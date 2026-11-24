package com.pixelrelay.sender

import com.pixelrelay.core.Clock
import com.pixelrelay.core.LedgerItem
import com.pixelrelay.core.MetaStore
import com.pixelrelay.core.NetworkState
import com.pixelrelay.core.PixelStatus
import com.pixelrelay.core.Reason
import com.pixelrelay.core.RemoteCommand
import com.pixelrelay.core.SendState
import com.pixelrelay.core.SenderLedger
import com.pixelrelay.core.SenderSettings
import com.pixelrelay.core.db.SendEntry
import com.pixelrelay.drive.DriveApi
import com.pixelrelay.drive.DriveAuthException
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID

/** 주력 폰이 픽셀에 붙는 방법(NSD 또는 마지막 IP). 비과금 와이파이가 아니거나 응답이 없으면 null. */
interface LanConnector {
    suspend fun connect(): PixelApi?
}

/** 알림 경계(안드로이드 구현 / 테스트 가짜). */
interface SenderNotifier {
    fun pixelWarning(code: String, since: Long)
    fun driveAuthNeeded()
}

object NoNotifier : SenderNotifier {
    override fun pixelWarning(code: String, since: Long) {}
    override fun driveAuthNeeded() {}
}

data class EngineReport(
    val scan: ScanReport,
    val sentFiles: Int,
    val lanReachable: Boolean,
    /** 아직 보낼 게 남아 바로 다음 워커를 예약해야 하는가 */
    val moreWork: Boolean,
    /** 안정 조건 때문에 곧(약 70초 뒤) 다시 봐야 하는가 */
    val recheckSoon: Boolean,
)

/**
 * 워커 1회분 일: 발견 -> (LAN 이면 장부·상태 맞춤) -> 경로 판단 -> 전송.
 * 포그라운드 서비스 없이 최대 약 5분 분량만 보내고 끝난다(설계서 4절).
 */
