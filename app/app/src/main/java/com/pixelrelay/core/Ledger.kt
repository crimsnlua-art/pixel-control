package com.pixelrelay.core

import androidx.room.withTransaction
import com.pixelrelay.core.db.AppDb
import com.pixelrelay.core.db.DriveCopy
import com.pixelrelay.core.db.Meta
import com.pixelrelay.core.db.RecvChange
import com.pixelrelay.core.db.RecvEntry
import com.pixelrelay.core.db.SendEntry
import com.pixelrelay.core.db.SeenMedia

/** 재시도 간격: 1분, 5분, 30분, 2시간, 이후 6시간. 최대 20회 (설계서 3절). */
object Retry {
    const val MAX_ATTEMPTS = 20
    private val steps = longArrayOf(60_000L, 5 * 60_000L, 30 * 60_000L, 2 * 3_600_000L)
    private const val LATER = 6 * 3_600_000L
    fun delayMs(attempt: Int): Long = if (attempt in 1..steps.size) steps[attempt - 1] else LATER
}

/** 보내기 장부. 허용된 전이만 통과시킨다. */
class SenderLedger(private val db: AppDb, private val clock: Clock) {
    private val dao = db.send()

    suspend fun get(sha: String): SendEntry? = dao.get(sha)

    /** 새 파일 등록. 이미 장부에 있는 sha 면 false (한 번 오른 sha 는 다시 등록되지 않는다). */
    suspend fun register(e: SendEntry): Boolean = dao.insertIgnore(e) != -1L

    suspend fun transition(
        sha: String,
        to: SendState,
        reason: String? = null,
        mutate: (SendEntry) -> SendEntry = { it },
    ): SendEntry = db.withTransaction {
        val cur = dao.get(sha) ?: throw IllegalTransition("장부에 없는 항목: $sha")
        val from = cur.stateEnum
        if (!Transitions.allowed(from, to)) throw IllegalTransition("허용되지 않은 전이: $from -> $to")
        var next = mutate(cur).copy(state = to.name, reason = reason, stateAt = clock.nowMs())
        if (to == SendState.RECEIVED || to == SendState.PHOTOS_CONFIRMED || to == SendState.SKIPPED || to == SendState.FAILED) {
            next = next.copy(resendCommandId = null)   // 다시 보내기 흐름 끝
        }
        dao.update(next)
        next
    }

    /** 전이 시도. 허용되지 않으면 조용히 무시(이미 더 앞선 상태). */
    suspend fun tryTransition(sha: String, to: SendState, reason: String? = null, mutate: (SendEntry) -> SendEntry = { it }): Boolean =
        try { transition(sha, to, reason, mutate); true } catch (e: IllegalTransition) { false }

    /** 진행 정보만 갱신(상태 불변). */
    suspend fun patch(sha: String, mutate: (SendEntry) -> SendEntry) = db.withTransaction {
        val cur = dao.get(sha) ?: return@withTransaction
        // 읽기와 쓰기를 한 트랜잭션으로. 상태는 현재 행 것을 유지해 완료 상태를 되돌리지 않는다.
        dao.update(mutate(cur).copy(state = cur.state, stateAt = cur.stateAt))
    }

    suspend fun setLanOffset(sha: String, off: Long) = dao.setLanOffset(sha, off)
    suspend fun setDriveOffset(sha: String, off: Long) = dao.setDriveOffset(sha, off)

    /** 원본변경으로 건너뛴 항목의 내용(sha)이 그대로 다시 안정되면 대기로 되살린다. */
    suspend fun reviveChanged(sha: String, size: Long, mod: Long): Boolean = db.withTransaction {
        val cur = dao.get(sha) ?: return@withTransaction false
        if (cur.stateEnum != SendState.SKIPPED || cur.reason != Reason.ORIGINAL_CHANGED) return@withTransaction false
        dao.update(cur.copy(state = SendState.WAITING.name, reason = Reason.NETWORK, size = size, dateModifiedSec = mod, attempts = 0, nextAttemptAt = 0, stateAt = clock.nowMs()))
        true
    }

    /** 대기 사유 갱신 (WAITING -> WAITING). */
    suspend fun wait(sha: String, reason: String, retryAfterMs: Long = 0) = db.withTransaction {
        val cur = dao.get(sha) ?: return@withTransaction
        if (cur.stateEnum !in setOf(SendState.WAITING, SendState.SENDING_LAN, SendState.UPLOADING_DRIVE)) return@withTransaction
        if (cur.stateEnum == SendState.UPLOADING_DRIVE) {
            // 드라이브 업로드 중 대기: 상태 유지(세션 URI 보존), 사유만 기록
            dao.update(cur.copy(reason = reason, nextAttemptAt = clock.nowMs() + retryAfterMs))
        } else {
            transition(sha, SendState.WAITING, reason) { it.copy(nextAttemptAt = clock.nowMs() + retryAfterMs) }
        }
        Unit
    }

