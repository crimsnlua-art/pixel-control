package com.pixelrelay

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pixelrelay.core.Clock
import com.pixelrelay.core.Hashing
import com.pixelrelay.core.NetSnapshot
import com.pixelrelay.core.NetworkState
import com.pixelrelay.core.db.AppDb
import com.pixelrelay.drive.ChunkResult
import com.pixelrelay.drive.DriveApi
import com.pixelrelay.drive.DriveFile
import com.pixelrelay.drive.DriveNames
import com.pixelrelay.drive.Quota
import com.pixelrelay.receiver.MediaIndex
import com.pixelrelay.sender.MediaItem
import com.pixelrelay.sender.MediaSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

class FakeClock(var now: Long = 1_700_000_000_000L) : Clock {
    override fun nowMs(): Long = now
    fun advance(ms: Long) { now += ms }
    fun advanceMin(m: Long) { now += m * 60_000L }
}

class FakeNetwork(var snap: NetSnapshot = NetSnapshot(true, true, false)) : NetworkState {
    override fun snapshot(): NetSnapshot = snap
}

/** 테스트에서 Robolectric 위에 Room DB 를 만든다. */
object TestDb {
    fun memory(): AppDb {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        return Room.inMemoryDatabaseBuilder(ctx, AppDb::class.java).allowMainThreadQueries().build()
    }

    fun file(name: String): AppDb {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        return Room.databaseBuilder(ctx, AppDb::class.java, name).allowMainThreadQueries().build()
    }
}

/** MediaStore 대역: 경로 <-> id 표. */
class FakeIndex : MediaIndex {
    private var next = 100L
    val byPath = HashMap<String, Long>()
    override fun scan(path: String, mime: String): Long { val id = next++; byPath[path] = id; return id }
    override fun idForPath(path: String): Long? = byPath[path]
    override fun hasId(id: Long): Boolean = byPath.containsValue(id)
    fun removePath(path: String) { byPath.remove(path) }
    fun changeId(path: String): Long { val id = next++; byPath[path] = id; return id }
}

class DeleteRecord(val fileId: String, val claimedSha: String?, val actualSha: String, val allowed: Boolean)

/** 드라이브 대역. 삭제 호출을 전부 기록하고, 그때 삭제가 허용되는 경우였는지 함께 적는다(완료 기준 11). */
class FakeDriveApi : DriveApi {
    class F(val id: String, val name: String, val props: Map<String, String>, val content: ByteArray, val reportedChecksum: String?) {
        val actualSha: String get() = Hashing.sha256Hex(content)
    }

    class Session(val name: String, val size: Long, val props: Map<String, String>, val buf: ByteArrayOutputStream = ByteArrayOutputStream())

    val files = LinkedHashMap<String, F>()
    val commands = LinkedHashMap<String, String>()
    var statusJson: String? = null
    var quota = Quota(null, 0)
    var corruptChecksum = false
    var failAfterTotalBytes: Long = -1
    var uploadedBytes = 0L
    val deletes = ArrayList<DeleteRecord>()
    val createdCommands = ArrayList<String>()
    val startedUploads = ArrayList<String>()
    private val sessions = HashMap<String, Session>()
    private var seq = 1

    /** sha -> 받기 장부에서 포토 확인된 항목인가 (삭제 시점에 평가) */
    var confirmed: suspend (String) -> Boolean = { false }

    fun put(sha: String, name: String, content: ByteArray, declaredSha: String = sha, sizeProp: Long = content.size.toLong()): F {
        val id = "f${seq++}"
        val f = F(id, DriveNames.queueName(declaredSha, name),
            mapOf("kind" to "queue", "sha" to declaredSha, "name" to name, "takenAt" to "1000", "mime" to "image/jpeg", "size" to sizeProp.toString()),
            content, Hashing.sha256Hex(content))
        files[id] = f
        return f
    }

    private fun toDriveFile(f: F) = DriveFile(f.id, f.name, f.content.size.toLong(), f.reportedChecksum, f.props)

    override suspend fun about(): Quota = quota
    override suspend fun listQueue(): List<DriveFile> = files.values.filter { it.props["kind"] == "queue" }.map { toDriveFile(it) }
    override suspend fun findBySha(sha: String): List<DriveFile> { kotlinx.coroutines.yield(); return listQueue().filter { it.sha == sha } }

    /** 파일 이름별로 시작 오류를 흉내낸다(오류 분류 시험). null 이면 정상. */
    var errorForName: ((String) -> Exception?)? = null

    override suspend fun startUpload(name: String, size: Long, mime: String, props: Map<String, String>): String {
        kotlinx.coroutines.yield()
        errorForName?.invoke(props["name"] ?: "")?.let { throw it }
        val id = "session-${seq++}"
        sessions[id] = Session(name, size, props)
        startedUploads += id
        return id
    }

    /** 조각 업로드 직전 훅(취소·지연 시험용) */
    var chunkHook: (suspend () -> Unit)? = null

