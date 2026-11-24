package com.pixelrelay.receiver

import com.pixelrelay.core.Hashing
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream

sealed class CommitResult {
    data class Ok(val path: String, val mediaId: Long?) : CommitResult()
    object HashMismatch : CommitResult()
    object Missing : CommitResult()
}

/**
 * 픽셀 저장 경계(설계서 2·6절): begin / append / commit / exists.
 * 실제 구현 = [FileMediaSink] (legacy 방식), 테스트에서는 같은 클래스를 임시 폴더에 쓰거나 가짜를 쓴다.
 */
interface MediaSink {
    fun freeBytes(): Long
    fun totalBytes(): Long

    /** 받다 만 부분 파일 길이. 없으면 0. */
    fun partialLength(sha: String): Long

    /** 부분 파일을 준비하고(없으면 만들고) 현재 길이를 돌려준다. */
    fun begin(sha: String): Long

    /** 부분 파일 끝에 이어 쓰는 출력 스트림. */
    fun openAppend(sha: String): OutputStream

    fun discard(sha: String)

    /** 전체 SHA-256 을 확인하고 공개한다. 불일치면 부분 파일을 지우고 HashMismatch. */
    fun commit(sha: String, name: String, takenAt: Long, mime: String): CommitResult

    /** 실제 파일 경로에 파일이 있는가 */
    fun exists(path: String): Boolean

    /** 이 경로 파일의 MediaStore id. 등록 안 됐으면 null. */
    fun mediaStoreId(path: String): Long?

    /** 이 id 가 MediaStore 에 있는가 */
    fun mediaStoreHas(id: Long): Boolean
}

/** MediaStore 연동 경계. 안드로이드 실제 구현은 MediaScannerConnection / ContentResolver 를 쓴다. */
interface MediaIndex {
    /** 파일을 MediaStore 에 알리고(scanFile) id 를 돌려준다. 실패하면 null. */
    fun scan(path: String, mime: String): Long?
    fun idForPath(path: String): Long?
    fun hasId(id: Long): Boolean
}

object NoIndex : MediaIndex {
    override fun scan(path: String, mime: String): Long? = null
    override fun idForPath(path: String): Long? = null
    override fun hasId(id: Long): Boolean = false
}

/**
 * 파일 시스템 위의 저장(설계서 6절): `.partial/{sha}.part` 에 직접 append, commit 에서 해시 확인 후
 * 같은 볼륨 안 rename 으로 `{root}/{원래 이름}` 공개 -> setLastModified(takenAt) -> scanFile.
 */
class FileMediaSink(
    private val root: File,
    private val free: () -> Long = { root.usableSpace },
    private val total: () -> Long = { root.totalSpace },
    private val index: MediaIndex = NoIndex,
) : MediaSink {
    private val partialDir = File(root, ".partial")

    companion object {
        private val SHA = Regex("^[0-9a-f]{64}$")

        /** 공개(rename) 단계는 전역 잠금으로 직렬화한다. */
        private val PUBLISH_LOCK = Any()
    }

    /** 입력 검증: sha 는 64자리 소문자 hex 만, 경로는 .partial 안으로 정규화되는지 검사. */
    private fun partial(sha: String): File {
        require(SHA.matches(sha)) { "sha 형식 오류" }
        val f = File(partialDir, "$sha.part")
        check(f.canonicalFile.parentFile == partialDir.canonicalFile) { "저장 경로가 .partial 밖" }
        return f
    }

    private fun ensureDirs() {
        partialDir.mkdirs()
        val nomedia = File(partialDir, ".nomedia")
        if (!nomedia.exists()) runCatching { nomedia.createNewFile() }
    }

    override fun freeBytes(): Long = free()
    override fun totalBytes(): Long = total()

    override fun partialLength(sha: String): Long = partial(sha).takeIf { it.exists() }?.length() ?: 0L

    override fun begin(sha: String): Long {
        ensureDirs()
        val f = partial(sha)
        if (!f.exists()) f.createNewFile()
        return f.length()
    }

    override fun openAppend(sha: String): OutputStream {
        ensureDirs()
        return java.io.FileOutputStream(partial(sha), true).buffered(64 * 1024)
    }

    override fun discard(sha: String) {
        partial(sha).delete()
    }

    private fun safeName(name: String): String {
        val n = name.replace('\\', '/').substringAfterLast('/').trim().ifEmpty { "file" }
        return n.filter { it != '\u0000' }
    }

    /** 공개 이름 후보: 원래 이름 -> 이름_sha8 -> 이름_sha8_2 -> 이름_sha8_3 ... */
    private fun candidateName(name: String, sha: String, idx: Int): String {
        val n = safeName(name)
        if (idx == 0) return n
        val dot = n.lastIndexOf('.')
        val base = if (dot > 0) n.substring(0, dot) else n
        val ext = if (dot > 0) n.substring(dot) else ""
        return if (idx == 1) "${base}_${sha.take(8)}$ext" else "${base}_${sha.take(8)}_$idx$ext"
    }

    override fun commit(sha: String, name: String, takenAt: Long, mime: String): CommitResult {
        val p = partial(sha)
        if (!p.exists()) return CommitResult.Missing
        val actual = FileInputStream(p).use { Hashing.sha256Hex(it) }
        if (actual != sha) {
            p.delete()
            return CommitResult.HashMismatch
        }
        root.mkdirs()
        var published: File? = null
        synchronized(PUBLISH_LOCK) {
            for (idx in 0..10_000) {
                val t = File(root, candidateName(name, sha, idx))
                if (t.exists()) continue
                try {
                    // 옵션 없는 move: 대상이 있으면 덮어쓰지 않고 예외(기존 파일 교체 금지). 같은 볼륨 rename.
                    java.nio.file.Files.move(p.toPath(), t.toPath())
                    published = t
                    break
                } catch (e: java.nio.file.FileAlreadyExistsException) {
                    continue
                } catch (e: java.io.IOException) {
                    return CommitResult.Missing
                }
            }
        }
        val target = published ?: return CommitResult.Missing
        if (takenAt > 0) target.setLastModified(takenAt)
        val id = index.scan(target.absolutePath, mime)
        return CommitResult.Ok(target.absolutePath, id)
    }

    override fun exists(path: String): Boolean = File(path).exists()
    override fun mediaStoreId(path: String): Long? = index.idForPath(path)
    override fun mediaStoreHas(id: Long): Boolean = index.hasId(id)
}
