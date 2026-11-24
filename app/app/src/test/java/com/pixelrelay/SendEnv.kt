package com.pixelrelay

import com.pixelrelay.core.GB
import com.pixelrelay.core.Hashing
import com.pixelrelay.core.LedgerItem
import com.pixelrelay.core.LedgerPage
import com.pixelrelay.core.MetaStore
import com.pixelrelay.core.PixelStatus
import com.pixelrelay.core.ReceiverConfig
import com.pixelrelay.core.RemoteCommand
import com.pixelrelay.core.SenderLedger
import com.pixelrelay.core.SenderSettings
import com.pixelrelay.core.StatusCounts
import com.pixelrelay.core.db.AppDb
import com.pixelrelay.core.db.SendEntry
import com.pixelrelay.receiver.BeginResult
import com.pixelrelay.receiver.CommitOutcome
import com.pixelrelay.receiver.PutResult
import com.pixelrelay.sender.ApiBegin
import com.pixelrelay.sender.ApiCommit
import com.pixelrelay.sender.ApiPut
import com.pixelrelay.sender.DailyUsage
import com.pixelrelay.sender.DrainRegistrar
import com.pixelrelay.sender.DriveSender
import com.pixelrelay.sender.LanConnector
import com.pixelrelay.sender.LanSender
import com.pixelrelay.sender.LedgerSync
import com.pixelrelay.sender.MediaScanner
import com.pixelrelay.sender.PixelApi
import com.pixelrelay.sender.RemoteStatusStore
import com.pixelrelay.sender.SenderCommands
import com.pixelrelay.sender.SenderEngine
import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.ZoneOffset

/** 프로세스 안에서 받기 핵심(ReceiverCore)에 직접 붙는 픽셀 대역. 네트워크 끊김도 흉내낸다. */
class CoreBackedPixelApi(private val recv: RecvEnv) : PixelApi {
    var down = false
    var failPutAfterCalls = -1
    private var putCalls = 0
    val commands = ArrayList<RemoteCommand>()

    private fun check() { if (down) throw IOException("LAN 끊김(시험)") }

    override suspend fun status(): PixelStatus {
        check()
        return PixelStatus(recv.free, 80, false, 30.0, StatusCounts(0, 0, 0, 0), 0, ReceiverConfig(), emptyList(), recv.ledger.maxSeq(), "t", recv.clock.nowMs())
    }

    override suspend fun begin(sha: String, name: String, size: Long, takenAt: Long, mime: String): ApiBegin {
        check()
        return when (val r = recv.core.begin(sha, name, size, takenAt, mime, "lan")) {
            is BeginResult.Ok -> ApiBegin.Ok(r.resume, r.offset)
            BeginResult.Already -> ApiBegin.Already
            BeginResult.Busy -> ApiBegin.Busy
            is BeginResult.NoSpace -> ApiBegin.NoSpace(r.permanent)
            BeginResult.Hot -> ApiBegin.Hot
            BeginResult.Invalid -> throw IOException("invalid")
            BeginResult.Mismatch -> throw IOException("mismatch")
        }
    }

    override suspend fun put(sha: String, offset: Long, data: ByteArray, len: Int): ApiPut {
        check()
        putCalls++
        if (failPutAfterCalls in 0 until putCalls) { down = true; throw IOException("조각 도중 끊김(시험)") }
        return when (val r = recv.core.put(sha, "lan", offset, ByteArrayInputStream(data, 0, len), len.toLong())) {
            is PutResult.Ok -> ApiPut.Ok(r.offset)
            is PutResult.Conflict -> ApiPut.Conflict(r.offset)
            PutResult.Hot -> ApiPut.Hot
            PutResult.NotBegun -> ApiPut.NotBegun
        }
    }

    override suspend fun commit(sha: String): ApiCommit {
        check()
        return when (val r = recv.core.commit(sha, "lan")) {
            CommitOutcome.Published -> ApiCommit.Published
            CommitOutcome.Mismatch -> ApiCommit.Mismatch
            is CommitOutcome.Incomplete -> ApiCommit.Incomplete(r.offset)
            CommitOutcome.NotBegun -> ApiCommit.NotBegun
        }
    }

    var ledgerCalls = 0
    override suspend fun ledger(since: Long, limit: Int): LedgerPage {
        check()
        ledgerCalls++
        val rows = recv.ledger.changesSince(since, limit + 1)
        val items = rows.take(limit).map { LedgerItem(it.sha, it.state, it.at, it.seq) }
        return LedgerPage(items, items.lastOrNull()?.seq ?: since, rows.size > limit)
    }

    override suspend fun command(cmd: RemoteCommand): Boolean { check(); commands += cmd; return true }
}

/** 보내기 쪽 시험 환경. */
class SendEnv(val db: AppDb = TestDb.memory(), val clock: FakeClock = FakeClock(), chunk: Int = 4096) {
    val ledger = SenderLedger(db, clock)
    val meta = MetaStore(db)
    val source = FakeMediaSource(clock)
    val net = FakeNetwork()
    val drive = FakeDriveApi()
    var settings = SenderSettings()
    var lanApi: PixelApi? = null
    val lan = object : LanConnector { override suspend fun connect(): PixelApi? = lanApi }
    val scanner = MediaScanner(source, ledger, clock) { settings }
    val sync = LedgerSync(ledger, meta)
    val statusStore = RemoteStatusStore(meta, clock)
    val usage = DailyUsage(meta, clock, ZoneOffset.UTC)
    val registrar = DrainRegistrar(meta, clock)
    val engine = SenderEngine(
        scanner, source, ledger, meta, clock, net, drive, lan,
        LanSender(source, ledger, clock, chunk), DriveSender(source, ledger, drive, clock, chunk),
        { settings }, usage, registrar, sync, statusStore,
        commands = SenderCommands(lan, drive, net),
    )

    /** 60초 안정 조건을 채워 장부에 등록시킨다. */
    suspend fun registerNow() {
        scanner.scan()
        clock.advance(61_000)
        scanner.scan()
    }

    suspend fun entryFor(bytes: ByteArray): SendEntry? = ledger.get(Hashing.sha256Hex(bytes))
}

fun mkEntry(size: Long = 1000, video: Boolean = false, state: String = "WAITING", sha: String = "a".repeat(64)) = SendEntry(
    sha = sha, mediaId = 1, uri = "content://x/1", name = "a.jpg", size = size, takenAt = 0, mime = "image/jpeg",
    relPath = "DCIM/Camera/", isVideo = video, state = state, createdAt = 0, stateAt = 0,
)

val GBL = GB
