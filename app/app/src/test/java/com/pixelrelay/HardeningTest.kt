package com.pixelrelay

import com.pixelrelay.core.GB
import com.pixelrelay.core.Hashing
import com.pixelrelay.core.NetSnapshot
import com.pixelrelay.core.QueueMode
import com.pixelrelay.core.Reason
import com.pixelrelay.core.ReceiverConfig
import com.pixelrelay.core.RecvState
import com.pixelrelay.core.RemoteCommand
import com.pixelrelay.core.SendState
import com.pixelrelay.core.SenderLedger
import com.pixelrelay.drive.DriveHttpException
import com.pixelrelay.drive.Quota
import com.pixelrelay.receiver.BeginResult
import com.pixelrelay.receiver.CommandHandler
import com.pixelrelay.receiver.CommitOutcome
import com.pixelrelay.receiver.DriveReceiver
import com.pixelrelay.receiver.PutResult
import com.pixelrelay.receiver.ReceiverConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
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
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 교차검증 반영(DESIGN §15) 중 받기(픽셀) 쪽 재현 테스트: 항목 1·2·3·4·7·8·9·12·13 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReceiverHardeningTest {
    private lateinit var env: RecvEnv
    @Before fun setUp() { env = RecvEnv() }
    @After fun tearDown() { assertTrue(env.drive.illegalDeletes().isEmpty()); env.close() }

    private fun visibleFiles() = env.dir.listFiles()!!.filter { it.isFile }

    // ---- 1. 공개 이름: 기존 파일을 덮어쓰지 않는다 ----
    @Test
    fun `대체 이름도 이미 있으면 다음 후보로 가고 기존 픽셀 파일은 그대로`() = runBlocking {
        val bytes = randomBytes(900, 501)
        val sha = Hashing.sha256Hex(bytes)
        val x = randomBytes(50, 502); val y = randomBytes(60, 503)
        File(env.dir, "IMG.jpg").writeBytes(x)
        File(env.dir, "IMG_${sha.take(8)}.jpg").writeBytes(y)
        env.receiveWhole(bytes, "IMG.jpg")
        assertTrue(x.contentEquals(File(env.dir, "IMG.jpg").readBytes()))
        assertTrue(y.contentEquals(File(env.dir, "IMG_${sha.take(8)}.jpg").readBytes()))
        val p = File(env.ledger.get(sha)!!.path!!)
        assertEquals("IMG_${sha.take(8)}_2.jpg", p.name)
        assertEquals(sha, Hashing.sha256Hex(p.readBytes()))
    }

    // ---- 2. 포토 확인 후보 ----
    @Test
    fun `버튼 전에 삭제된 파일은 버튼을 눌러도 후보가 아니라 원인 불명이고 드라이브 삭제 0`() = runBlocking {
        val b = randomBytes(700, 510)
        val sha = env.receiveWhole(b, "a.jpg")
        val f = env.drive.put(sha, "a.jpg", b)
        env.ledger.attachCopy(sha, f.id, 700)
        val e = env.ledger.get(sha)!!
        File(e.path!!).delete(); env.index.removePath(e.path!!)      // 버튼 전에 이미 사라짐(아직 감지 전)
        env.confirmer.recordButton()
        env.confirmer.check(); env.clock.advance(61_000); env.confirmer.check()
        assertEquals(RecvState.VANISHED_UNKNOWN, env.ledger.get(sha)!!.stateEnum)
        assertEquals(0, env.drive.deletes.size)
    }

    @Test
    fun `버튼 순간 존재가 확인된 항목만 후보 - 같은 버튼으로 하나는 확인 하나는 원인 불명`() = runBlocking {
        val b1 = randomBytes(700, 511); val b2 = randomBytes(700, 512)
        val s1 = env.receiveWhole(b1, "a.jpg"); val s2 = env.receiveWhole(b2, "b.jpg")
        val e2 = env.ledger.get(s2)!!
        File(e2.path!!).delete(); env.index.removePath(e2.path!!)       // s2 는 버튼 전에 사라짐
        env.confirmer.recordButton()
        env.clock.advanceMin(10)
        val e1 = env.ledger.get(s1)!!
        File(e1.path!!).delete(); env.index.removePath(e1.path!!)       // s1 은 버튼 뒤에 사라짐
        env.confirmer.check(); env.clock.advance(61_000); env.confirmer.check()
        assertEquals(RecvState.PHOTOS_CONFIRMED, env.ledger.get(s1)!!.stateEnum)
        assertEquals(RecvState.VANISHED_UNKNOWN, env.ledger.get(s2)!!.stateEnum)
    }

    // ---- 3. 같은 sha put·commit 배타 ----
    private class GateStream(private val data: ByteArray, private val pauseAt: Int, val started: CountDownLatch, val gate: CountDownLatch) : InputStream() {
        private var pos = 0
        private var paused = false
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            if (pos == pauseAt && !paused) { paused = true; started.countDown(); gate.await(10, TimeUnit.SECONDS) }
            val limit = if (!paused && pos < pauseAt) pauseAt else data.size
            val n = minOf(len, limit - pos)
            System.arraycopy(data, pos, b, off, n); pos += n
            return n
        }
    }

    @Test
    fun `진행 중인 PUT 이 있으면 commit 은 기다렸다가 완전한 파일을 공개한다`() = runBlocking {
        val data = randomBytes(1000, 520)
        val sha = Hashing.sha256Hex(data)
        env.core.begin(sha, "a.jpg", 1000, 0, "image/jpeg", "lan")
        val started = CountDownLatch(1); val gate = CountDownLatch(1)
        val put = async(Dispatchers.IO) { env.core.put(sha, "lan", 0, GateStream(data, 500, started, gate), 1000) }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val commit = async(Dispatchers.IO) { env.core.commit(sha, "lan") }
        delay(300)
        assertFalse("commit 이 진행 중인 PUT 을 기다리지 않았다", commit.isCompleted)
        gate.countDown()
        assertEquals(PutResult.Ok(1000), put.await())
        assertEquals(CommitOutcome.Published, commit.await())
        assertEquals(sha, Hashing.sha256Hex(File(env.ledger.get(sha)!!.path!!).readBytes()))
        // 공개 뒤 늦게 온 PUT 은 파일에 아무것도 붙이지 못한다
        assertEquals(PutResult.NotBegun, env.core.put(sha, "lan", 1000, ByteArrayInputStream(randomBytes(50, 1)), 50))
        assertEquals(sha, Hashing.sha256Hex(File(env.ledger.get(sha)!!.path!!).readBytes()))
    }

    // ---- 9. 공간 예약 ----
    @Test
    fun `동시 수신은 진행 중 수신의 남은 바이트를 예약으로 잡아 begin 에서 거절`() = runBlocking {
        env.free = 14L * GB
        val a = "a".repeat(64); val b = "b".repeat(64)
        assertTrue(env.core.begin(a, "a.mp4", 6L * GB, 0, "video/mp4", "lan") is BeginResult.Ok)   // 6+4 <= 14
        val r = env.core.begin(b, "b.mp4", 6L * GB, 0, "video/mp4", "drive")                        // 14-6=8 < 6+4
        assertTrue(r is BeginResult.NoSpace)
        assertFalse((r as BeginResult.NoSpace).permanent)
        env.core.release(a, "lan")                                                                  // 예약 해제 뒤에는 가능
        assertTrue(env.core.begin(b, "b.mp4", 6L * GB, 0, "video/mp4", "drive") is BeginResult.Ok)
    }

    // ---- 13. 입력 검증 ----
    @Test
    fun `형식이 틀린 sha·음수 크기는 받지 않고 저장 계층은 경로 순회를 막는다`() = runBlocking {
        assertEquals(BeginResult.Invalid, env.core.begin("../../evil", "a.jpg", 10, 0, "image/jpeg", "lan"))
        assertEquals(BeginResult.Invalid, env.core.begin("A".repeat(64), "a.jpg", 10, 0, "image/jpeg", "lan"))
        assertEquals(BeginResult.Invalid, env.core.begin("a".repeat(64), "a.jpg", -1, 0, "image/jpeg", "lan"))
        try { env.sink.begin("../x"); fail() } catch (e: IllegalArgumentException) { }
        try { env.sink.partialLength("..\\x"); fail() } catch (e: IllegalArgumentException) { }
        // 드라이브 쪽: 큐 항목의 sha 가 오염돼 있어도 아무것도 받지 않고 폴더 밖에 파일이 생기지 않는다
        env.cfg = ReceiverConfig(QueueMode.IMMEDIATE)
        val data = randomBytes(300, 530)
        env.drive.put("x", "evil.jpg", data, declaredSha = "../../evil")
        env.drive.put("x", "upper.jpg", data, declaredSha = Hashing.sha256Hex(data).uppercase())
        val r = env.driveRx.runOnce()
        assertEquals(0, r.downloaded)
        assertTrue(env.ledger.all().isEmpty())
        assertEquals(0, visibleFiles().size)
        assertFalse(File(env.dir.parentFile, "evil.part").exists())
    }

    // ---- 12. 문턱 합계에 같은 sha 사본 포함 ----
    @Test
    fun `문턱 합계는 같은 sha 사본을 모두 세고 다운로드는 sha 당 1회`() = runBlocking {
        env.cfg = ReceiverConfig(QueueMode.CAPACITY, thresholdBytes = 1000, reserveBytes = 0)
        env.drive.quota = Quota(1_000_000, 0)
        val b = randomBytes(600, 540)
        val sha = Hashing.sha256Hex(b)
        env.drive.put(sha, "a.jpg", b); env.drive.put(sha, "a.jpg", b)          // 사본 2개 = 1200 >= 1000
        val r = env.driveRx.runOnce()
        assertTrue(r.received)
        assertEquals(1, r.downloaded)
        assertEquals(2, env.ledger.copies(sha).size)
        assertEquals(RecvState.PUBLISHED, env.ledger.get(sha)!!.stateEnum)
    }

    // ---- 8. sendNow expectShas ----
    @Test
    fun `sendNow 는 expectShas 가 장부에 오를 때까지 업로드 전이라도 강제 수신을 유지`() = runBlocking {
        env.cfg = ReceiverConfig(QueueMode.CAPACITY, thresholdBytes = 1_000_000, reserveBytes = 0)
        env.drive.quota = Quota(100_000_000, 0)
        val handler = CommandHandler(env.ledger, ReceiverConfigStore(env.meta), env.confirmer, env.clock)
        val b = randomBytes(300, 550)
        val sha = Hashing.sha256Hex(b)
        assertTrue(handler.handle(RemoteCommand(RemoteCommand.SEND_NOW, expectShas = listOf(sha)), remote = true).ok)
        assertFalse(env.driveRx.runOnce().received)                    // 아직 업로드 전: 큐가 비어 있다
        env.clock.advanceMin(5)
        env.drive.put(sha, "a.jpg", b)                                 // 그 뒤에 업로드가 끝남
        assertTrue(env.driveRx.runOnce().received)                     // 문턱 미만인데도 받는다
        assertEquals(RecvState.PUBLISHED, env.ledger.get(sha)!!.stateEnum)
        // 기대 목록이 다 채워졌으니 해제: 새 작은 파일은 다시 문턱 미만이라 안 받는다
        val b2 = randomBytes(300, 551)
        env.drive.put(Hashing.sha256Hex(b2), "b.jpg", b2)
        assertFalse(env.driveRx.runOnce().received)
    }

    @Test
    fun `expectShas 는 24시간이 지나면 해제된다`() = runBlocking {
        env.cfg = ReceiverConfig(QueueMode.CAPACITY, thresholdBytes = 1_000_000, reserveBytes = 0)
        env.drive.quota = Quota(100_000_000, 0)
        val handler = CommandHandler(env.ledger, ReceiverConfigStore(env.meta), env.confirmer, env.clock)
        handler.handle(RemoteCommand(RemoteCommand.SEND_NOW, expectShas = listOf("e".repeat(64))), remote = true)
        env.clock.advanceMin(24 * 60 + 1)
        val b = randomBytes(300, 552)
        env.drive.put(Hashing.sha256Hex(b), "a.jpg", b)
        assertFalse(env.driveRx.runOnce().received)
    }

    // ---- 7. 다시 보내기 허용 ----
    @Test
    fun `원인 불명 항목은 resendVanished 뒤 다시 받을 수 있고 이력과 사본 표는 보존`() = runBlocking {
        val handler = CommandHandler(env.ledger, ReceiverConfigStore(env.meta), env.confirmer, env.clock)
        val bytes = randomBytes(800, 560)
        val sha = env.receiveWhole(bytes, "a.jpg")
        val f = env.drive.put(sha, "a.jpg", bytes)
        env.ledger.attachCopy(sha, f.id, 800)
        env.ledger.transition(sha, RecvState.VANISHED_UNKNOWN)
        assertEquals(BeginResult.Already, env.core.begin(sha, "a.jpg", 800, 0, "image/jpeg", "lan"))   // 허용 전에는 거절

        // commandId 없으면 거절, 있으면 허용, 같은 commandId 재사용은 거절
        assertFalse(handler.handle(RemoteCommand(RemoteCommand.RESEND_VANISHED, sha = sha), remote = true).ok)
        assertTrue(handler.handle(RemoteCommand(RemoteCommand.RESEND_VANISHED, sha = sha, id = "r-1"), remote = true).ok)
        assertEquals(RecvState.RESEND_REQUESTED, env.ledger.get(sha)!!.stateEnum)
        assertFalse(handler.handle(RemoteCommand(RemoteCommand.RESEND_VANISHED, sha = sha, id = "r-1"), remote = true).ok)
        assertEquals(1, env.ledger.copies(sha).size)                        // 사본 표 보존

        // 대상이 아닌 sha(PUBLISHED)는 허용하지 않는다
        val other = env.receiveWhole(randomBytes(300, 561), "b.jpg")
        assertFalse(handler.handle(RemoteCommand(RemoteCommand.RESEND_VANISHED, sha = other, id = "r-2"), remote = true).ok)

        // LAN 으로 다시 받으면 PUBLISHED
        val b = env.core.begin(sha, "a.jpg", 800, 0, "image/jpeg", "lan") as BeginResult.Ok
        assertEquals(RecvState.RECEIVING, env.ledger.get(sha)!!.stateEnum)
        env.core.put(sha, "lan", b.offset, ByteArrayInputStream(bytes), 800)
        assertEquals(CommitOutcome.Published, env.core.commit(sha, "lan"))
        assertEquals(RecvState.PUBLISHED, env.ledger.get(sha)!!.stateEnum)
        assertEquals(1, env.ledger.copies(sha).size)
    }

    @Test
    fun `resend 허용된 항목은 드라이브 큐 사본으로도 다시 받는다`() = runBlocking {
        env.cfg = ReceiverConfig(QueueMode.IMMEDIATE)
        val handler = CommandHandler(env.ledger, ReceiverConfigStore(env.meta), env.confirmer, env.clock)
        val bytes = randomBytes(800, 562)
        val sha = env.receiveWhole(bytes, "a.jpg")
        env.drive.put(sha, "a.jpg", bytes)
        env.ledger.transition(sha, RecvState.VANISHED_UNKNOWN)
        handler.handle(RemoteCommand(RemoteCommand.RESEND_VANISHED, sha = sha, id = "r-9"), remote = true)
        val r = env.driveRx.runOnce()
        assertEquals(1, r.downloaded)
        assertEquals(RecvState.PUBLISHED, env.ledger.get(sha)!!.stateEnum)
    }

    // ---- 4. 드라이브 명령 commandId 재사용 ----
    @Test
    fun `파괴적 명령은 commandId 가 필수이고 LAN·드라이브 어느 경로든 재사용하면 거절`() = runBlocking {
        val handler = CommandHandler(env.ledger, ReceiverConfigStore(env.meta), env.confirmer, env.clock)
        val s1 = env.receiveWhole(randomBytes(300, 570), "a.jpg"); val s2 = env.receiveWhole(randomBytes(300, 571), "b.jpg")
        env.ledger.transition(s1, RecvState.VANISHED_UNKNOWN); env.ledger.transition(s2, RecvState.VANISHED_UNKNOWN)
        assertFalse(handler.handle(RemoteCommand(RemoteCommand.CONFIRM_VANISHED, sha = s1), remote = true).ok)
        assertTrue(handler.handle(RemoteCommand(RemoteCommand.CONFIRM_VANISHED, sha = s1, id = "c-1"), remote = true).ok)
        assertFalse(handler.handle(RemoteCommand(RemoteCommand.CONFIRM_VANISHED, sha = s2, id = "c-1"), remote = true).ok)
        assertEquals(RecvState.VANISHED_UNKNOWN, env.ledger.get(s2)!!.stateEnum)
        // 7일이 지나면 보관 목록에서 빠진다
        env.clock.advanceMin(7 * 24 * 60 + 1)
        assertTrue(handler.handle(RemoteCommand(RemoteCommand.CONFIRM_VANISHED, sha = s2, id = "c-1"), remote = true).ok)
    }
}