    /** 실패 한 번: 시도 횟수 +1, 최대 20회면 FAILED. */
    suspend fun recordFailure(sha: String, why: String): SendEntry? = db.withTransaction {
        val cur = dao.get(sha) ?: return@withTransaction null
        if (cur.stateEnum !in setOf(SendState.WAITING, SendState.SENDING_LAN, SendState.UPLOADING_DRIVE)) return@withTransaction cur
        val n = cur.attempts + 1
        if (n >= Retry.MAX_ATTEMPTS) {
            transition(sha, SendState.FAILED, why) { it.copy(attempts = n) }
        } else if (cur.stateEnum == SendState.UPLOADING_DRIVE) {
            val next = cur.copy(attempts = n, reason = why, nextAttemptAt = clock.nowMs() + Retry.delayMs(n))
            dao.update(next); next
        } else {
            transition(sha, SendState.WAITING, why) { it.copy(attempts = n, nextAttemptAt = clock.nowMs() + Retry.delayMs(n)) }
        }
    }

    /**
     * 예외 1-a: 원인 불명 항목의 "다시 보내기" 요청(사용자 누름). 상태를 RESEND_PENDING + commandId 로 영속 기록한다.
     * 이 시점에는 보내지 않는다: 픽셀이 허용(RESEND_REQUESTED)했음이 확인된 뒤에만 resendConfirmed 로 WAITING 이 된다.
     */
    suspend fun requestResend(sha: String, commandId: String = java.util.UUID.randomUUID().toString()): SendEntry? = db.withTransaction {
        val cur = dao.get(sha) ?: return@withTransaction null
        if (cur.stateEnum != SendState.VANISHED_UNKNOWN) return@withTransaction null
        val next = cur.copy(state = SendState.RESEND_PENDING.name, reason = null, resendCommandId = commandId, nextAttemptAt = 0, stateAt = clock.nowMs())
        dao.update(next)
        next
    }

    /** 예외 1-b: 픽셀의 수신 측 상태가 RESEND_REQUESTED 로 확인됐을 때 대기로 되돌려 다시 보낸다. 시도 정보 초기화. */
    suspend fun resendConfirmed(sha: String): Boolean = db.withTransaction {
        val cur = dao.get(sha) ?: return@withTransaction false
        if (cur.stateEnum != SendState.RESEND_PENDING) return@withTransaction false
        dao.update(
            cur.copy(
                state = SendState.WAITING.name, reason = Reason.NETWORK, attempts = 0, nextAttemptAt = 0,
                route = null, lanOffset = 0, driveSessionUri = null, driveOffset = 0, driveFileId = null,
                stateAt = clock.nowMs(),
            ),
        )
        true
    }

    /** 시험 파일 표시(장부에 시험 표시) */
    suspend fun markTrial(sha: String) = patch(sha) { it.copy(trial = true) }

    /** 해시 키로 올라 있는 항목 중 이 미디어 id 의 것(자리표시 m... 제외) */
    suspend fun findByMediaId(mediaId: Long): SendEntry? = dao.all().firstOrNull { it.mediaId == mediaId && !it.sha.startsWith("m") }

    suspend fun pendingResends(): List<SendEntry> = dao.all().filter { it.stateEnum == SendState.RESEND_PENDING }

    /** 예외 2: 실패 항목의 사용자 "다시 시도". */
    suspend fun retryFailed(sha: String): Boolean = db.withTransaction {
        val cur = dao.get(sha) ?: return@withTransaction false
        if (cur.stateEnum != SendState.FAILED) return@withTransaction false
        dao.update(cur.copy(state = SendState.WAITING.name, reason = Reason.NETWORK, attempts = 0, nextAttemptAt = 0, stateAt = clock.nowMs()))
        true
    }

    /** 자동으로 보낼 대상: 아직 도착하지 않은 것만. */
    suspend fun sendable(): List<SendEntry> = dao.sendable(clock.nowMs())

    /** 해시 전 자리표시(권한필요) 항목 제거. */
    suspend fun remove(sha: String) = dao.delete(sha)

    suspend fun seen(mediaId: Long): SeenMedia? = dao.seen(mediaId)
    suspend fun putSeen(s: SeenMedia) = dao.upsertSeen(s)
    suspend fun forgetSeen(mediaId: Long) = dao.deleteSeen(mediaId)
}

