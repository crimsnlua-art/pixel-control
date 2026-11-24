package com.pixelrelay.sender

import com.pixelrelay.core.Clock
import com.pixelrelay.core.MetaStore
import com.pixelrelay.core.NetSnapshot
import com.pixelrelay.core.Proto
import com.pixelrelay.core.Reason
import com.pixelrelay.core.SendState
import com.pixelrelay.core.SenderSettings
import com.pixelrelay.core.db.SendEntry
import java.time.Instant
import java.time.ZoneId

sealed class Route {
    object Lan : Route()
    object Drive : Route()

    /** 이미 드라이브에 올라가 있음: LAN 으로 다시 보내지 않고 픽셀이 받아 가길 기다린다. */
    object NothingToSend : Route()
    data class Wait(val reason: String, val registerDrain: Boolean = false) : Route()
}

data class PlanInput(
    val entry: SendEntry,
    val net: NetSnapshot,
    /** 비과금 와이파이이고 픽셀에 서명된 /status 가 성공했는가 */
    val lanReachable: Boolean,
    val settings: SenderSettings,
    /** 오늘 모바일 데이터로 쓴 바이트 */
    val usedTodayBytes: Long,
    /** 드라이브 queue 합계(알면) */
    val driveQueueBytes: Long?,
    /** 드라이브 계정 남은 용량(알면) */
    val driveRemainingBytes: Long?,
    /** "지금 보내기": 데이터 조건(영상·모바일·하루 한도)을 무시 */
    val forceNow: Boolean = false,
)

/** 경로 판단(설계서 4절): LAN 우선, 그 밖은 드라이브. 순수 함수. */
object RoutePlanner {
    fun decide(i: PlanInput): Route {
        val e = i.entry
        val st = e.stateEnum
        if (st == SendState.IN_DRIVE) return Route.NothingToSend
        if (!i.net.connected) return Route.Wait(Reason.NETWORK)

        // 드라이브 업로드를 이미 시작했으면 집에 와도 마저 끝낸다(경로 혼선 방지)
        val continueDrive = st == SendState.UPLOADING_DRIVE

        if (!continueDrive && i.net.unmeteredWifi && i.lanReachable) return Route.Lan

        // ---- 드라이브 경로 ----
        if (i.net.roaming) return Route.Wait(Reason.NETWORK) // 로밍 금지(지금 보내기여도 막는다)
        if (i.net.metered) {
            if (e.isVideo && !i.settings.videoOverMobile && !i.forceNow) return Route.Wait(Reason.NETWORK)
            if (!e.isVideo && !i.settings.photoOverMobile && !i.forceNow) return Route.Wait(Reason.NETWORK)
            if (!i.forceNow && i.usedTodayBytes + e.size > i.settings.dailyLimitBytes) return Route.Wait(Reason.DAILY_LIMIT)
        }
        if (!continueDrive) {
            val q = i.driveQueueBytes
            val r = i.driveRemainingBytes
            if (q != null && r != null && q + e.size > r - Proto.DRIVE_SEND_MARGIN) {
                return Route.Wait(Reason.DRIVE_QUOTA, registerDrain = true)
            }
        }
        return Route.Drive
    }
}

/** 오늘 모바일 데이터 사용량(날짜별 키). */
class DailyUsage(private val meta: MetaStore, private val clock: Clock, private val zone: ZoneId = ZoneId.systemDefault()) {
    private fun key() = "usage:" + Instant.ofEpochMilli(clock.nowMs()).atZone(zone).toLocalDate()
    companion object { const val KEY_RESERVED = "usageReserved" }

    /** 실제로 쓴 양 */
    suspend fun actualToday(): Long = meta.getLong(key()) ?: 0L

    /** 보내기 전에 잡아 둔 예약(아직 정산 전) */
    suspend fun reserved(): Long = meta.getLong(KEY_RESERVED) ?: 0L

    /** 한도 판단에 쓰는 값 = 실제 + 예약 */
    suspend fun today(): Long = actualToday() + reserved()

    suspend fun add(bytes: Long) { meta.putLong(key(), actualToday() + bytes) }
    suspend fun reserve(bytes: Long) { meta.putLong(KEY_RESERVED, reserved() + bytes) }

    /** 예약을 풀고 실제로 보낸 바이트를 사용량에 더한다. */
    suspend fun settle(reservedBytes: Long, actualBytes: Long) {
        meta.putLong(KEY_RESERVED, maxOf(0L, reserved() - reservedBytes))
        add(actualBytes)
    }

    /** 엔진 시작 때: 이전 실행이 끊겨 남은 미정산 예약을 해제한다. */
    suspend fun releaseStale() { if (reserved() != 0L) meta.putLong(KEY_RESERVED, 0L) }
}

/** 드라이브 용량 부족 때 `drainNow` 명령을 1시간에 한 번까지만 자동 등록한다. */
class DrainRegistrar(private val meta: MetaStore, private val clock: Clock) {
    companion object {
        const val KEY = "lastAutoDrainAt"
        const val INTERVAL_MS = 60 * 60_000L
    }

    suspend fun shouldRegister(): Boolean {
        val last = meta.getLong(KEY) ?: return true
        return clock.nowMs() - last >= INTERVAL_MS
    }

    /** 등록했으면 true */
    suspend fun registerOnce(register: suspend () -> Unit): Boolean {
        if (!shouldRegister()) return false
        register()
        meta.putLong(KEY, clock.nowMs())
        return true
    }
}
