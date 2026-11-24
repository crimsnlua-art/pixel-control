package com.pixelrelay.receiver

import com.pixelrelay.core.Clock
import com.pixelrelay.core.Proto
import com.pixelrelay.core.Reason
import com.pixelrelay.core.RecvState
import com.pixelrelay.core.ReceiverLedger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

sealed class BeginResult {
    data class Ok(val resume: Boolean, val offset: Long) : BeginResult()
    object Already : BeginResult()
    object Busy : BeginResult()
    data class NoSpace(val freeBytes: Long, val permanent: Boolean) : BeginResult()
    object Hot : BeginResult()
    /** sha 형식 오류 또는 크기 < 0 */
    object Invalid : BeginResult()

    /** 장부에 이미 있는 항목(진행 중)과 size·mime 가 다름: 부분 파일을 건드리지 않고 거절 */
    object Mismatch : BeginResult()
}

sealed class PutResult {
    data class Ok(val offset: Long) : PutResult()
    data class Conflict(val offset: Long) : PutResult()
    object NotBegun : PutResult()
    object Hot : PutResult()
}

sealed class CommitOutcome {
    object Published : CommitOutcome()
    object Mismatch : CommitOutcome()
    data class Incomplete(val offset: Long) : CommitOutcome()
    object NotBegun : CommitOutcome()
}

/**
 * 받기 핵심: LAN 서버와 드라이브 수신 루프가 함께 쓴다 (begin/put/commit 과정 동일, 설계서 7절).
 * - 같은 sha 는 소유자 임대 + sha 별 Mutex 로 직렬화(§15): offset 검사~쓰기~해시~rename~장부 전이 전체가 한 번에 하나.
 * - 공간 예약(§15): 진행 중 수신의 남은 바이트 합계를 begin 판정에 포함한다.
 */
