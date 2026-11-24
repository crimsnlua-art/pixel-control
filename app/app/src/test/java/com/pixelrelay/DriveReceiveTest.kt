package com.pixelrelay

import com.pixelrelay.core.GB
import com.pixelrelay.core.Hashing
import com.pixelrelay.core.QueueMode
import com.pixelrelay.core.ReceiverConfig
import com.pixelrelay.core.RecvState
import com.pixelrelay.core.RemoteCommand
import com.pixelrelay.drive.Quota
import com.pixelrelay.receiver.BeginResult
import com.pixelrelay.receiver.CleanResult
import com.pixelrelay.receiver.CommandHandler
import com.pixelrelay.receiver.DriveReceiver
import com.pixelrelay.receiver.ReceivePolicy
import com.pixelrelay.receiver.ReceiverConfigStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 완료 기준 8: 수신 모드 (정책 단독) */
class ReceivePolicyTest {
    private val cfg = ReceiverConfig(QueueMode.CAPACITY, 10 * GB, 3 * GB)

    @Test fun `실제 문턱은 설정 용량과 남은 용량 빼기 3GB 중 작은 쪽`() {
        assertEquals(2 * GB, ReceivePolicy.effectiveThreshold(cfg, 5 * GB))
        assertEquals(10 * GB, ReceivePolicy.effectiveThreshold(cfg, 20 * GB))
    }

    @Test fun `용량 모드는 문턱 미만이면 안 받고 이상이면 받는다`() {
        assertFalse(ReceivePolicy.shouldReceive(cfg, 2 * GB - 1, 5 * GB, forced = false, draining = false))
        assertTrue(ReceivePolicy.shouldReceive(cfg, 2 * GB, 5 * GB, forced = false, draining = false))
    }

    @Test fun `이미 비우는 중이면 문턱 미만이어도 계속 받는다`() =
        assertTrue(ReceivePolicy.shouldReceive(cfg, 1, 5 * GB, forced = false, draining = true))

    @Test fun `sendNow·drainNow 는 모드와 무관하게 받는다`() {
        assertTrue(ReceivePolicy.shouldReceive(cfg, 1, 50 * GB, forced = true, draining = false))
        assertTrue(ReceivePolicy.shouldReceive(cfg.copy(queueMode = QueueMode.CAPACITY), 5, 50 * GB, forced = true, draining = false))
    }

    @Test fun `바로 모드는 대기 파일이 있으면 항상 받는다, 없으면 받을 것이 없다`() {
        val im = cfg.copy(queueMode = QueueMode.IMMEDIATE)
        assertTrue(ReceivePolicy.shouldReceive(im, 1, 50 * GB, false, false))
        assertFalse(ReceivePolicy.shouldReceive(im, 0, 50 * GB, false, false))
    }

    @Test fun `계정 남은 용량이 설정 용량보다 작아도 교착 없이 받는다`() {
        // 남은 1GB < 여유 3GB -> 실제 문턱이 음수. 대기 파일이 있으면 받아야 비워진다.
        assertTrue(ReceivePolicy.shouldReceive(cfg, 100, 1 * GB, false, false))
        assertTrue(ReceivePolicy.shouldReceive(cfg.copy(thresholdBytes = 100 * GB), 100, 0, false, false))
    }

    @Test fun `목록 주기는 바로 2분, 용량 10분`() {
        assertEquals(120_000L, ReceivePolicy.listIntervalMs(QueueMode.IMMEDIATE))
        assertEquals(600_000L, ReceivePolicy.listIntervalMs(QueueMode.CAPACITY))
    }
}

