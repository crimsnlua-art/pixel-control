package com.pixelrelay

import com.pixelrelay.core.GB
import com.pixelrelay.core.Hashing
import com.pixelrelay.core.NetSnapshot
import com.pixelrelay.core.Reason
import com.pixelrelay.core.RecvState
import com.pixelrelay.core.RemoteCommand
import com.pixelrelay.core.SendState
import com.pixelrelay.core.QueueMode
import com.pixelrelay.core.ReceiverConfig
import com.pixelrelay.drive.DriveApi
import com.pixelrelay.receiver.BeginResult
import com.pixelrelay.receiver.Cleaner
import com.pixelrelay.receiver.CommandHandler
import com.pixelrelay.receiver.DriveReceiver
import com.pixelrelay.receiver.PhotosConfirmer
import com.pixelrelay.receiver.PutResult
import com.pixelrelay.receiver.ReceiverConfigStore
import com.pixelrelay.sender.LanSender
import com.pixelrelay.sender.SendResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

/** 2차 재검증 지적 6건(받기 쪽: 1·3·5) */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Round2ReceiverTest {
    private lateinit var env: RecvEnv
    @Before fun setUp() { env = RecvEnv() }
    @After fun tearDown() { env.close() }

    private fun handler(wake: () -> Unit = {}, confirmer: PhotosConfirmer = env.confirmer) =
        CommandHandler(env.ledger, ReceiverConfigStore(env.meta), confirmer, env.clock, wake)

    // ---- 1. 기존 RECEIVING 행과 size·mime 가 다르면 부분 파일을 건드리기 전에 거절 ----
    @Test
    fun `진행 중 항목과 size 또는 mime 가 다른 begin 은 부분 파일을 건드리지 않고 거절`() = runBlocking {
        val sha = "a".repeat(64)
        assertTrue(env.core.begin(sha, "a.jpg", 1000, 0, "image/jpeg", "lan") is BeginResult.Ok)
        env.core.put(sha, "lan", 0, ByteArrayInputStream(randomBytes(500, 1)), 500)
        assertEquals(BeginResult.Mismatch, env.core.begin(sha, "a.jpg", 300, 0, "image/jpeg", "lan"))
        assertEquals(BeginResult.Mismatch, env.core.begin(sha, "a.jpg", 1000, 0, "video/mp4", "lan"))
        assertEquals(500L, env.sink.partialLength(sha))                      // 부분 파일이 버려지지 않았다
        // put 상한도 장부의 size(1000) 하나가 기준
        val r = env.core.put(sha, "lan", 500, ByteArrayInputStream(randomBytes(5000, 2)), 5000)
        assertEquals(PutResult.Ok(1000), r)
        assertEquals(1000L, env.sink.partialLength(sha))
    }

    @Test
    fun `size 를 줄여 다시 begin 해도 예약이 줄지 않는다`() = runBlocking {
        env.free = 14L * GB
        val a = "a".repeat(64); val b = "b".repeat(64)
        assertTrue(env.core.begin(a, "a.mp4", 6L * GB, 0, "video/mp4", "lan") is BeginResult.Ok)
        assertEquals(BeginResult.Mismatch, env.core.begin(a, "a.mp4", 1, 0, "video/mp4", "lan"))
        assertTrue(env.core.begin(b, "b.mp4", 6L * GB, 0, "video/mp4", "drive") is BeginResult.NoSpace)
    }

    // ---- 3. expectShas 가 있으면 수신 루프가 바로 돈다 ----
    @Test
    fun `expectShas 가 있는 sendNow 는 강제 실행 조건이 켜지고 수신 루프를 깨운다`() = runBlocking {
        var woke = 0
        val h = handler(wake = { woke++ })
        assertFalse(env.driveRx.forcedNow())
        assertTrue(h.handle(RemoteCommand(RemoteCommand.SEND_NOW, expectShas = listOf("f".repeat(64))), remote = true).ok)
        assertEquals(1, woke)
        assertTrue(env.driveRx.forcedNow())              // KEY_FORCE 없이도 루프가 지금 돌아야 한다
        env.clock.advanceMin(24 * 60 + 1)
        assertFalse(env.driveRx.forcedNow())
    }

    // ---- 5. commandId 기록과 상태 전이는 한 트랜잭션 ----
    @Test
    fun `명령 처리 중 예외가 나면 commandId 도 상태 전이도 함께 되돌려진다`() = runBlocking {
        val sha = env.receiveWhole(randomBytes(300, 20), "a.jpg")
        env.ledger.transition(sha, RecvState.VANISHED_UNKNOWN)
        val bad = handler(wake = { throw IllegalStateException("주입한 실패") })
        try { bad.handle(RemoteCommand(RemoteCommand.RESEND_VANISHED, sha = sha, id = "t-1"), remote = true); fail() } catch (e: IllegalStateException) { }
        assertEquals(RecvState.VANISHED_UNKNOWN, env.ledger.get(sha)!!.stateEnum)    // 전이 되돌림
        // commandId 도 남지 않았으므로 같은 id 로 다시 처리 가능
        assertTrue(handler().handle(RemoteCommand(RemoteCommand.RESEND_VANISHED, sha = sha, id = "t-1"), remote = true).ok)
        assertEquals(RecvState.RESEND_REQUESTED, env.ledger.get(sha)!!.stateEnum)
    }

    @Test
    fun `처리 실패(대상 아님)한 명령의 commandId 는 소비되지 않는다`() = runBlocking {
        val sha = env.receiveWhole(randomBytes(300, 21), "a.jpg")           // PUBLISHED: 확정 대상 아님
        assertFalse(handler().handle(RemoteCommand(RemoteCommand.CONFIRM_VANISHED, sha = sha, id = "t-2"), remote = true).ok)
        env.ledger.transition(sha, RecvState.VANISHED_UNKNOWN)
        assertTrue(handler().handle(RemoteCommand(RemoteCommand.CONFIRM_VANISHED, sha = sha, id = "t-2"), remote = true).ok)
    }

    @Test
    fun `확정 뒤 드라이브 삭제가 실패해도 상태는 확정이고 나중에 Cleaner 가 다시 정리한다`() = runBlocking {
        val b = randomBytes(400, 22)
        val sha = env.receiveWhole(b, "a.jpg")
        val f = env.drive.put(sha, "a.jpg", b)
        env.ledger.attachCopy(sha, f.id, 400)
        env.ledger.transition(sha, RecvState.VANISHED_UNKNOWN)
        val failing = object : DriveApi by env.drive {
            override suspend fun deleteFile(fileId: String) { throw IOException("삭제 실패(시험)") }
        }
        val h = handler(confirmer = PhotosConfirmer(env.ledger, env.sink, env.clock, Cleaner(env.ledger, failing)))
        assertTrue(h.handle(RemoteCommand(RemoteCommand.CONFIRM_VANISHED, sha = sha, id = "t-3"), remote = true).ok)
        assertEquals(RecvState.PHOTOS_CONFIRMED, env.ledger.get(sha)!!.stateEnum)
        assertEquals(0, env.drive.deletes.size)
        assertFalse(h.handle(RemoteCommand(RemoteCommand.CONFIRM_VANISHED, sha = sha, id = "t-3"), remote = true).ok)  // 재생 거절
        env.cleaner.cleanAllConfirmed()
        assertEquals(RecvState.CLEANED, env.ledger.get(sha)!!.stateEnum)
        assertEquals(1, env.drive.deletes.size)
        assertTrue(env.drive.illegalDeletes().isEmpty())
    }
}

