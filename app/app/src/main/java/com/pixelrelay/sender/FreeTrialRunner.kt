package com.pixelrelay.sender

import com.pixelrelay.core.Clock
import com.pixelrelay.core.FreeTrialMachine
import com.pixelrelay.core.MetaStore
import com.pixelrelay.core.SenderLedger
import com.pixelrelay.core.StartResult
import com.pixelrelay.core.TrialFile
import com.pixelrelay.core.TrialPhase
import com.pixelrelay.core.TrialSnapshot
import com.pixelrelay.core.TrialState
import com.pixelrelay.drive.DriveApi
import com.pixelrelay.drive.DriveAuthException
import org.json.JSONObject

/** 시험 상태를 장부 옆 키-값 저장소에 JSON 으로 영속 보관한다. */
class TrialStore(private val meta: MetaStore) {
    companion object { const val KEY = "freeTrial" }

    suspend fun get(): TrialState? =
        meta.getString(KEY)?.let { runCatching { TrialState.fromJson(JSONObject(it)) }.getOrNull() }

    suspend fun put(s: TrialState) = meta.putString(KEY, s.toJson().toString())
    suspend fun clear() = meta.putString(KEY, "")
}

sealed class TrialStartOutcome {
    data class Started(val state: TrialState) : TrialStartOutcome()

    /** 구글 계정 연결 필요(시작 불가) */
    data class AccountRequired(val detail: String) : TrialStartOutcome()
    data class Rejected(val message: String) : TrialStartOutcome()
}

/**
 * 무료 업로드 확인 시험 진행기(주력 폰). 전송은 일반 경로(엔진)가 하고,
 * 이 클래스는 시작(사용량 시작값·사진 등록)과 상태 전이를 영속적으로 이끈다. 판정 논리는 [FreeTrialMachine].
 */
class FreeTrialRunner(
    private val store: TrialStore,
    private val drive: DriveApi,
    private val source: MediaSource,
    private val scanner: MediaScanner,
    private val ledger: SenderLedger,
    private val statusStore: RemoteStatusStore,
    private val clock: Clock,
) {
    suspend fun current(): TrialState? = store.get()

    suspend fun reset() = store.clear()

    /** 픽셀이 보고한 시험 신호의 최댓값(LAN·드라이브 중 더 앞선 것) */
    private suspend fun pixelSignals(): Pair<Long, Long> {
        val all = listOfNotNull(statusStore.load("lan"), statusStore.load("drive"))
        return all.maxOfOrNull { it.status.trialDoneAt }.let { it ?: 0L } to all.maxOfOrNull { it.status.cleanupOpenedAt }.let { it ?: 0L }
    }

    suspend fun start(mediaIds: List<Long>): TrialStartOutcome {
        val cur = store.get()
        if (cur != null && cur.phase !in setOf(TrialPhase.IDLE, TrialPhase.FINISHED, TrialPhase.FAILED)) {
            return TrialStartOutcome.Rejected("이미 시험이 진행 중입니다. 끝나거나 중단한 뒤 다시 시작하세요.")
        }
        if (mediaIds.size != FreeTrialMachine.COUNT || mediaIds.toSet().size != FreeTrialMachine.COUNT) {
            return TrialStartOutcome.Rejected("사진을 정확히 ${FreeTrialMachine.COUNT}장 고르세요.")
        }
        val usage = try {
            drive.about().usageBytes
        } catch (e: DriveAuthException) {
            return TrialStartOutcome.AccountRequired(e.message ?: "")
        } catch (e: Exception) {
            return TrialStartOutcome.Rejected("드라이브 사용량을 읽지 못했습니다: ${e.message}")
        }

        val files = ArrayList<TrialFile>()
        for (id in mediaIds) {
            when (val r = scanner.registerSelected(id)) {
                is SelectResult.Registered -> files += TrialFile(id, r.item.size, r.item.name, r.sha)
                SelectResult.AlreadyKnown -> return TrialStartOutcome.Rejected("이미 장부에 있는(보냈거나 보내는 중인) 사진이 있습니다. 새로 찍은 지 얼마 안 된, 아직 안 보낸 사진을 고르세요.")
                SelectResult.Pending -> return TrialStartOutcome.Rejected("아직 저장 중인 사진이 있습니다. 잠시 뒤 다시 하세요.")
                SelectResult.NotFound -> return TrialStartOutcome.Rejected("사진을 읽지 못했습니다(삭제됐을 수 있습니다).")
                SelectResult.NoLocation -> return TrialStartOutcome.Rejected("사진 위치 정보 권한이 없어 원본을 읽을 수 없습니다. 권한을 허용하세요.")
                SelectResult.PartialAccess -> return TrialStartOutcome.Rejected("사진·영상 '전체 허용'이 필요합니다.")
            }
        }
        files.forEach { f -> f.sha?.let { ledger.markTrial(it) } }
        val (doneAt, cleanupAt) = pixelSignals()
        return when (val r = FreeTrialMachine.start(usage, files, doneAt, cleanupAt, clock.nowMs())) {
            is StartResult.Started -> { store.put(r.state); TrialStartOutcome.Started(r.state) }
            StartResult.AccountRequired -> TrialStartOutcome.AccountRequired("")
            StartResult.WrongCount -> TrialStartOutcome.Rejected("사진을 정확히 ${FreeTrialMachine.COUNT}장 고르세요.")
        }
    }

    /** 한 걸음: 장부·픽셀 신호·(필요할 때만) 드라이브 사용량을 읽어 상태를 전이시키고 저장한다. */
    suspend fun step(): TrialState? {
        val s = store.get() ?: return null
        if (s.phase in setOf(TrialPhase.IDLE, TrialPhase.FINISHED, TrialPhase.FAILED)) return s
        val now = clock.nowMs()
        val files = s.files.map { f -> if (f.sha != null) f else f.copy(sha = ledger.findByMediaId(f.mediaId)?.sha) }
        val bound = s.copy(files = files)
        val states = HashMap<String, String>()
        for (f in files) f.sha?.let { sha -> ledger.get(sha)?.let { states[sha] = it.state } }
        val usage = if (FreeTrialMachine.wantsUsage(bound, now)) runCatching { drive.about().usageBytes }.getOrNull() else null
        val (doneAt, cleanupAt) = pixelSignals()
        val next = FreeTrialMachine.advance(bound, TrialSnapshot(now, states, doneAt, cleanupAt, usage))
        if (next != s) store.put(next)
        return next
    }
}
