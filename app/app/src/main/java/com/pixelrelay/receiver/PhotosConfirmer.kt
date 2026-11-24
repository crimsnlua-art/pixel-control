package com.pixelrelay.receiver

import com.pixelrelay.core.Clock
import com.pixelrelay.core.ReceiverLedger
import com.pixelrelay.core.RecvState

data class ConfirmReport(val confirmed: Int, val vanishedUnknown: Int, val missingNow: Int)

/**
 * 포토 백업 확인, 신호 A (설계서 8절).
 * PUBLISHED 항목이 MediaStore 에도 없고 실제 파일 경로에도 없고, 1분 뒤 재확인해도 없으면 "사라짐".
 * 사라진 시각이 "포토 공간 확보 열기" 버튼을 누른 뒤 1시간 안이면 PHOTOS_CONFIRMED(+드라이브 사본 삭제),
 * 그 밖은 VANISHED_UNKNOWN(드라이브 사본 유지).
 */
class PhotosConfirmer(
    private val ledger: ReceiverLedger,
    private val sink: MediaSink,
    private val clock: Clock,
    private val cleaner: Cleaner,
) {
    companion object {
        const val KEY_BUTTON_AT = "cleanupOpenedAt"
        const val RECHECK_MS = 60_000L
        const val BUTTON_WINDOW_MS = 60 * 60_000L
    }

    /**
     * "포토 공간 확보 열기" 버튼을 누른 시각 기록 + 후보 확정(§15):
     * 누르는 순간 PUBLISHED 이면서 파일·MediaStore 둘 다 존재가 확인된 항목만 후보로 기록한다.
     * 버튼 순간 이미 없거나 확인 실패한 항목은 후보가 아니므로 나중에 사라져도 VANISHED_UNKNOWN 이다.
     */
    suspend fun recordButton() {
        val now = clock.nowMs()
        ledger.putLong(KEY_BUTTON_AT, now)
        for (e in ledger.all()) {
            if (e.stateEnum != RecvState.PUBLISHED || e.ownDeleted) continue
            val path = e.path
            val present = path != null && sink.exists(path) &&
                (sink.mediaStoreId(path) != null || (e.mediaId != null && sink.mediaStoreHas(e.mediaId)))
            ledger.patch(e.sha) { it.copy(candidateAt = if (present) now else null) }
        }
    }

    /** 우리 앱이 직접 지운 파일은 확인 대상에서 뺀다. */
    suspend fun markOwnDeleted(sha: String) = ledger.patch(sha) { it.copy(ownDeleted = true) }

    suspend fun check(): ConfirmReport {
        val now = clock.nowMs()
        val button = ledger.getLong(KEY_BUTTON_AT)
        var confirmed = 0
        var unknown = 0
        var missing = 0
        for (e in ledger.all()) {
            if (e.stateEnum != RecvState.PUBLISHED || e.ownDeleted) continue
            val path = e.path ?: continue

            if (sink.exists(path)) {
                // 파일이 있으면 사라짐이 아니다. MediaStore id 만 바뀐 경우 새 id 로 갱신.
                val id = sink.mediaStoreId(path)
                if (e.missingSince != null || (id != null && id != e.mediaId)) {
                    ledger.patch(e.sha) { it.copy(missingSince = null, mediaId = id ?: it.mediaId) }
                }
                continue
            }
            if (e.mediaId != null && sink.mediaStoreHas(e.mediaId)) {
                if (e.missingSince != null) ledger.patch(e.sha) { it.copy(missingSince = null) }
                continue
            }

            // 둘 다 없음
            val first = e.missingSince
            if (first == null) {
                ledger.patch(e.sha) { it.copy(missingSince = now) }
                missing++
                continue
            }
            if (now - first < RECHECK_MS) { missing++; continue }

            // 1분 뒤 재확인에도 없음 -> 사라진 시각 = 처음 없음을 본 시각
            val cand = e.candidateAt
            val byButton = button != null && cand != null && first >= cand && first - cand <= BUTTON_WINDOW_MS
            if (byButton) {
                ledger.transition(e.sha, RecvState.PHOTOS_CONFIRMED)
                confirmed++
                cleaner.clean(e.sha)
            } else {
                ledger.transition(e.sha, RecvState.VANISHED_UNKNOWN)
                unknown++
            }
        }
        return ConfirmReport(confirmed, unknown, missing)
    }

    /** 사용자가 원인 불명 항목을 "포토 확인됨으로 확정"한다. 그 뒤 드라이브 사본을 정리한다. */
    suspend fun userConfirm(sha: String): Boolean {
        if (!userConfirmTransition(sha)) return false
        cleaner.clean(sha)
        return true
    }

    /** 확정 뒤 드라이브 사본 정리(트랜잭션 밖) */
    suspend fun cleanAfterConfirm(sha: String) = cleaner.clean(sha)

    /** 상태 전이만(드라이브 삭제 없음). 명령 처리에서 commandId 기록과 같은 트랜잭션에 넣고, 삭제는 커밋 뒤에 한다. */
    suspend fun userConfirmTransition(sha: String): Boolean {
        val e = ledger.get(sha) ?: return false
        if (e.stateEnum != RecvState.VANISHED_UNKNOWN) return false
        ledger.transition(sha, RecvState.PHOTOS_CONFIRMED)
        return true
    }
}