/** 2차 재검증 지적 6건(보내기 쪽: 2·4·6) */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Round2SenderTest {
    private val dbs = ArrayList<com.pixelrelay.core.db.AppDb>()
    private val recvs = ArrayList<RecvEnv>()
    @After fun tearDown() { dbs.forEach { runCatching { it.close() } }; recvs.forEach { runCatching { it.close() } } }
    private fun env(clock: FakeClock = FakeClock()) = SendEnv(TestDb.memory().also { dbs += it }, clock)

    // ---- 2. 다시 보내기: 영속 RESEND_PENDING, 픽셀 확인 뒤에만 WAITING ----
    @Test
    fun `다시 보내기는 명령 실패 동안 RESEND_PENDING 을 유지하고 같은 commandId 로 재시도하며 픽셀 확인 뒤에야 보낸다`() = runBlocking {
        val clock = FakeClock()
        val e = env(clock)
        val recv = RecvEnv(clock).also { recvs += it }
        val api = CoreBackedPixelApi(recv)
        e.lanApi = api
        val b = randomBytes(9000, 700)
        val sha = Hashing.sha256Hex(b)
        e.source.add("a.jpg", b); e.registerNow()
        e.engine.runOnce()
        assertEquals(SendState.RECEIVED, e.ledger.get(sha)!!.stateEnum)
        recv.ledger.transition(sha, RecvState.VANISHED_UNKNOWN)
        e.engine.runOnce()                                              // 동기화로 원인 불명을 알게 됨
        assertEquals(SendState.VANISHED_UNKNOWN, e.ledger.get(sha)!!.stateEnum)

        // 사용자가 다시 보내기: 영속 기록만, 아직 보내지 않는다
        assertEquals("rid-1", e.ledger.requestResend(sha, "rid-1")!!.resendCommandId)
        // 명령이 실패하는 동안(LAN 끊김 -> 드라이브 명령 파일로 대체) 상태 유지
        api.down = true
        e.engine.runOnce()
        assertEquals(SendState.RESEND_PENDING, e.ledger.get(sha)!!.stateEnum)
        assertEquals(1, e.drive.createdCommands.size)
        assertTrue(e.drive.createdCommands[0].contains("rid-1"))
        // 5분 전에는 다시 보내지 않고, 5분 뒤에는 같은 commandId 로 재시도
        clock.advance(60_000); e.engine.runOnce()
        assertEquals(1, e.drive.createdCommands.size)
        clock.advanceMin(6); e.engine.runOnce()
        assertEquals(2, e.drive.createdCommands.size)
        assertTrue(e.drive.createdCommands[1].contains("rid-1"))
        assertEquals(SendState.RESEND_PENDING, e.ledger.get(sha)!!.stateEnum)

        // 픽셀이 명령을 처리해 RESEND_REQUESTED 가 되면 그 뒤에만 WAITING -> 다시 보내 RECEIVED
        api.down = false
        val h = CommandHandler(recv.ledger, ReceiverConfigStore(recv.meta), recv.confirmer, recv.clock)
        assertTrue(h.handle(RemoteCommand(RemoteCommand.RESEND_VANISHED, sha = sha, id = "rid-1"), remote = true).ok)
        e.engine.runOnce()
        assertEquals(SendState.RECEIVED, e.ledger.get(sha)!!.stateEnum)
        assertNull(e.ledger.get(sha)!!.resendCommandId)
        assertEquals(RecvState.PUBLISHED, recv.ledger.get(sha)!!.stateEnum)
    }

    @Test
    fun `다시 보내기 흐름 중인 항목은 begin 이 already 여도 도착으로 처리하지 않는다`() = runBlocking {
        val clock = FakeClock()
        val e = env(clock)
        val recv = RecvEnv(clock).also { recvs += it }
        val api = CoreBackedPixelApi(recv)
        val b = randomBytes(2000, 701)
        val sha = recv.receiveWhole(b, "a.jpg")                         // 픽셀은 이미 가지고 있음(already)
        val base = mkEntry(size = 2000, sha = sha, state = "WAITING").copy(name = "a.jpg")
        val sender = LanSender(e.source, e.ledger, clock)
        // 흐름 밖: already -> 도착
        e.ledger.register(base)
        assertTrue(sender.send(base, api, clock.nowMs() + 60_000) is SendResult.Done)
        // 흐름 중: already -> 도착으로 처리하지 않음
        val sha2 = "d".repeat(64)
        recv.ledger.create(sha2, "b.jpg", 10, 0, "image/jpeg", "lan"); recv.ledger.transition(sha2, RecvState.PUBLISHED)
        val flow = mkEntry(size = 10, sha = sha2, state = "WAITING").copy(resendCommandId = "x")
        e.ledger.register(flow)
        assertEquals(SendResult.ResendNotYet, sender.send(flow, api, clock.nowMs() + 60_000))
    }

    // ---- 4. 수정 시각 기준이 없는 행은 해시 재검증 ----
    @Test
    fun `수정 시각 기준이 없는 행은 통과시키지 않고 해시를 다시 확인한다(내용 바뀜)`() = runBlocking {
        val e = env()
        val old = randomBytes(5000, 710)
        val item = e.source.add("a.jpg", old)
        e.registerNow()
        val sha = Hashing.sha256Hex(old)
        e.ledger.patch(sha) { it.copy(dateModifiedSec = 0) }           // 기준 없음
        e.source.data[item.uri] = randomBytes(5000, 711)                // 같은 크기·같은 시각, 다른 내용
        e.engine.runOnce()
        val en = e.ledger.get(sha)!!
        assertEquals(SendState.SKIPPED, en.stateEnum)
        assertEquals(Reason.ORIGINAL_CHANGED, en.reason)
        assertTrue(e.drive.startedUploads.isEmpty())
    }

    @Test
    fun `수정 시각 기준이 없는 행도 내용이 같으면 보내고 기준 시각을 채운다`() = runBlocking {
        val e = env()
        val b = randomBytes(5000, 712)
        val item = e.source.add("a.jpg", b)
        e.registerNow()
        val sha = Hashing.sha256Hex(b)
        e.ledger.patch(sha) { it.copy(dateModifiedSec = 0) }
        e.engine.runOnce()
        assertEquals(SendState.IN_DRIVE, e.ledger.get(sha)!!.stateEnum)
        assertEquals(item.dateModifiedSec, e.ledger.get(sha)!!.dateModifiedSec)
    }

    @Test
    fun `DB 는 버전 1 하나이고 마이그레이션 코드가 없다`() {
        val src = File("src/main/java/com/pixelrelay/core/db/Db.kt").readText()
        assertFalse(src.contains("MIGRATION"))
        assertFalse(src.contains("addMigrations"))
        assertTrue(Regex("version\\s*=\\s*1,").containsMatchIn(src))
    }

    // ---- 6. 하루 사용량 예약은 별도 저장 · NonCancellable 정산 · 시작 때 해제 ----
    @Test
    fun `엔진 시작 때 이전 실행의 미정산 예약을 해제한다`() = runBlocking {
        val e = env().also { it.net.snap = NetSnapshot(true, false, false) }
        e.meta.putLong("usageReserved", 5_000)
        assertEquals(5_000L, e.usage.today())                           // 정산 전 예약은 별도 열에 있다
        assertEquals(0L, e.usage.actualToday())
        e.engine.runOnce()
        assertEquals(0L, e.usage.reserved())
        assertEquals(0L, e.usage.today())
    }

    @Test
    fun `보내는 도중 취소돼도 예약은 정산되어 사라진다`() = runBlocking {
        val e = env().also { it.net.snap = NetSnapshot(true, false, false) }
        val b = randomBytes(30_000, 720)
        e.source.add("a.jpg", b); e.registerNow()
        e.drive.chunkHook = { delay(60_000) }
        val job = launch(Dispatchers.Default) { e.engine.runOnce() }
        var waited = 0
        while (e.usage.reserved() == 0L && waited < 400) { delay(10); waited++ }
        assertTrue("예약이 잡히지 않았다", e.usage.reserved() > 0L)
        assertEquals(0L, e.usage.actualToday())                         // 예약은 실제 사용량과 분리 저장
        job.cancelAndJoin()
        assertEquals(0L, e.usage.reserved())
    }
}
