package com.pixelrelay.sender

import com.pixelrelay.core.Clock
import com.pixelrelay.core.FolderPolicy
import com.pixelrelay.core.FolderVerdict
import com.pixelrelay.core.Hashing
import com.pixelrelay.core.Reason
import com.pixelrelay.core.SendState
import com.pixelrelay.core.SenderLedger
import com.pixelrelay.core.SenderSettings
import com.pixelrelay.core.db.SendEntry
import com.pixelrelay.core.db.SeenMedia
import kotlinx.coroutines.ensureActive
import java.io.InputStream

/** 주력 폰 미디어 한 개(MediaStore 한 행). */
data class MediaItem(
    val id: Long,
    val uri: String,
    val name: String,
    val size: Long,
    val dateModifiedSec: Long,
    val dateAddedSec: Long,
    val takenAtMs: Long,
    val mime: String,
    val relPath: String,
    val isVideo: Boolean,
    val isPending: Boolean,
)

/** 주력 폰 읽기 경계. 실제 구현은 MediaStore(setRequireOriginal), 테스트는 가짜. */
interface MediaSource {
    /** READ_MEDIA_IMAGES/VIDEO 전체 허용 여부 (부분 허용이면 false) */
    fun hasFullMediaAccess(): Boolean

    /** ACCESS_MEDIA_LOCATION 허용 여부 */
    fun hasLocationAccess(): Boolean

    /** 이 시각(초) 이후 추가된 사진·영상 목록(0 이면 전부) */
    fun list(addedSinceSec: Long): List<MediaItem>

    /** 설정 화면용: 사진·영상이 있는 폴더(RELATIVE_PATH)별 파일 수 */
    fun folderCounts(): Map<String, Int> = list(0).groupingBy { it.relPath }.eachCount()

    /** 한 파일의 현재 상태(전송 직전 재검사용). 없으면 null. */
    fun stat(id: Long): MediaItem?

    /** 원본 바이트 스트림(GPS 가림 없음). 파일이 사라졌으면 null. */
    fun openOriginal(uri: String): InputStream?
}

sealed class SelectResult {
    data class Registered(val sha: String, val item: MediaItem) : SelectResult()

    /** 이미 장부에 있는 사진(이미 보냈거나 보내는 중): 시험에 쓸 수 없다 */
    object AlreadyKnown : SelectResult()
    object Pending : SelectResult()
    object NotFound : SelectResult()
    object NoLocation : SelectResult()
    object PartialAccess : SelectResult()
}

data class ScanReport(
    val registered: Int,
    val skipped: Int,
    val permissionMissing: Boolean,
    val partialAccess: Boolean,
    /** 아직 안정 조건을 못 채운 파일이 있어 곧 다시 봐야 함 */
    val recheckSoon: Boolean,
    /** 이번 회차 해시 예산(시간·개수)이 다해 다음 실행으로 넘긴 파일 수(0 이면 다 봤음) */
    val pendingHash: Int = 0,
    /** pendingHash 중 큰 파일이라(또는 도중에 예산이 끊겨) 짧은 실행에서 미룬 수. 한가한 실행에서 끝까지 해시한다. */
    val deferredLarge: Int = 0,
)

/**
 * 새 사진·영상 발견 -> 안정 조건(IS_PENDING=0, 크기·수정 시각이 60초 동안 그대로) -> 해시 -> 장부 등록.
 * 이미 장부에 있는 sha 는 다시 등록하지 않는다(설계서 1절 5번).
 */