class SenderEngine(
    private val scanner: MediaScanner,
    private val source: MediaSource,
    private val ledger: SenderLedger,
    private val meta: MetaStore,
    private val clock: Clock,
    private val network: NetworkState,
    private val drive: DriveApi,
    private val lan: LanConnector,
    private val lanSender: LanSender,
    private val driveSender: DriveSender,
    private val settings: () -> SenderSettings,
    private val usage: DailyUsage,
    private val registrar: DrainRegistrar,
    private val sync: LedgerSync,
    private val statusStore: RemoteStatusStore,
    private val notifier: SenderNotifier = NoNotifier,
    private val budgetMs: Long = 5 * 60_000L,
    /** 다시 보내기 명령 재시도용(없으면 재시도 안 함) */
    private val commands: SenderCommands? = null,
    /** 무료 확인 시험을 한 걸음 진행시키는 훅(없으면 안 함) */
    private val trialStep: (suspend () -> Unit)? = null,
) {
    companion object {
        /** 프로세스 전역: 전송 엔진 실행은 한 번에 하나(§15). 여러 워커가 같은 장부를 동시에 처리하지 않는다. */
        private val RUN_MUTEX = kotlinx.coroutines.sync.Mutex()
        const val KEY_DRIVE_AUTH = "driveAuthProblem"
        const val KEY_DRIVE_STATUS_AT = "driveStatusAt"
        const val KEY_NOTIFIED = "notifiedWarnings"
        const val DRIVE_STATUS_EVERY_MS = 5 * 60_000L
        const val RESEND_RETRY_MS = 5 * 60_000L
    }

    suspend fun runOnce(forceNow: Boolean = false): EngineReport =
        RUN_MUTEX.withLock { runLocked(forceNow) }

    private suspend fun runLocked(forceNow: Boolean): EngineReport {
        val deadline = clock.nowMs() + budgetMs
        usage.releaseStale()   // 이전 실행이 끊겨 정산 못 한 예약을 해제(엔진은 한 번에 하나라 지금은 진행 중인 예약이 없다)
        val scan = scanner.scan()
        val net = network.snapshot()

        // LAN: 비과금 와이파이 + 서명된 /status 성공
        var api: PixelApi? = null
        if (net.connected && net.unmeteredWifi) {
            api = try { lan.connect() } catch (e: Exception) { null }
        }
        if (api != null) {
            try {
                val st = api.status()
                statusStore.save(st, "lan")
                handleWarnings(st)
                sync.syncViaLan(api)
            } catch (e: Exception) {
                api = null
            }
        }
        // 다시 보내기 명령 재시도(영속): 픽셀이 RESEND_REQUESTED 로 확인해 줄 때까지 RESEND_PENDING 유지
        val lanApi = api
        if (retryResends() && lanApi != null) runCatching { sync.syncViaLan(lanApi) }
        var lanOk = api != null
        val lanWasOk = lanOk

        // 드라이브 쪽 상태 보고(5분에 한 번 이하)
        if (net.connected) syncDriveStatusIfDue()

        // 전송
        val settingsNow = settings()
        val entries = ledger.sendable().filter { !it.sha.startsWith("m") }
        var driveQueue: Long? = null
        var driveRemaining: Long? = null
        var driveInfoTried = false
        var driveBlocked = false
        var sent = 0
        var more = false

        for (e in entries) {
            if (clock.nowMs() > deadline) { more = true; break }

            if (!driveInfoTried && !lanOk && net.connected) {
                driveInfoTried = true
                try {
                    driveQueue = drive.listQueue().sumOf { it.size }
                    driveRemaining = drive.about().remaining
                    meta.putLong(KEY_DRIVE_AUTH, 0)
                } catch (ex: DriveAuthException) {
                    driveBlocked = true
                    meta.putLong(KEY_DRIVE_AUTH, 1)
                    notifier.driveAuthNeeded()
                } catch (ex: Exception) {
                    // 일시 오류: 용량 정보 없이 진행(전송 시점에 다시 오류가 나면 그때 처리)
                }
            }

            val route = RoutePlanner.decide(
                PlanInput(e, net, lanOk, settingsNow, usage.today(), driveQueue, driveRemaining, forceNow),
            )
            if (e.stateEnum == SendState.SENDING_LAN && route != Route.Lan) {
                // LAN 이 끊김 -> 대기로 돌아가 다시 고른다(offset 은 픽셀이 기억)
                ledger.tryTransition(e.sha, SendState.WAITING, Reason.NETWORK)
            }
            // 한 파일의 오류가 나머지 파일을 막지 않도록 파일별로 감싼다(§15)
            try {
                when (route) {
                    Route.NothingToSend -> {}
                    is Route.Wait -> {
                        ledger.wait(e.sha, route.reason)
                        if (route.registerDrain) autoDrain()
                    }
                    Route.Lan -> {
                        if (recheck(e)) continue
                        val r = lanSender.send(e, api!!, deadline)
                        if (handleLan(e, r)) sent++
                        if (r is SendResult.Partial) more = true
                        if (r is SendResult.Network) { api = null; lanOk = false }
                    }
                    Route.Drive -> {
                        if (driveBlocked) { ledger.wait(e.sha, Reason.PERMISSION); continue }
                        if (recheck(e)) continue
                        // 하루 사용량: 보내기 전에 별도 예약으로 잡고, 끝나면(취소돼도) 실제 바이트로 정산(§15)
                        val reserve = if (net.metered) maxOf(0L, e.size - e.driveOffset) else 0L
                        if (reserve > 0) usage.reserve(reserve)
                        var actual = 0L
                        try {
                            val r = driveSender.send(e, deadline)
                            actual = bytesOf(r)
                            if (handleDrive(e, r)) sent++
                            if (r is DriveSendResult.Partial) more = true
                            if (r is DriveSendResult.Auth) driveBlocked = true
                        } finally {
                            if (reserve > 0) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { usage.settle(reserve, actual) }
                        }
                    }
                }
            } catch (ex: kotlinx.coroutines.CancellationException) {
                throw ex
            } catch (ex: Exception) {
                ledger.recordFailure(e.sha, "예외: ${ex.message}")
            }
        }
        try { trialStep?.invoke() } catch (ex: kotlinx.coroutines.CancellationException) { throw ex } catch (ex: Exception) { /* 시험은 전송을 막지 않는다 */ }
        return EngineReport(scan, sent, lanWasOk, more, scan.recheckSoon)
    }

    private suspend fun handleLan(e: SendEntry, r: SendResult): Boolean {
        when (r) {
            is SendResult.Done -> { ledger.tryTransition(e.sha, SendState.RECEIVED); return true }
            is SendResult.Partial -> {}
            is SendResult.Network -> ledger.wait(e.sha, Reason.NETWORK, 30_000)
            is SendResult.NoSpace ->
                if (r.permanent) ledger.tryTransition(e.sha, SendState.SKIPPED, Reason.TOO_LARGE)
                else ledger.wait(e.sha, Reason.PIXEL_SPACE, 10 * 60_000L)
            SendResult.Busy -> ledger.wait(e.sha, Reason.NETWORK, 60_000)
            SendResult.ResendNotYet -> ledger.tryTransition(e.sha, SendState.RESEND_PENDING) { it.copy(nextAttemptAt = 0) }
            SendResult.Hot -> ledger.wait(e.sha, Reason.NETWORK, 5 * 60_000L)
            SendResult.OriginalGone -> ledger.tryTransition(e.sha, SendState.SKIPPED, Reason.ORIGINAL_MISSING)
            is SendResult.Failed -> ledger.recordFailure(e.sha, r.why)
        }
        return false
    }

    private fun bytesOf(r: DriveSendResult): Long = when (r) {
        is DriveSendResult.Done -> r.bytes
        is DriveSendResult.Partial -> r.bytes
        is DriveSendResult.Network -> r.bytes
        is DriveSendResult.Retry -> r.bytes
        is DriveSendResult.Failed -> r.bytes
        else -> 0L
    }

    /**
     * 전송 직전 재검사(§15): 미디어가 기록 중이거나 크기·수정 시각이 등록 때와 다르면 보내지 않는다.
     * 기록 중이면 대기, 내용이 바뀌었으면 옛 항목을 SKIPPED(원본변경)로 두고 안정되면 새 sha 로 다시 등록된다.
     * @return 이번 회차에 이 항목을 보내지 않아야 하면 true
     */
    private suspend fun recheck(e: SendEntry): Boolean {
        val m = source.stat(e.mediaId) ?: return false   // 사라졌으면 전송 단계가 원본없음으로 처리
        if (m.isPending) {
            ledger.wait(e.sha, Reason.RECORDING, 60_000)
            return true
        }
        var changed = m.size != e.size || (e.dateModifiedSec != 0L && m.dateModifiedSec != e.dateModifiedSec)
        if (!changed && e.dateModifiedSec == 0L) {
            // 수정 시각 기준이 없는 행: 통과시키지 않고 해시를 다시 확인한다(같으면 기준 시각을 채워 다음부터는 빠르게)
            val ins = source.openOriginal(e.uri) ?: return false
            val h = ins.use { com.pixelrelay.core.Hashing.sha256Hex(it) }
            if (h != e.sha) changed = true
            else ledger.patch(e.sha) { it.copy(dateModifiedSec = m.dateModifiedSec) }
        }
        if (changed) {
            ledger.tryTransition(e.sha, SendState.SKIPPED, Reason.ORIGINAL_CHANGED)
            ledger.putSeen(com.pixelrelay.core.db.SeenMedia(e.mediaId, m.size, m.dateModifiedSec, clock.nowMs(), null))
            return true
        }
        return false
    }

    private suspend fun handleDrive(e: SendEntry, r: DriveSendResult): Boolean {
        when (r) {
            is DriveSendResult.Done -> return true
            is DriveSendResult.Partial -> {}
            is DriveSendResult.Retry -> ledger.wait(e.sha, Reason.NETWORK, 5 * 60_000L)
            is DriveSendResult.Network -> ledger.wait(e.sha, Reason.NETWORK, 30_000)
            DriveSendResult.Auth -> {
                meta.putLong(KEY_DRIVE_AUTH, 1)
                notifier.driveAuthNeeded()
                ledger.wait(e.sha, Reason.PERMISSION)
            }
            DriveSendResult.OriginalGone -> ledger.tryTransition(e.sha, SendState.SKIPPED, Reason.ORIGINAL_MISSING)
            is DriveSendResult.Failed -> ledger.recordFailure(e.sha, r.why)
        }
        return false
    }

    /** RESEND_PENDING 항목의 resendVanished 명령을 (아직 확인 안 됐으면) 5분마다 다시 보낸다. 명령을 보냈으면 true. */
    private suspend fun retryResends(): Boolean {
        val c = commands ?: return false
        var any = false
        val now = clock.nowMs()
        for (e in ledger.pendingResends()) {
            if (e.nextAttemptAt > now) continue
            val id = e.resendCommandId ?: continue
            try {
                c.send(RemoteCommand(RemoteCommand.RESEND_VANISHED, sha = e.sha, id = id))
                any = true
            } catch (ex: kotlinx.coroutines.CancellationException) {
                throw ex
            } catch (ex: Exception) {
                // 실패해도 상태는 RESEND_PENDING 그대로: 다음 회차에 같은 commandId 로 다시 시도
            }
            ledger.patch(e.sha) { it.copy(nextAttemptAt = now + RESEND_RETRY_MS) }
        }
        return any
    }

    private suspend fun autoDrain() {
        registrar.registerOnce {
            val id = UUID.randomUUID().toString()
            drive.createCommand(id, RemoteCommand(RemoteCommand.DRAIN_NOW, id = id).toJson().toString())
        }
    }

    /** 드라이브 status.json 을 읽어 상태 + 변화 목록을 반영한다. */
    suspend fun syncDriveStatusIfDue(force: Boolean = false) {
        val now = clock.nowMs()
        val last = meta.getLong(KEY_DRIVE_STATUS_AT) ?: 0L
        if (!force && now - last < DRIVE_STATUS_EVERY_MS) return
        meta.putLong(KEY_DRIVE_STATUS_AT, now)
        try {
            val raw = drive.getStatus() ?: return
            val o = JSONObject(raw)
            val st = PixelStatus.fromJson(o)
            statusStore.save(st, "drive")
            val arr = o.optJSONArray("changes")
            if (arr != null) {
                val items = (0 until arr.length()).map { LedgerItem.fromJson(arr.getJSONObject(it)) }
                sync.applyChanges(items)
            }
            sync.noteRemoteSeq(st.seq)
            handleWarnings(st)
            meta.putLong(KEY_DRIVE_AUTH, 0)
        } catch (e: DriveAuthException) {
            meta.putLong(KEY_DRIVE_AUTH, 1)
            notifier.driveAuthNeeded()
        } catch (e: Exception) {
            // 상태 읽기는 실패해도 전송을 막지 않는다
        }
    }

    /** 새 경고(코드+시작 시각)만 알림으로 띄운다. */
    private suspend fun handleWarnings(st: PixelStatus) {
        val seen = (meta.getString(KEY_NOTIFIED) ?: "").split('|').filter { it.isNotEmpty() }.toMutableSet()
        var changed = false
        val current = st.warnings.map { "${it.code}@${it.since}" }.toSet()
        for (w in st.warnings) {
            val k = "${w.code}@${w.since}"
            if (seen.add(k)) { notifier.pixelWarning(w.code, w.since); changed = true }
        }
        // 없어진 경고는 기억에서 지워 다시 생기면 새로 알린다
        if (seen.retainAll(current)) changed = true
        if (changed) meta.putString(KEY_NOTIFIED, seen.joinToString("|"))
    }
}

/** 주력 폰 -> 픽셀 명령 보내기: LAN 이 되면 LAN, 아니면 드라이브 commands/ (설계서 7절). */
class SenderCommands(
    private val lan: LanConnector,
    private val drive: DriveApi,
    private val network: NetworkState,
) {
    suspend fun send(cmd0: RemoteCommand): String {
        // 모든 명령에 commandId 를 붙인다(파괴적 명령은 픽셀이 한 번만 처리)
        val cmd = if (cmd0.id.isNullOrBlank()) cmd0.copy(id = UUID.randomUUID().toString()) else cmd0
        val net = network.snapshot()
        if (net.unmeteredWifi) {
            val api = try { lan.connect() } catch (e: Exception) { null }
            if (api != null) {
                try { if (api.command(cmd)) return "LAN" } catch (e: Exception) { /* 드라이브로 */ }
            }
        }
        drive.createCommand(cmd.id!!, cmd.toJson().toString())
        return "드라이브"
    }
}