class ReceiverCore(
    private val ledger: ReceiverLedger,
    private val sink: MediaSink,
    private val clock: Clock,
    /** 고온 중지 중이면 true */
    private val stopped: () -> Boolean = { false },
    private val leaseMs: Long = 120_000L,
) {
    companion object {
        private val SHA = Regex("^[0-9a-f]{64}$")
        fun validSha(s: String) = SHA.matches(s)
    }

    private class Lock(val owner: String, @Volatile var at: Long)

    private val locks = ConcurrentHashMap<String, Lock>()
    private val shaMutex = ConcurrentHashMap<String, Mutex>()
    private val reserved = ConcurrentHashMap<String, Long>()
    private val beginMutex = Mutex()

    private fun mutexFor(sha: String): Mutex = shaMutex.getOrPut(sha) { Mutex() }

    private fun lockedByOther(sha: String, owner: String): Boolean {
        val l = locks[sha] ?: return false
        if (clock.nowMs() - l.at > leaseMs) { locks.remove(sha, l); reserved.remove(sha); return false }
        return l.owner != owner
    }

    private fun touch(sha: String, owner: String) {
        val l = locks[sha]
        if (l != null && l.owner == owner) l.at = clock.nowMs() else locks[sha] = Lock(owner, clock.nowMs())
    }

    fun release(sha: String, owner: String) {
        val l = locks[sha] ?: return
        if (l.owner == owner) { locks.remove(sha, l); reserved.remove(sha) }
    }

    private fun holds(sha: String, owner: String): Boolean {
        val l = locks[sha] ?: return false
        return l.owner == owner && clock.nowMs() - l.at <= leaseMs
    }

    /** 다른 sha 들의 예약 합계(임대가 살아 있는 것만) */
    private fun reservedByOthers(sha: String): Long {
        var sum = 0L
        for ((k, v) in reserved) {
            if (k == sha) continue
            val l = locks[k]
            if (l == null || clock.nowMs() - l.at > leaseMs) { reserved.remove(k); continue }
            sum += v
        }
        return sum
    }

    suspend fun begin(sha: String, name: String, size: Long, takenAt: Long, mime: String, owner: String): BeginResult {
        if (!validSha(sha) || size < 0) return BeginResult.Invalid
        if (stopped()) return BeginResult.Hot
        return mutexFor(sha).withLock { beginMutex.withLock { beginLocked(sha, name, size, takenAt, mime, owner) } }
    }

    private suspend fun beginLocked(sha: String, name: String, size: Long, takenAt: Long, mime: String, owner: String): BeginResult {
        var e = ledger.get(sha)
        if (e != null) {
            when (e.stateEnum) {
                RecvState.PUBLISHED, RecvState.PHOTOS_CONFIRMED, RecvState.CLEANED, RecvState.VANISHED_UNKNOWN ->
                    return BeginResult.Already
                RecvState.SKIPPED -> return BeginResult.NoSpace(sink.freeBytes(), true)
                else -> {}
            }
        }
        // 승인·예약·put 상한은 모두 장부의 size 하나가 기준: 다르게 들어오면 부분 파일을 건드리기 전에 거절
        if (e != null && (e.size != size || e.mime != mime)) return BeginResult.Mismatch
        if (lockedByOther(sha, owner)) return BeginResult.Busy

        val partial = sink.partialLength(sha).let { if (it > size) { sink.discard(sha); 0L } else it }
        val free = sink.freeBytes()
        val need = size - partial
        if (free - reservedByOthers(sha) < need + Proto.SPACE_MARGIN) {
            val permanent = size > sink.totalBytes() - Proto.SPACE_MARGIN
            if (permanent) {
                if (e == null) e = ledger.create(sha, name, size, takenAt, mime, owner)
                if (e.stateEnum == RecvState.FAILED) ledger.transition(sha, RecvState.RECEIVING)
                if (e.stateEnum == RecvState.RESEND_REQUESTED) ledger.transition(sha, RecvState.RECEIVING)
                ledger.transition(sha, RecvState.SKIPPED, Reason.TOO_LARGE)
            }
            return BeginResult.NoSpace(free, permanent)
        }

        if (e == null) ledger.create(sha, name, size, takenAt, mime, owner)
        else if (e.stateEnum == RecvState.FAILED) ledger.transition(sha, RecvState.RECEIVING)
        else if (e.stateEnum == RecvState.RESEND_REQUESTED) {
            // 주력 폰이 다시 보내기를 요청한 항목: 이력·사본 표는 보존하고 새로 받는다
            ledger.transition(sha, RecvState.RECEIVING) {
                it.copy(name = name, takenAt = takenAt, via = owner, missingSince = null,
                    candidateAt = null, ownDeleted = false, failCount = 0)
            }
        }
        val offset = sink.begin(sha)
        reserved[sha] = size - offset
        touch(sha, owner)
        return BeginResult.Ok(resume = offset > 0, offset = offset)
    }

    /** [offset] 부터 [input] 을 최대 [maxBytes] 만큼 부분 파일에 이어 쓴다. */
    suspend fun put(sha: String, owner: String, offset: Long, input: InputStream, maxBytes: Long): PutResult {
        if (!validSha(sha)) return PutResult.NotBegun
        if (stopped()) return PutResult.Hot
        return mutexFor(sha).withLock {
            val e = ledger.get(sha)
            if (e == null || e.stateEnum != RecvState.RECEIVING || !holds(sha, owner)) return@withLock PutResult.NotBegun
            val cur = sink.partialLength(sha)
            if (offset != cur) return@withLock PutResult.Conflict(cur)
            var remaining = minOf(maxBytes, e.size - cur)
            val buf = ByteArray(64 * 1024)
            sink.openAppend(sha).use { out ->
                while (remaining > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    remaining -= n
                    touch(sha, owner)
                }
            }
            touch(sha, owner)
            val now = sink.partialLength(sha)
            reserved[sha] = e.size - now
            PutResult.Ok(now)
        }
    }

    suspend fun commit(sha: String, owner: String): CommitOutcome {
        if (!validSha(sha)) return CommitOutcome.NotBegun
        return mutexFor(sha).withLock { commitLocked(sha, owner) }
    }

    private suspend fun commitLocked(sha: String, owner: String): CommitOutcome {
        val e = ledger.get(sha) ?: return CommitOutcome.NotBegun
        // 이미 공개된 뒤 다시 commit 이 오면(응답 유실) 그대로 성공으로 본다.
        if (e.stateEnum in setOf(RecvState.PUBLISHED, RecvState.PHOTOS_CONFIRMED, RecvState.CLEANED, RecvState.VANISHED_UNKNOWN)) {
            return CommitOutcome.Published
        }
        if (e.stateEnum != RecvState.RECEIVING || !holds(sha, owner)) return CommitOutcome.NotBegun
        val have = sink.partialLength(sha)
        if (have != e.size) return CommitOutcome.Incomplete(have)
        return when (val r = sink.commit(sha, e.name, e.takenAt, e.mime)) {
            is CommitResult.Ok -> {
                ledger.transition(sha, RecvState.PUBLISHED) { it.copy(path = r.path, mediaId = r.mediaId) }
                release(sha, owner)
                CommitOutcome.Published
            }
            CommitResult.HashMismatch, CommitResult.Missing -> {
                ledger.patch(sha) { it.copy(failCount = it.failCount + 1) }
                release(sha, owner)
                CommitOutcome.Mismatch
            }
        }
    }
}