/** 완료 기준 8(수신 루프 통합) + 9(양쪽 경로 중복) */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriveReceiverTest {
    private lateinit var env: RecvEnv

    @Before fun setUp() { env = RecvEnv() }
    @After fun tearDown() { assertTrue("허용되지 않은 드라이브 삭제: ${env.drive.illegalDeletes().size}", env.drive.illegalDeletes().isEmpty()); env.close() }

    private fun visibleFiles() = env.dir.listFiles()!!.filter { it.isFile }

    private fun addToQueue(seed: Long, size: Int = 500): Pair<String, ByteArray> {
        val b = randomBytes(size, seed)
        val sha = Hashing.sha256Hex(b)
        env.drive.put(sha, "p$seed.jpg", b)
        return sha to b
    }

    @Test
    fun `바로 모드는 큐 파일을 받아 공개하고 사본 표에 붙인다`() = runBlocking {
        env.cfg = ReceiverConfig(QueueMode.IMMEDIATE)
        val (sha, b) = addToQueue(1)
        val r = env.driveRx.runOnce()
        assertEquals(1, r.downloaded)
        assertEquals(RecvState.PUBLISHED, env.ledger.get(sha)!!.stateEnum)
        assertEquals(1, env.ledger.copies(sha).size)
        assertEquals(sha, Hashing.sha256Hex(java.io.File(env.ledger.get(sha)!!.path!!).readBytes()))
        assertEquals(0, env.drive.deletes.size)      // 포토 확인 전에는 지우지 않는다
    }

    @Test
    fun `용량 모드는 문턱 미만이면 안 받고 넘으면 큐를 비울 때까지 받는다`() = runBlocking {
        env.cfg = ReceiverConfig(QueueMode.CAPACITY, thresholdBytes = 1000, reserveBytes = 300)
        env.drive.quota = Quota(limitBytes = 10_000, usageBytes = 5_000)        // 남은 5000 -> 실제 문턱 min(1000, 4700)=1000
        val (s1, _) = addToQueue(1, 600)
        var r = env.driveRx.runOnce()
        assertFalse(r.received)
        assertNull(env.ledger.get(s1))                                           // 문턱 미만이라 안 받음
        assertEquals(0, visibleFiles().size)

        val (s2, _) = addToQueue(2, 600)                                         // 합계 1200 >= 1000
        r = env.driveRx.runOnce()
        assertTrue(r.received)
        assertEquals(2, r.downloaded)
        assertEquals(RecvState.PUBLISHED, env.ledger.get(s1)!!.stateEnum)
        assertEquals(RecvState.PUBLISHED, env.ledger.get(s2)!!.stateEnum)

        // 다 비웠으니 새 작은 파일 하나는 다시 문턱 미만 -> 안 받는다
        val (s3, _) = addToQueue(3, 100)
        r = env.driveRx.runOnce()
        assertFalse(r.received)
        assertNull(env.ledger.get(s3))
    }

    @Test
    fun `비우는 중 표시가 남아 있으면 문턱 미만 대기분도 마저 받는다`() = runBlocking {
        env.cfg = ReceiverConfig(QueueMode.CAPACITY, thresholdBytes = 100_000, reserveBytes = 0)
        env.drive.quota = Quota(1_000_000, 0)
        val (s1, _) = addToQueue(1, 200)
        assertFalse(env.driveRx.runOnce().received)
        env.ledger.putLong(DriveReceiver.KEY_DRAINING, 1)
        assertTrue(env.driveRx.runOnce().received)
        assertEquals(RecvState.PUBLISHED, env.ledger.get(s1)!!.stateEnum)
        assertEquals(0L, env.ledger.getLong(DriveReceiver.KEY_DRAINING))        // 비웠으니 해제
    }

    @Test
    fun `계정 남은 용량이 설정 용량보다 작아도 교착 없이 받는다`() = runBlocking {
        env.cfg = ReceiverConfig(QueueMode.CAPACITY, thresholdBytes = 10_000, reserveBytes = 3_000)
        env.drive.quota = Quota(limitBytes = 5_000, usageBytes = 4_000)         // 남은 1000 < 여유 3000 -> 실제 문턱 음수
        val (s1, _) = addToQueue(1, 400)
        val r = env.driveRx.runOnce()
        assertTrue(r.received)
        assertEquals(RecvState.PUBLISHED, env.ledger.get(s1)!!.stateEnum)
    }

    @Test
    fun `sendNow·drainNow 명령은 모드와 무관하게 큐를 비울 때까지 받고 표시를 해제한다`() = runBlocking {
        env.cfg = ReceiverConfig(QueueMode.CAPACITY, thresholdBytes = 1_000_000, reserveBytes = 0)
        env.drive.quota = Quota(100_000_000, 0)
        val handler = CommandHandler(env.ledger, ReceiverConfigStore(env.meta), env.confirmer, env.clock)
        val (s1, _) = addToQueue(1, 300)
        assertFalse(env.driveRx.runOnce().received)

        assertTrue(handler.handle(RemoteCommand(RemoteCommand.SEND_NOW), remote = true).ok)
        assertTrue(env.driveRx.runOnce().received)
        assertEquals(RecvState.PUBLISHED, env.ledger.get(s1)!!.stateEnum)
        assertEquals(0L, env.ledger.getLong(DriveReceiver.KEY_FORCE))

        val (s2, _) = addToQueue(2, 300)
        assertFalse(env.driveRx.runOnce().received)
        assertTrue(handler.handle(RemoteCommand(RemoteCommand.DRAIN_NOW), remote = true).ok)
        assertTrue(env.driveRx.runOnce().received)
        assertEquals(RecvState.PUBLISHED, env.ledger.get(s2)!!.stateEnum)
    }

    @Test
    fun `명령 처리 setConfig 와 cleanupOpened 의 원격 불가`() = runBlocking {
        val store = ReceiverConfigStore(env.meta)
        val handler = CommandHandler(env.ledger, store, env.confirmer, env.clock)
        val c = ReceiverConfig(QueueMode.CAPACITY, 7 * GB, 2 * GB)
        assertTrue(handler.handle(RemoteCommand(RemoteCommand.SET_CONFIG, config = c), remote = true).ok)
        assertEquals(c, store.get())
        assertFalse(handler.handle(RemoteCommand(RemoteCommand.CLEANUP_OPENED), remote = true).ok)
        assertNull(env.ledger.getLong("cleanupOpenedAt"))
        assertTrue(handler.handle(RemoteCommand(RemoteCommand.CLEANUP_OPENED), remote = false).ok)
        assertEquals(env.clock.nowMs(), env.ledger.getLong("cleanupOpenedAt"))
    }

    @Test
    fun `드라이브 해시 불일치는 3회까지 다시 받고 이후 FAILED, 사본은 남긴다`() = runBlocking {
        env.cfg = ReceiverConfig(QueueMode.IMMEDIATE)
        val good = randomBytes(800, 40)
        val sha = Hashing.sha256Hex(good)
        env.drive.put(sha, "x.jpg", randomBytes(800, 41), declaredSha = sha)    // 내용이 다른 불량 사본
        repeat(3) { env.driveRx.runOnce() }
        val e = env.ledger.get(sha)!!
        assertEquals(3, e.failCount)
        env.driveRx.runOnce()
        assertEquals(RecvState.FAILED, env.ledger.get(sha)!!.stateEnum)
        assertEquals(1, env.drive.files.size)                                   // 드라이브 사본은 남김
        assertEquals(0, visibleFiles().size)
        assertEquals(0, env.drive.deletes.size)
    }

    // ---- 완료 기준 9 ----

    @Test
    fun `같은 sha 가 LAN 과 드라이브 사본 2개로 들어와도 파일은 1개, 사본 표 2개, 포토 확인 뒤 delete 2번`() = runBlocking {
        env.cfg = ReceiverConfig(QueueMode.IMMEDIATE)
        val bytes = randomBytes(1500, 50)
        val sha = env.receiveWhole(bytes, "same.jpg")                           // LAN 으로 먼저
        env.drive.put(sha, "same.jpg", bytes)                                   // 드라이브 사본 2개(두 번 올라옴)
        env.drive.put(sha, "same.jpg", bytes)
        val r = env.driveRx.runOnce()
        assertEquals(0, r.downloaded)                                           // 받지 않는다
        assertEquals(1, visibleFiles().size)
        assertEquals(2, env.ledger.copies(sha).size)
        assertEquals(0, env.drive.deletes.size)

        // 포토 공간 확보 버튼 -> 파일 사라짐 -> 확인
        vanishViaButton(sha)
        assertEquals(RecvState.CLEANED, env.ledger.get(sha)!!.stateEnum)
        assertEquals(2, env.drive.deletes.size)
        assertTrue(env.drive.files.isEmpty())
        assertEquals(0, env.ledger.copies(sha).size)
    }

    @Test
    fun `드라이브로 먼저 받은 sha 가 나중에 LAN 으로 오면 already 이고 파일은 1개`() = runBlocking {
        env.cfg = ReceiverConfig(QueueMode.IMMEDIATE)
        val b = randomBytes(1200, 51)
        val sha = Hashing.sha256Hex(b)
        env.drive.put(sha, "same.jpg", b)
        env.drive.put(sha, "same.jpg", b)
        val r = env.driveRx.runOnce()
        assertEquals(1, r.downloaded)
        assertEquals(1, visibleFiles().size)
        assertEquals(2, env.ledger.copies(sha).size)
        assertEquals(BeginResult.Already, env.core.begin(sha, "same.jpg", b.size.toLong(), 0, "image/jpeg", "lan"))
        assertEquals(1, visibleFiles().size)
    }

    @Test
    fun `이미 포토 확인된 sha 의 뒤늦은 드라이브 사본은 바로 정리된다`() = runBlocking {
        env.cfg = ReceiverConfig(QueueMode.IMMEDIATE)
        val bytes = randomBytes(900, 52)
        val sha = env.receiveWhole(bytes, "late.jpg")
        vanishViaButton(sha)
        assertEquals(RecvState.PHOTOS_CONFIRMED, env.ledger.get(sha)!!.stateEnum)   // 사본이 없어 CLEANED 가 아님
        env.drive.put(sha, "late.jpg", bytes)
        env.driveRx.runOnce()
        assertEquals(1, env.drive.deletes.size)
        assertEquals(RecvState.CLEANED, env.ledger.get(sha)!!.stateEnum)
    }

    /** 버튼 기록 -> 30분 뒤 파일·MediaStore 에서 사라짐 -> 1분 재확인 */
    private suspend fun vanishViaButton(sha: String) {
        env.confirmer.recordButton()
        env.clock.advanceMin(30)
        val e = env.ledger.get(sha)!!
        java.io.File(e.path!!).delete(); env.index.removePath(e.path!!)
        env.confirmer.check()
        env.clock.advance(61_000)
        env.confirmer.check()
    }
}
