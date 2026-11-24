package com.pixelrelay.receiver

import com.pixelrelay.core.Clock
import com.pixelrelay.core.QueueMode
import com.pixelrelay.core.ReceiverConfig
import com.pixelrelay.core.ReceiverLedger
import com.pixelrelay.core.RecvState
import com.pixelrelay.drive.DriveApi
import com.pixelrelay.drive.DriveFile

/** 수신 모드 판단(설계서 7절). 순수 함수라 단독 테스트한다. */
object ReceivePolicy {
    /** 실제 문턱 = min(설정 용량, 계정 남은 용량 − 여유). */
    fun effectiveThreshold(cfg: ReceiverConfig, remainingBytes: Long): Long =
        minOf(cfg.thresholdBytes, remainingBytes - cfg.reserveBytes)

    /**
     * @param pendingBytes 아직 받지 않은 큐 파일 합계
     * @param forced sendNow/drainNow 를 받은 상태
     * @param draining 이미 문턱을 넘어 비우는 중
     */
    fun shouldReceive(cfg: ReceiverConfig, pendingBytes: Long, remainingBytes: Long, forced: Boolean, draining: Boolean): Boolean {
        if (pendingBytes <= 0) return false
        if (forced || draining) return true
        return when (cfg.queueMode) {
            QueueMode.IMMEDIATE -> true
            // 남은 용량이 작아 문턱이 0 이하가 돼도, 대기 파일이 있으면 받는다(교착 방지)
            QueueMode.CAPACITY -> pendingBytes >= effectiveThreshold(cfg, remainingBytes)
        }
    }

    /** 목록을 보는 주기: 바로 = 2분, 용량 = 10분 (설계서 7절). 명령 확인은 항상 2분. */
    fun listIntervalMs(mode: QueueMode): Long = if (mode == QueueMode.IMMEDIATE) 2 * 60_000L else 10 * 60_000L
}

data class DriveRunReport(
    val listed: Int,
    val downloaded: Int,
    val attachedOnly: Int,
    val skippedBusy: Int,
    val received: Boolean,
)