    override suspend fun uploadChunk(session: String, offset: Long, data: ByteArray, len: Int, total: Long): ChunkResult {
        kotlinx.coroutines.yield()
        chunkHook?.invoke()
        val s = sessions[session] ?: throw IOException("세션 없음")
        if (offset != s.buf.size().toLong()) throw IOException("offset 불일치 ${s.buf.size()} != $offset")
        if (failAfterTotalBytes >= 0 && uploadedBytes + len > failAfterTotalBytes) {
            failAfterTotalBytes = -1
            throw IOException("연결 끊김(시험)")
        }
        s.buf.write(data, 0, len)
        uploadedBytes += len
        if (s.buf.size().toLong() < total) return ChunkResult.More(s.buf.size().toLong())
        val content = s.buf.toByteArray()
        val id = "f${seq++}"
        val checksum = if (corruptChecksum) "0".repeat(64) else Hashing.sha256Hex(content)
        val f = F(id, s.name, s.props, content, checksum)
        files[id] = f
        sessions.remove(session)
        return ChunkResult.Done(toDriveFile(f))
    }

    override suspend fun queryOffset(session: String, total: Long): Long? = sessions[session]?.buf?.size()?.toLong()

    override suspend fun openDownload(fileId: String, from: Long): InputStream {
        val f = files[fileId] ?: throw IOException("파일 없음")
        return ByteArrayInputStream(f.content, from.toInt(), f.content.size - from.toInt())
    }

    override suspend fun deleteFile(fileId: String) {
        val f = files[fileId] ?: return
        val claimed = f.props["sha"]
        val actual = f.actualSha
        // 허용: 포토 확인된 sha 의 사본이거나, 내용이 주장한 sha 와 달라 보내기 쪽이 방금 만든 불량 사본
        val bad = f.reportedChecksum != null && f.reportedChecksum != claimed
        val allowed = bad || actual != claimed || (claimed != null && confirmed(claimed))
        deletes += DeleteRecord(fileId, claimed, actual, allowed)
        files.remove(fileId)
    }

    override suspend fun putStatus(json: String) { statusJson = json }
    override suspend fun getStatus(): String? = statusJson

    override suspend fun createCommand(id: String, json: String) {
        commands[id] = json; createdCommands += json
    }

    override suspend fun listCommands(): List<DriveFile> =
        commands.keys.map { DriveFile(it, DriveNames.commandName(it), 0, null, mapOf("kind" to "command")) }

    override suspend fun readCommand(fileId: String): String = commands[fileId] ?: ""
    override suspend fun deleteCommand(fileId: String) { commands.remove(fileId) }

    fun illegalDeletes(): List<DeleteRecord> = deletes.filter { !it.allowed }
}

/** 주력 폰 미디어 대역. */
class FakeMediaSource(private val clock: Clock) : MediaSource {
    var full = true
    var location = true
    val items = ArrayList<MediaItem>()
    val data = HashMap<String, ByteArray>()
    val gone = HashSet<String>()
    private var nextId = 1L

    fun add(
        name: String, bytes: ByteArray, rel: String = "DCIM/Camera/", video: Boolean = false, pending: Boolean = false,
        modifiedSec: Long = clock.nowMs() / 1000, sizeOverride: Long? = null,
    ): MediaItem {
        val id = nextId++
        val uri = "content://fake/$id"
        val m = MediaItem(id, uri, name, sizeOverride ?: bytes.size.toLong(), modifiedSec, clock.nowMs() / 1000, 1_600_000_000_000L + id,
            if (video) "video/mp4" else "image/jpeg", rel, video, pending)
        items += m
        data[uri] = bytes
        return m
    }

    fun replace(i: Int, f: (MediaItem) -> MediaItem) { items[i] = f(items[i]) }

    override fun hasFullMediaAccess(): Boolean = full
    override fun hasLocationAccess(): Boolean = location
    override fun list(addedSinceSec: Long): List<MediaItem> = items.filter { it.dateAddedSec >= addedSinceSec }
    override fun stat(id: Long): MediaItem? = items.firstOrNull { it.id == id }
    /** 파일을 열 때마다 불리는 훅(시험에서 시계를 흘려 보내는 데 쓴다) */
    var onOpen: (() -> Unit)? = null

    /** 스트림을 한 번 읽을 때마다 불리는 훅(큰 파일 하나를 읽는 동안 시간이 흐르는 상황을 흉내) */
    var onRead: (() -> Unit)? = null

    override fun openOriginal(uri: String): InputStream? {
        onOpen?.invoke()
        if (uri in gone) return null
        val bytes = data[uri] ?: return null
        return object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int { onRead?.invoke(); return super.read(b, off, len) }
        }
    }
}

fun randomBytes(n: Int, seed: Long = 1): ByteArray = ByteArray(n).also { java.util.Random(seed).nextBytes(it) }
