package com.pixelrelay.charge

import com.pixelrelay.core.Clock
import com.pixelrelay.core.StatusWarning
import com.pixelrelay.core.WarnCode

/** 스마트 플러그 경계. 1단계에는 제품 코드가 없어 NoPlug 만 쓴다. */
interface PlugController {
    val available: Boolean
    suspend fun turnOn()
    suspend fun turnOff()
}

object NoPlug : PlugController {
    override val available: Boolean = false
    override suspend fun turnOn() {}
    override suspend fun turnOff() {}
}

enum class PlugAction { TURN_OFF, TURN_ON, KEEP }

data class ChargeDecision(
    val plugAction: PlugAction,
    /** 고온으로 받기를 멈춰야 하는가 */
    val receiveStopped: Boolean,
    /** 지금 켜져 있는 충전·온도 관련 경고 코드 */
    val active: Set<String>,
    /** 이번 평가에서 사용자에게 새로 알려야 하는 경고 코드(30분 간격 제한 적용 후) */
    val emit: Set<String>,
)

/**
 * 충전 정책 (설계서 10절).
 * - 80% 이상 → 끄기 판단, 60% 이하 → 켜기 판단, 사이는 유지.
 * - 온도 > 40 → 받기 중지, < 38 → 재개 (사이는 이전 상태 유지).
 * - 플러그가 없으면(NoPlug) 경고만. 같은 경고는 30분 간격.
 */
class ChargePolicy(private val clock: Clock) {
    companion object {
        const val OFF_AT = 80
        const val ON_AT = 60
        const val LOW_BATTERY_AT = 20
        const val HOT_STOP_C = 40.0
        const val HOT_RESUME_C = 38.0
        const val WARN_INTERVAL_MS = 30 * 60_000L
    }

    private var stopped = false
    private val lastEmit = HashMap<String, Long>()

    val receiveStopped: Boolean get() = stopped

    fun evaluate(batteryPct: Int, charging: Boolean, tempC: Double): ChargeDecision {
        if (tempC > HOT_STOP_C) stopped = true else if (tempC < HOT_RESUME_C) stopped = false

        val action = when {
            batteryPct >= OFF_AT -> PlugAction.TURN_OFF
            batteryPct <= ON_AT -> PlugAction.TURN_ON
            else -> PlugAction.KEEP
        }

        val active = LinkedHashSet<String>()
        if (stopped) active += WarnCode.HIGH_TEMP
        if (charging && batteryPct >= OFF_AT) active += WarnCode.UNPLUG_SUGGESTED
        if (!charging && batteryPct <= ON_AT) active += WarnCode.PLUG_SUGGESTED
        if (batteryPct <= LOW_BATTERY_AT) active += WarnCode.LOW_BATTERY

        val now = clock.nowMs()
        val emit = LinkedHashSet<String>()
        for (c in active) {
            val last = lastEmit[c]
            // 같은 경고는 30분 간격으로만 다시 알린다
            if (last == null || now - last >= WARN_INTERVAL_MS) {
                emit += c
                lastEmit[c] = now
            }
        }
        return ChargeDecision(action, stopped, active, emit)
    }
}

/** 켜져 있는 경고의 시작 시각(since)을 유지한다. */
class WarningBook(private val clock: Clock) {
    private val since = LinkedHashMap<String, Long>()

    @Synchronized
    fun update(active: Set<String>): List<StatusWarning> {
        val now = clock.nowMs()
        since.keys.retainAll(active)
        for (c in active) since.putIfAbsent(c, now)
        return since.map { StatusWarning(it.key, it.value) }
    }
}