/** 드라이브 수신 루프 한 회차(설계서 7절). */
class DriveReceiver(
    private val drive: DriveApi,
    private val core: ReceiverCore,
    private val ledger: ReceiverLedger,
    private val cleaner: Cleaner,
    private val config: suspend () -> ReceiverConfig,
    private val clock: Clock,
) {
    companion object {
        const val OWNER = "drive"
        const val KEY_FORCE = "forceDrain"
        const val KEY_DRAINING = "draining"
        const val KEY_EXPECT = "expectShas"
        const val KEY_EXPECT_UNTIL = "expectUntil"
        const val EXPECT_MS = 24 * 3_600_000L
        const val MAX_HASH_FAILS = 3
    }

    /**
     * sendNow 의 expectShas: 이번 배치 sha 가 모두 받기 장부에 오르거나 24시간이 지날 때까지 강제 수신 유지(§15).
     * 업로드가 아직 안 끝나 큐에 없는 sha 가 있어도 소진되지 않는다.
     */
    suspend fun expectActive(): Boolean {
        val raw = ledger.getString(KEY_EXPECT)
        if (raw.isNullOrEmpty()) return false
        val until = ledger.getLong(KEY_EXPECT_UNTIL) ?: 0L
        val shas = raw.split(',').filter { it.isNotEmpty() }
        if (clock.nowMs() > until || shas.all { ledger.get(it) != null }) {
            ledger.putString(KEY_EXPECT, "")
            return false
        }
        return true
    }

    /** 수신 루프가 목록 조회 간격을 기다리지 않고 지금 돌아야 하는가: 강제 표시 또는 활성 expectShas */
    suspend fun forcedNow(): Boolean = (ledger.getLong(KEY_FORCE) ?: 0L) == 1L || expectActive()

    suspend fun runOnce(): DriveRunReport {
        val quota = drive.about()
        // 입력 검증: sha = 64자리 소문자 hex, 크기 >= 0 (경로 순회 등 방지)
        val files = drive.listQueue().filter {
            val sha = it.sha
            sha != null && ReceiverCore.validSha(sha) && it.size >= 0 && (it.props["size"]?.toLongOrNull() ?: it.size) >= 0
        }
        val cfg = config()
        var attachedOnly = 0
        var busy = 0
        var downloaded = 0

        // 1) 사본 표에 붙이기 (받기 장부에 이미 있으면 내려받지 않는다)
        val pending = ArrayList<DriveFile>()
        val seenSha = HashSet<String>()
        var pendingBytes = 0L
        for (f in files) {
            val sha = f.sha!!
            ledger.attachCopy(sha, f.id, f.size)
            val e = ledger.get(sha)
            if (e == null || e.stateEnum == RecvState.RECEIVING || e.stateEnum == RecvState.RESEND_REQUESTED) {
                // 문턱 합계 = 미수신 사본 전체 바이트(중복 sha 포함), 다운로드는 sha 당 1회
                pendingBytes += f.size
                if (seenSha.add(sha)) pending += f
            } else {
                attachedOnly++
                // 뒤늦게 붙은 사본이라도 이미 포토 확인 뒤면 바로 정리
                cleaner.clean(sha)
            }
        }

        val forced = forcedNow()
        val draining = (ledger.getLong(KEY_DRAINING) ?: 0L) == 1L
        val go = ReceivePolicy.shouldReceive(cfg, pendingBytes, quota.remaining, forced, draining)
        if (!go) {
            if (pendingBytes == 0L) { ledger.putLong(KEY_DRAINING, 0); ledger.putLong(KEY_FORCE, 0) }
            return DriveRunReport(files.size, 0, attachedOnly, 0, false)
        }
        if (!draining && cfg.queueMode == QueueMode.CAPACITY) ledger.putLong(KEY_DRAINING, 1)

        // 2) 받기
        var stopAll = false
        for (f in pending) {
            if (stopAll) break
            when (receiveOne(f)) {
                One.DONE -> downloaded++
                One.BUSY -> busy++
                One.STOP -> stopAll = true
                One.FAILED -> {}
            }
        }

        // 3) 다 비웠으면 "비우는 중"·명령 표시 해제
        val left = ledger.all().count { (it.stateEnum == RecvState.RECEIVING || it.stateEnum == RecvState.RESEND_REQUESTED) && seenSha.contains(it.sha) }
        if (!stopAll && left == 0) { ledger.putLong(KEY_DRAINING, 0); ledger.putLong(KEY_FORCE, 0) }
        expectActive() // 이번 수신으로 기대 목록이 다 채워졌으면 여기서 해제
        return DriveRunReport(files.size, downloaded, attachedOnly, busy, true)
    }

    private enum class One { DONE, BUSY, STOP, FAILED }

    private suspend fun receiveOne(f: DriveFile): One {
        val sha = f.sha!!
        val name = f.props["name"] ?: f.name.substringAfterLast('/')
        val size = f.props["size"]?.toLongOrNull() ?: f.size
        val takenAt = f.props["takenAt"]?.toLongOrNull() ?: 0L
        val mime = f.props["mime"] ?: "application/octet-stream"

        val cur = ledger.get(sha)
        if (cur != null && cur.stateEnum == RecvState.RECEIVING && cur.failCount >= MAX_HASH_FAILS) {
            ledger.transition(sha, RecvState.FAILED, "해시 불일치 ${cur.failCount}회")
            return One.FAILED
        }
        return when (val b = core.begin(sha, name, size, takenAt, mime, OWNER)) {
            BeginResult.Already -> One.DONE
            BeginResult.Busy -> One.BUSY
            BeginResult.Hot -> One.STOP
            BeginResult.Invalid, BeginResult.Mismatch -> One.FAILED
            is BeginResult.NoSpace -> if (b.permanent) One.FAILED else One.STOP
            is BeginResult.Ok -> {
                try {
                    drive.openDownload(f.id, b.offset).use { core.put(sha, OWNER, b.offset, it, size - b.offset) }
                } catch (e: Exception) {
                    core.release(sha, OWNER)
                    return One.FAILED
                }
                when (core.commit(sha, OWNER)) {
                    CommitOutcome.Published -> One.DONE
                    CommitOutcome.Mismatch -> {
                        val e = ledger.get(sha)
                        if (e != null && e.failCount >= MAX_HASH_FAILS && e.stateEnum == RecvState.RECEIVING) {
                            ledger.transition(sha, RecvState.FAILED, "해시 불일치 ${e.failCount}회")
                        }
                        One.FAILED
                    }
                    else -> { core.release(sha, OWNER); One.FAILED }
                }
            }
        }
    }
}
