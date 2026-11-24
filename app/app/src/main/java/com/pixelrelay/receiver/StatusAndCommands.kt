package com.pixelrelay.receiver

import com.pixelrelay.charge.WarningBook
import com.pixelrelay.core.Clock
import com.pixelrelay.core.GB
import com.pixelrelay.core.MetaStore
import com.pixelrelay.core.PixelStatus
import com.pixelrelay.core.Proto
import com.pixelrelay.core.ReceiverConfig
import com.pixelrelay.core.ReceiverLedger
import com.pixelrelay.core.RecvState
import com.pixelrelay.core.RemoteCommand
import com.pixelrelay.core.StatusCounts
import com.pixelrelay.core.WarnCode
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** 기기 상태 읽기 경계(배터리·온도). */
interface DeviceProbe {
    fun batteryPct(): Int
    fun charging(): Boolean
    fun tempC(): Double
}

/** 받기 설정을 장부 옆 키-값 저장소에 JSON 으로 보관한다. */
class ReceiverConfigStore(private val meta: MetaStore) {
    suspend fun get(): ReceiverConfig =
        meta.getString("receiverConfig")?.let { runCatching { ReceiverConfig.fromJson(JSONObject(it)) }.getOrNull() } ?: ReceiverConfig()

    suspend fun set(c: ReceiverConfig) = meta.putString("receiverConfig", c.toJson().toString())
}

/** 상태 객체(/status · status.json 공통)를 만든다 (설계서 9절). */
class StatusBuilder(
    private val ledger: ReceiverLedger,
    private val sink: MediaSink,
    private val probe: DeviceProbe,
    private val clock: Clock,
    private val config: suspend () -> ReceiverConfig,
    private val chargeWarnings: () -> Set<String>,
    private val driveAuthProblem: () -> Boolean,
    private val appVersion: String,
    private val warningBook: WarningBook,
) {
    companion object {
        const val STALL_MS = 6 * 3_600_000L
        const val LOW_SPACE = 4 * GB
    }

    suspend fun build(): PixelStatus {
        val now = clock.nowMs()
        val c = ledger.counts()
        val free = sink.freeBytes()
        val lastActivity = ledger.lastPhotosActivity()
        val active = LinkedHashSet<String>(chargeWarnings())
        if (free < LOW_SPACE) active += WarnCode.LOW_SPACE
        if (c.published > 0) {
            val oldest = ledger.oldestPublished() ?: now
            if (now - maxOf(lastActivity, oldest) >= STALL_MS) active += WarnCode.PHOTOS_STALLED
        }
        if (driveAuthProblem()) active += WarnCode.DRIVE_AUTH
        return PixelStatus(
            freeBytes = free,
            batteryPct = probe.batteryPct(),
            charging = probe.charging(),
            tempC = probe.tempC(),
            counts = StatusCounts(c.published, c.confirmed, c.vanished, c.failed),
            lastPhotosActivityAt = lastActivity,
            config = config(),
            warnings = warningBook.update(active),
            seq = ledger.maxSeq(),
            appVersion = appVersion,
            reportedAt = now,
            trialDoneAt = ledger.getLong(com.pixelrelay.core.TrialSignals.KEY_PHOTOS_DONE) ?: 0L,
            cleanupOpenedAt = ledger.getLong(PhotosConfirmer.KEY_BUTTON_AT) ?: 0L,
        )
    }

    /** status.json: 상태 + 최근 500건 변화 목록 + 최근 명령 결과. */
    suspend fun buildDriveDoc(commandResults: JSONArray): JSONObject {
        val s = build()
        val max = ledger.maxSeq()
        val ch = ledger.changesSince(maxOf(0L, max - 500), 500)
        return s.toJson()
            .put("changes", JSONArray().also { a -> ch.forEach { a.put(JSONObject().put("sha", it.sha).put("state", it.state).put("at", it.at).put("seq", it.seq)) } })
            .put("commandResults", commandResults)
    }
}

data class CommandResult(val ok: Boolean, val message: String)