data class RecvCounts(val published: Int, val confirmed: Int, val vanished: Int, val failed: Int, val receiving: Int, val skipped: Int)

/** 받기 장부. 상태가 바뀔 때마다 변화 기록(seq)을 같은 트랜잭션에서 남긴다. */
class ReceiverLedger(private val db: AppDb, private val clock: Clock) {
    private val dao = db.recv()

    suspend fun get(sha: String): RecvEntry? = dao.get(sha)
    suspend fun all(): List<RecvEntry> = dao.all()

    /** 여러 장부 갱신을 한 DB 트랜잭션으로 묶는다(안에서 예외가 나면 전부 되돌려진다). */
    suspend fun <T> inTransaction(block: suspend () -> T): T = db.withTransaction { block() }

    suspend fun create(sha: String, name: String, size: Long, takenAt: Long, mime: String, via: String): RecvEntry =
        db.withTransaction {
            val now = clock.nowMs()
            val e = RecvEntry(sha = sha, name = name, size = size, takenAt = takenAt, mime = mime, via = via,
                state = RecvState.RECEIVING.name, tReceiving = now)
            dao.upsert(e)
            val seq = dao.insertChange(RecvChange(sha = sha, state = e.state, at = now))
            dao.upsert(e.copy(seq = seq))
            e.copy(seq = seq)
        }

    suspend fun transition(sha: String, to: RecvState, reason: String? = null, mutate: (RecvEntry) -> RecvEntry = { it }): RecvEntry =
        db.withTransaction {
            val cur = dao.get(sha) ?: throw IllegalTransition("받기 장부에 없는 항목: $sha")
            val from = cur.stateEnum
            if (!Transitions.allowed(from, to)) throw IllegalTransition("허용되지 않은 전이: $from -> $to")
            val now = clock.nowMs()
            var next = mutate(cur).copy(state = to.name, reason = reason)
            next = when (to) {
                RecvState.PUBLISHED -> next.copy(tPublished = now)
                RecvState.PHOTOS_CONFIRMED -> next.copy(tConfirmed = now)
                RecvState.CLEANED -> next.copy(tCleaned = now)
                RecvState.VANISHED_UNKNOWN -> next.copy(tVanished = now)
                RecvState.RECEIVING -> next.copy(tReceiving = now)
                else -> next
            }
            val seq = dao.insertChange(RecvChange(sha = sha, state = to.name, at = now))
            next = next.copy(seq = seq)
            dao.upsert(next)
            next
        }

    /** 상태는 그대로, 진행 정보만 갱신. */
    suspend fun patch(sha: String, mutate: (RecvEntry) -> RecvEntry) = db.withTransaction {
        val cur = dao.get(sha) ?: return@withTransaction
        // 상태·사유·seq 는 현재 행 것을 유지(완료 상태를 되돌리지 않는다)
        dao.upsert(mutate(cur).copy(state = cur.state, reason = cur.reason, seq = cur.seq))
    }

    suspend fun delete(sha: String) = dao.delete(sha)

    suspend fun attachCopy(sha: String, driveFileId: String, size: Long) = dao.insertCopy(DriveCopy(sha, driveFileId, size))
    suspend fun copies(sha: String): List<DriveCopy> = dao.copies(sha)
    suspend fun removeCopy(sha: String, driveFileId: String) = dao.deleteCopy(sha, driveFileId)

    suspend fun changesSince(since: Long, limit: Int) = dao.changesSince(since, limit)
    suspend fun maxSeq(): Long = dao.maxSeq()

    suspend fun counts(): RecvCounts {
        val m = dao.counts().associate { it.state to it.n }
        return RecvCounts(
            published = m[RecvState.PUBLISHED.name] ?: 0,
            confirmed = (m[RecvState.PHOTOS_CONFIRMED.name] ?: 0) + (m[RecvState.CLEANED.name] ?: 0),
            vanished = m[RecvState.VANISHED_UNKNOWN.name] ?: 0,
            failed = m[RecvState.FAILED.name] ?: 0,
            receiving = m[RecvState.RECEIVING.name] ?: 0,
            skipped = m[RecvState.SKIPPED.name] ?: 0,
        )
    }

    suspend fun lastPhotosActivity(): Long = dao.lastPhotosActivity() ?: 0L
    suspend fun oldestPublished(): Long? = dao.oldestPublished()

    suspend fun getLong(k: String): Long? = dao.meta(k)?.v?.toLongOrNull()
    suspend fun putLong(k: String, v: Long) = dao.putMeta(Meta(k, v.toString()))
    suspend fun getString(k: String): String? = dao.meta(k)?.v
    suspend fun putString(k: String, v: String) = dao.putMeta(Meta(k, v))
}