class MediaScanner(
    private val source: MediaSource,
    private val ledger: SenderLedger,
    private val clock: Clock,
    /** 한 번의 실행에서 해시에 쓸 수 있는 시간. 다 쓰면 나머지는 다음 실행에서 이어 한다. */
    private val hashBudgetMs: Long = DEFAULT_HASH_BUDGET_MS,
    /** 한 번의 실행에서 해시할 최대 파일 수 */
    private val maxHashPerRun: Int = DEFAULT_MAX_HASH,
    private val settings: () -> SenderSettings,
) {
    companion object {
        const val DEFAULT_HASH_BUDGET_MS = 90_000L
        const val DEFAULT_MAX_HASH = 400

        /** 큰 파일이 예산 안에 끝날지 어림하는 읽기+해시 속도(초당 바이트). 보수적으로 잡는다. */
        const val HASH_RATE_BPS = 150L * 1024 * 1024
        const val HASH_CHUNK = 256 * 1024
    }

    /** 시간·코루틴 취소를 조각마다 확인하며 SHA-256. 시간이 넘으면 null(이어 하기 불가라 다음에 처음부터). */
    private suspend fun hashUntil(st: java.io.InputStream, deadlineMs: Long): String? {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(HASH_CHUNK)
        while (true) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (clock.nowMs() > deadlineMs) return null
            val n = st.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return com.pixelrelay.core.Hashing.hex(md.digest())
    }

    /**
     *  allowLong true 면 한가한 실행용: 큰 파일도 미루지 않고 deadlineMs(없으면 5분)까지 끝까지 해시한다.
     */
    suspend fun scan(allowLong: Boolean = false, deadlineMs: Long? = null): ScanReport {
        val s = settings()
        if (!source.hasFullMediaAccess()) {
            // 부분 허용이면 자동 감지를 멈춘다(설계서 4절)
            return ScanReport(0, 0, permissionMissing = true, partialAccess = true, recheckSoon = false)
        }
        val now = clock.nowMs()
        var registered = 0
        var skipped = 0
        var recheck = false
        val locOk = source.hasLocationAccess()
        val startedAt = now
        var hashed = 0
        var pendingHash = 0
        var deferredLarge = 0
        // 해시에 쓸 수 있는 마감: 짧은 실행은 hashBudgetMs, 한가한 실행(allowLong)은 호출자가 준 마감(없으면 5분)
        val hashEnd = if (allowLong) (deadlineMs ?: (now + 5 * 60_000L)) else now + hashBudgetMs

        // 최근 것 먼저 본다: 새로 찍은 사진이 밀린 옛 사진 뒤에서 해시를 기다리지 않게
        val items = source.list(s.scanFromSec)
            .sortedWith(compareByDescending<MediaItem> { it.dateAddedSec }.thenByDescending { it.id })
        for (it in items) {
            val verdict = FolderPolicy.verdict(it.relPath, s.watchFolders, s.excludeFolders)
            if (verdict == FolderVerdict.OUT) continue
            val placeholder = "m${it.id}"
            if (verdict == FolderVerdict.EXCLUDED) {
                if (ledger.get(placeholder) == null) {
                    if (ledger.register(entry(placeholder, it, SendState.SKIPPED, Reason.EXCLUDED, now))) skipped++
                }
                continue
            }
            if (it.isPending) { recheck = true; continue }

            val seen = ledger.seen(it.id)
            if (seen?.sha != null && seen.size == it.size && seen.dateModified == it.dateModifiedSec) continue

            val limit = if (it.isVideo) SenderSettings.MAX_VIDEO_BYTES else SenderSettings.MAX_PHOTO_BYTES
            if (it.size > limit) {
                if (ledger.register(entry(placeholder, it, SendState.SKIPPED, Reason.TOO_LARGE, now))) skipped++
                ledger.putSeen(SeenMedia(it.id, it.size, it.dateModifiedSec, now, placeholder))
                continue
            }

            // 안정 조건: 크기·수정 시각이 60초 동안 그대로.
            // 수정 시각이 이미 충분히 과거(OLD_FILE_SEC)인 파일은 기록 중일 수 없으므로 바로 안정으로 본다(IS_PENDING 은 위에서 이미 제외).
            val unchanged = seen != null && seen.size == it.size && seen.dateModified == it.dateModifiedSec
            val stableSince: Long
            if (unchanged) {
                stableSince = seen!!.stableSince
                if (now - stableSince < SenderSettings.STABLE_MS) { recheck = true; continue }
            } else if (now / 1000 - it.dateModifiedSec >= SenderSettings.OLD_FILE_SEC) {
                stableSince = now - SenderSettings.STABLE_MS
            } else {
                ledger.putSeen(SeenMedia(it.id, it.size, it.dateModifiedSec, now, null))
                recheck = true
                continue
            }

            if (!locOk) {
                // 위치 권한 없이 읽으면 GPS 가 지워진 사본이 되므로 해시하지 않는다.
                if (ledger.get(placeholder) == null) {
                    ledger.register(entry(placeholder, it, SendState.WAITING, Reason.PERMISSION, now))
                }
                continue
            }

            // 앞서 시간 초과로 끊긴 큰 파일: 짧은 스캔은 건너뛰고 나머지를 먼저 처리한다(한가한 실행에서 끝까지 해시)
            val deferredBefore = ledger.isHashDeferred(it.id, it.size, it.dateModifiedSec)
            if (deferredBefore && !allowLong) { pendingHash++; deferredLarge++; continue }

            // 해시 예산: 시간·개수를 다 쓰면 나머지는 다음 실행에서 이어 한다
            if (hashed >= maxHashPerRun || clock.nowMs() >= hashEnd) { pendingHash++; continue }
            // 남은 시간 안에 끝나지 못할 큰 파일은 짧은 실행에서 미룬다(이미 대기 중인 전송을 해시가 막지 않게). 한가한 실행에서 끝까지 한다.
            val remainMs = hashEnd - clock.nowMs()
            if (it.size * 1000L / HASH_RATE_BPS > remainMs) { pendingHash++; deferredLarge++; continue }

            val sha = try {
                source.openOriginal(it.uri)?.use { st -> hashUntil(st, hashEnd) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
            if (sha == null) {
                // 예산이 도중에 끊겼거나 읽기 실패: 다음(한가한) 실행에서 처음부터 다시
                if (clock.nowMs() > hashEnd) {
                    ledger.markHashDeferred(it.id, it.size, it.dateModifiedSec)   // 같은 파일부터 무한 반복하지 않게 기억
                    pendingHash++; deferredLarge++
                }
                continue
            }
            hashed++
            if (deferredBefore) ledger.clearHashDeferred(it.id)
            if (ledger.get(placeholder)?.stateEnum == SendState.WAITING) ledger.remove(placeholder)
            if (ledger.register(entry(sha, it, SendState.WAITING, Reason.NETWORK, now))) registered++
            else if (ledger.reviveChanged(sha, it.size, it.dateModifiedSec)) registered++   // 원본변경으로 건너뛴 같은 내용
            ledger.putSeen(SeenMedia(it.id, it.size, it.dateModifiedSec, stableSince, sha))
        }
        return ScanReport(registered, skipped, permissionMissing = !locOk, partialAccess = false, recheckSoon = recheck, pendingHash = pendingHash, deferredLarge = deferredLarge)
    }

    /** 사용자가 직접 고른 사진 한 장을 안정 조건 없이(이미 끝난 사진) 바로 장부에 올린다. 무료 확인 시험용. */
    suspend fun registerSelected(id: Long): SelectResult {
        if (!source.hasFullMediaAccess()) return SelectResult.PartialAccess
        if (!source.hasLocationAccess()) return SelectResult.NoLocation
        val m = source.stat(id) ?: return SelectResult.NotFound
        if (m.isPending) return SelectResult.Pending
        val sha = try {
            source.openOriginal(m.uri)?.use { Hashing.sha256Hex(it) }
        } catch (e: Exception) { null } ?: return SelectResult.NotFound
        if (ledger.get(sha) != null) return SelectResult.AlreadyKnown
        val now = clock.nowMs()
        ledger.register(entry(sha, m, SendState.WAITING, Reason.NETWORK, now))
        ledger.putSeen(SeenMedia(m.id, m.size, m.dateModifiedSec, now - SenderSettings.STABLE_MS, sha))
        return SelectResult.Registered(sha, m)
    }

    private fun entry(key: String, m: MediaItem, st: SendState, reason: String, now: Long) = SendEntry(
        sha = key, mediaId = m.id, uri = m.uri, name = m.name, size = m.size,
        takenAt = m.takenAtMs, mime = m.mime, relPath = m.relPath, isVideo = m.isVideo,
        state = st.name, reason = reason, createdAt = now, stateAt = now, dateModifiedSec = m.dateModifiedSec,
    )
}
