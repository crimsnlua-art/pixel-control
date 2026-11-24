package com.pixelrelay.sender

import com.pixelrelay.core.Clock
import com.pixelrelay.core.FolderPolicy
import com.pixelrelay.core.Hashing
import com.pixelrelay.core.Reason
import com.pixelrelay.core.SendState
import com.pixelrelay.core.SenderLedger
import com.pixelrelay.core.SenderSettings
import com.pixelrelay.core.db.SendEntry
import com.pixelrelay.core.db.SeenMedia
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

    /** 이 시각(초) 이후 추가된 사진·영상 목록 */
    fun list(addedSinceSec: Long): List<MediaItem>

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
)

/**
 * 새 사진·영상 발견 -> 안정 조건(IS_PENDING=0, 크기·수정 시각이 60초 동안 그대로) -> 해시 -> 장부 등록.
 * 이미 장부에 있는 sha 는 다시 등록하지 않는다(설계서 1절 5번).
 */
class MediaScanner(
    private val source: MediaSource,
    private val ledger: SenderLedger,
    private val clock: Clock,
    private val settings: () -> SenderSettings,
) {
    suspend fun scan(): ScanReport {
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

        for (it in source.list(s.roleSetAtSec)) {
            if (!FolderPolicy.inWatch(it.relPath, s.watchFolders)) continue
            val placeholder = "m${it.id}"
            if (FolderPolicy.excluded(it.relPath, s.excludeFolders)) {
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

            // 안정 조건: 크기·수정 시각이 60초 동안 그대로
            if (seen == null || seen.size != it.size || seen.dateModified != it.dateModifiedSec) {
                ledger.putSeen(SeenMedia(it.id, it.size, it.dateModifiedSec, now, null))
                recheck = true
                continue
            }
            if (now - seen.stableSince < SenderSettings.STABLE_MS) { recheck = true; continue }

            if (!locOk) {
                // 위치 권한 없이 읽으면 GPS 가 지워진 사본이 되므로 해시하지 않는다.
                if (ledger.get(placeholder) == null) {
                    ledger.register(entry(placeholder, it, SendState.WAITING, Reason.PERMISSION, now))
                }
                continue
            }

            val sha = try {
                source.openOriginal(it.uri)?.use { st -> Hashing.sha256Hex(st) }
            } catch (e: Exception) { null } ?: continue

            if (ledger.get(placeholder)?.stateEnum == SendState.WAITING) ledger.remove(placeholder)
            if (ledger.register(entry(sha, it, SendState.WAITING, Reason.NETWORK, now))) registered++
            else if (ledger.reviveChanged(sha, it.size, it.dateModifiedSec)) registered++   // 원본변경으로 건너뛴 같은 내용
            ledger.putSeen(SeenMedia(it.id, it.size, it.dateModifiedSec, seen.stableSince, sha))
        }
        return ScanReport(registered, skipped, permissionMissing = !locOk, partialAccess = false, recheckSoon = recheck)
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