/** 받는 쪽 명령 처리 (설계서 7절). 원격 명령은 LAN /command 와 드라이브 commands/ 두 경로로 온다. */
class CommandHandler(
    private val ledger: ReceiverLedger,
    private val configStore: ReceiverConfigStore,
    private val confirmer: PhotosConfirmer,
    private val clock: Clock,
    /** 수신 루프를 바로 깨우는 콜백 */
    private val wake: () -> Unit = {},
) {
    companion object {
        const val KEY_CMD_IDS = "processedCommandIds"
        const val ID_KEEP_MS = 7 * 24 * 3_600_000L
    }

    private val idMutex = kotlinx.coroutines.sync.Mutex()

    /** commandId 를 7일 보관하며 한 번만 허용한다(LAN·드라이브 명령 공통). 이미 썼으면 false. 호출자 트랜잭션 안에서 실행된다. */
    private suspend fun consumeCommandId(id: String): Boolean {
        val now = clock.nowMs()
        val o = ledger.getString(KEY_CMD_IDS)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        val keys = o.keys().asSequence().toList()
        for (k in keys) if (now - o.optLong(k) > ID_KEEP_MS) o.remove(k)
        if (o.has(id)) return false
        o.put(id, now)
        ledger.putString(KEY_CMD_IDS, o.toString())
        return true
    }

    private class Rollback(val result: CommandResult) : RuntimeException(null, null, false, false)

    /**
     * 파괴적 명령: commandId 기록과 상태 전이를 한 DB 트랜잭션으로 묶는다(§15).
     * 처리되지 않은 명령(실패 결과·예외)은 commandId 도 남지 않는다. 드라이브 삭제 같은 외부 동작은 커밋 뒤에 하며,
     * 실패해도 PHOTOS_CONFIRMED 상태를 보고 정리하는 기존 구조(Cleaner.cleanAllConfirmed)가 다시 시도한다.
     */
    suspend fun handle(cmd: RemoteCommand, remote: Boolean): CommandResult {
        if (cmd.type !in RemoteCommand.DESTRUCTIVE) return dispatch(cmd, remote)
        val id = cmd.id
        if (id.isNullOrBlank()) return CommandResult(false, "commandId 필요")
        val r = idMutex.withLock {
            try {
                ledger.inTransaction {
                    if (!consumeCommandId(id)) throw Rollback(CommandResult(false, "이미 처리한 commandId"))
                    val res = dispatch(cmd, remote)
                    if (!res.ok) throw Rollback(res)
                    res
                }
            } catch (rb: Rollback) {
                rb.result
            }
        }
        if (r.ok && cmd.type == RemoteCommand.CONFIRM_VANISHED) {
            cmd.sha?.let { runCatching { confirmer.cleanAfterConfirm(it) } }
        }
        return r
    }

    private suspend fun dispatch(cmd: RemoteCommand, remote: Boolean): CommandResult = when (cmd.type) {
        RemoteCommand.SEND_NOW, RemoteCommand.DRAIN_NOW -> {
            val expect = cmd.expectShas.filter { ReceiverCore.validSha(it) }
            if (cmd.type == RemoteCommand.SEND_NOW && expect.isNotEmpty()) {
                // 이번 배치 sha 가 모두 받기 장부에 오르거나 24시간이 지날 때까지 강제 수신 유지
                ledger.putString(DriveReceiver.KEY_EXPECT, expect.joinToString(","))
                ledger.putLong(DriveReceiver.KEY_EXPECT_UNTIL, clock.nowMs() + DriveReceiver.EXPECT_MS)
            } else {
                ledger.putLong(DriveReceiver.KEY_FORCE, 1)
            }
            wake()
            CommandResult(true, "받기 시작")
        }
        RemoteCommand.RESEND_VANISHED -> {
            val sha = cmd.sha
            val e = if (sha != null) ledger.get(sha) else null
            if (e == null || e.stateEnum != RecvState.VANISHED_UNKNOWN) CommandResult(false, "대상 아님")
            else {
                ledger.transition(e.sha, RecvState.RESEND_REQUESTED)
                wake()
                CommandResult(true, "다시 받기 허용")
            }
        }
        RemoteCommand.SET_CONFIG -> {
            val c = cmd.config
            if (c == null) CommandResult(false, "설정 값 없음") else { configStore.set(c); CommandResult(true, "설정 변경됨") }
        }
        RemoteCommand.CLEANUP_OPENED ->
            // 픽셀 화면 전용이라 원격으로는 받지 않는다.
            if (remote) CommandResult(false, "원격 불가") else { confirmer.recordButton(); CommandResult(true, "기록됨") }
        RemoteCommand.CONFIRM_VANISHED -> {
            val sha = cmd.sha
            if (sha == null) CommandResult(false, "sha 없음")
            else if (confirmer.userConfirmTransition(sha)) CommandResult(true, "포토 확인됨으로 확정") else CommandResult(false, "대상 아님")
        }
        else -> CommandResult(false, "알 수 없는 명령")
    }

    /** 최근 명령 결과(최대 20개)를 장부 옆에 쌓아 status.json 으로 내보낸다. */
    suspend fun recordResult(cmd: RemoteCommand, r: CommandResult) {
        val old = ledger.getString("cmdResults")?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
        val list = ArrayList<JSONObject>()
        for (i in 0 until old.length()) list += old.getJSONObject(i)
        list += JSONObject().put("id", cmd.id ?: "").put("type", cmd.type).put("ok", r.ok).put("msg", r.message).put("at", clock.nowMs())
        val trimmed = list.takeLast(20)
        ledger.putString("cmdResults", JSONArray(trimmed).toString())
    }

    suspend fun results(): JSONArray = ledger.getString("cmdResults")?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
}