/** 교차검증 반영(DESIGN §15) 중 보내기 쪽: 항목 5·6·10·11 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SenderHardeningTest {
    private val dbs = ArrayList<com.pixelrelay.core.db.AppDb>()
    @After fun tearDown() { dbs.forEach { runCatching { it.close() } } }
    private fun env() = SendEnv(TestDb.memory().also { dbs += it })

    // ---- 5. 보내기 직렬화 + 사용량 정산 ----
    @Test
    fun `엔진을 동시에 두 번 돌려도 같은 파일을 한 번만 올린다`() = runBlocking {
        val e = env()
        val b = randomBytes(20_000, 600)
        e.source.add("a.jpg", b)
        e.registerNow()
        awaitAll(async { e.engine.runOnce() }, async { e.engine.runOnce() })
        assertEquals(1, e.drive.startedUploads.size)
        assertEquals(1, e.drive.files.size)
        assertEquals(SendState.IN_DRIVE, e.entryFor(b)!!.stateEnum)
    }

    @Test
    fun `하루 사용량은 보내기 전에 예약하고 실제 바이트로 정산한다`() = runBlocking {
        val e = env().also { it.net.snap = NetSnapshot(true, false, false) }
        val b = randomBytes(30_000, 601)
        e.source.add("a.jpg", b)
        e.registerNow()
        e.drive.failAfterTotalBytes = 9_000
        e.engine.runOnce()
        assertEquals(8192L, e.usage.today())          // 예약(30000)이 아니라 실제로 보낸 8192 만 남는다
        e.clock.advance(60_000)
        e.engine.runOnce()
        assertTrue(e.usage.today() >= 8192L && e.usage.today() <= 30_000L)
    }

    // ---- 6. 장부 갱신 원자성 ----
    @Test
    fun `진행 정보 갱신이 동시에 와도 완료 상태가 되돌려지지 않는다(보내기)`() = runBlocking {
        val db = TestDb.memory().also { dbs += it }
        val l = SenderLedger(db, FakeClock())
        repeat(25) { i ->
            val sha = i.toString(16).padStart(64, '0')
            l.register(mkEntry(sha = sha))
            l.transition(sha, SendState.SENDING_LAN)
            val jobs = (1..30).map { k ->
                async(Dispatchers.IO) {
                    if (k == 15) l.transition(sha, SendState.RECEIVED)
                    else { l.patch(sha) { it.copy(driveOffset = k.toLong()) }; l.setLanOffset(sha, k.toLong()) }
                }
            }
            jobs.awaitAll()
            assertEquals(SendState.RECEIVED, l.get(sha)!!.stateEnum)
        }
    }

    @Test
    fun `진행 정보 갱신이 동시에 와도 완료 상태가 되돌려지지 않는다(받기)`() = runBlocking {
        val clock = FakeClock()
        val db = TestDb.memory().also { dbs += it }
        val l = com.pixelrelay.core.ReceiverLedger(db, clock)
        repeat(25) { i ->
            val sha = i.toString(16).padStart(64, 'a')
            l.create(sha, "a.jpg", 10, 0, "image/jpeg", "lan")
            val jobs = (1..30).map { k ->
                async(Dispatchers.IO) {
                    if (k == 15) l.transition(sha, RecvState.PUBLISHED) { it.copy(path = "/x") }
                    else l.patch(sha) { it.copy(failCount = k) }
                }
            }
            jobs.awaitAll()
            val e = l.get(sha)!!
            assertEquals(RecvState.PUBLISHED, e.stateEnum)
            assertEquals("/x", e.path)
        }
    }

    // ---- 10. 드라이브 오류 분류 ----
    @Test
    fun `드라이브 오류는 파일별로 분류되고 한 파일 오류가 나머지를 막지 않는다`() = runBlocking {
        val e = env()
        val a = randomBytes(5_000, 610); val b = randomBytes(5_000, 611)
        e.source.add("A.jpg", a); e.source.add("B.jpg", b)
        e.registerNow()
        e.drive.errorForName = { n -> if (n == "A.jpg") DriveHttpException(400, "bad") else null }
        e.engine.runOnce()
        val ea = e.entryFor(a)!!
        assertEquals(1, ea.attempts)                                   // 그 밖(400) -> 시도 증가
        assertTrue(ea.stateEnum != SendState.IN_DRIVE)
        assertEquals(SendState.IN_DRIVE, e.entryFor(b)!!.stateEnum)    // 나머지는 계속 진행

        e.drive.errorForName = { n -> if (n == "A.jpg") DriveHttpException(403, "rate") else null }
        e.clock.advanceMin(3)
        e.engine.runOnce()
        val e2 = e.entryFor(a)!!
        assertEquals(1, e2.attempts)                                   // 403 -> 시도 증가 없이 대기
        assertEquals(Reason.NETWORK, e2.reason)

        e.drive.errorForName = { n -> if (n == "A.jpg") DriveHttpException(503, "down") else null }
        e.clock.advanceMin(10)
        e.engine.runOnce()
        assertEquals(1, e.entryFor(a)!!.attempts)                      // 5xx 도 동일

        e.drive.errorForName = { n -> if (n == "A.jpg") DriveHttpException(401, "auth") else null }
        e.clock.advanceMin(10)
        e.engine.runOnce()
        assertEquals(Reason.PERMISSION, e.entryFor(a)!!.reason)        // 401 -> 인증 필요
        assertEquals(1L, e.meta.getLong("driveAuthProblem"))

        e.drive.errorForName = null
        e.clock.advanceMin(10)
        e.meta.putLong("driveAuthProblem", 0)
        e.engine.runOnce()
        assertEquals(SendState.IN_DRIVE, e.entryFor(a)!!.stateEnum)
    }

    // ---- 11. 전송 직전 재검사 ----
    @Test
    fun `전송 직전 기록 중이면 보내지 않고 대기(기록중)`() = runBlocking {
        val e = env()
        val b = randomBytes(5_000, 620)
        e.source.add("a.jpg", b)
        e.registerNow()
        e.source.replace(0) { it.copy(isPending = true) }
        e.engine.runOnce()
        val en = e.entryFor(b)!!
        assertEquals(SendState.WAITING, en.stateEnum)
        assertEquals(Reason.RECORDING, en.reason)
        assertTrue(e.drive.files.isEmpty() && e.drive.startedUploads.isEmpty())
        // 기록이 끝나면 보낸다
        e.source.replace(0) { it.copy(isPending = false) }
        e.clock.advance(61_000)
        e.engine.runOnce()
        assertEquals(SendState.IN_DRIVE, e.entryFor(b)!!.stateEnum)
    }

    @Test
    fun `등록 뒤 원본이 바뀌면 옛 항목은 SKIPPED(원본변경)이고 안정되면 새 sha 로 보낸다`() = runBlocking {
        val e = env()
        val old = randomBytes(5_000, 621)
        val item = e.source.add("a.jpg", old)
        e.registerNow()
        val neu = randomBytes(7_000, 622)
        e.source.data[item.uri] = neu
        e.source.replace(0) { it.copy(size = 7_000, dateModifiedSec = it.dateModifiedSec + 5) }
        e.engine.runOnce()
        val o = e.entryFor(old)!!
        assertEquals(SendState.SKIPPED, o.stateEnum)
        assertEquals(Reason.ORIGINAL_CHANGED, o.reason)
        assertTrue(e.drive.startedUploads.isEmpty())                   // 옛 내용은 올리지 않았다
        assertNull(e.entryFor(neu))                                    // 새 내용은 아직 안정 전
        e.clock.advance(61_000)
        e.engine.runOnce()
        assertEquals(SendState.IN_DRIVE, e.entryFor(neu)!!.stateEnum)
        assertEquals(1, e.drive.files.size)
        assertEquals(Hashing.sha256Hex(neu), e.drive.files.values.single().actualSha)
    }
}
