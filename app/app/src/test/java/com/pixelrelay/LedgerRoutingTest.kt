package com.pixelrelay

import com.pixelrelay.core.GB
import com.pixelrelay.core.Hashing
import com.pixelrelay.core.IllegalTransition
import com.pixelrelay.core.NetSnapshot
import com.pixelrelay.core.Reason
import com.pixelrelay.core.RecvState
import com.pixelrelay.core.RemoteCommand
import com.pixelrelay.core.SendState
import com.pixelrelay.core.SenderLedger
import com.pixelrelay.core.SenderSettings
import com.pixelrelay.core.Transitions
import com.pixelrelay.sender.PlanInput
import com.pixelrelay.sender.Route
import com.pixelrelay.sender.RoutePlanner
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 완료 기준 6: 장부 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LedgerTest {
    private val dbs = ArrayList<com.pixelrelay.core.db.AppDb>()
    @After fun tearDown() { dbs.forEach { runCatching { it.close() } } }

    private fun expectIllegal(f: suspend () -> Unit) {
        try { runBlocking { f() }; fail("허용되지 않은 전이가 통과했다") } catch (e: IllegalTransition) { /* 기대대로 */ }
    }

    @Test
    fun `허용된 전이만 통과한다(LAN 경로와 드라이브 경로)`() = runBlocking {
        val db = TestDb.memory().also { dbs += it }
        val l = SenderLedger(db, FakeClock())
        l.register(mkEntry(sha = "1".repeat(64)))
        l.transition("1".repeat(64), SendState.SENDING_LAN)
        l.transition("1".repeat(64), SendState.RECEIVED)
        l.transition("1".repeat(64), SendState.PHOTOS_CONFIRMED)

        l.register(mkEntry(sha = "2".repeat(64)))
        l.transition("2".repeat(64), SendState.UPLOADING_DRIVE)
        l.transition("2".repeat(64), SendState.IN_DRIVE)
        l.transition("2".repeat(64), SendState.RECEIVED)
        l.transition("2".repeat(64), SendState.VANISHED_UNKNOWN)
        l.transition("2".repeat(64), SendState.PHOTOS_CONFIRMED)     // 사용자가 확정
        Unit
    }

    @Test
    fun `허용되지 않은 전이는 예외`() {
        val db = TestDb.memory().also { dbs += it }
        val l = SenderLedger(db, FakeClock())
        val a = "a".repeat(64); val b = "b".repeat(64); val c = "c".repeat(64); val d = "d".repeat(64)
        runBlocking {
            l.register(mkEntry(sha = a, state = "RECEIVED"))
            l.register(mkEntry(sha = b, state = "IN_DRIVE"))
            l.register(mkEntry(sha = c, state = "PHOTOS_CONFIRMED"))
            l.register(mkEntry(sha = d, state = "VANISHED_UNKNOWN"))
        }
        expectIllegal { l.transition(a, SendState.WAITING) }           // 도착한 것은 다시 대기로 못 감
        expectIllegal { l.transition(a, SendState.SENDING_LAN) }
        expectIllegal { l.transition(b, SendState.SENDING_LAN) }       // IN_DRIVE 는 LAN 으로 다시 안 감
        expectIllegal { l.transition(b, SendState.WAITING) }
        expectIllegal { l.transition(c, SendState.RECEIVED) }          // 포토 확인은 끝
        expectIllegal { l.transition(d, SendState.WAITING) }           // 다시 보내기는 resendVanished 만
        expectIllegal { l.transition("e".repeat(64), SendState.RECEIVED) }  // 없는 항목
    }

    @Test
    fun `전이표 전체를 훑어 도착 이후 상태에서는 어떤 대기 상태로도 못 돌아간다`() {
        val after = setOf(SendState.RECEIVED, SendState.PHOTOS_CONFIRMED, SendState.VANISHED_UNKNOWN)
        val backward = setOf(SendState.WAITING, SendState.SENDING_LAN, SendState.UPLOADING_DRIVE, SendState.IN_DRIVE)
        for (f in after) for (t in backward) assertFalse("$f -> $t", Transitions.allowed(f, t))
        assertFalse(Transitions.allowed(SendState.IN_DRIVE, SendState.SENDING_LAN))
        assertFalse(Transitions.allowed(SendState.PHOTOS_CONFIRMED, SendState.VANISHED_UNKNOWN))
        // 받기 장부
        assertTrue(Transitions.allowed(RecvState.RECEIVING, RecvState.PUBLISHED))
        assertFalse(Transitions.allowed(RecvState.PUBLISHED, RecvState.RECEIVING))
        assertFalse(Transitions.allowed(RecvState.CLEANED, RecvState.PHOTOS_CONFIRMED))
    }

    @Test
    fun `한 번 오른 sha 는 새 DB 인스턴스에서도 자동 재전송 대상이 아니다`() = runBlocking {
        val clock = FakeClock()
        val bytes = randomBytes(3000, 21)
        val sha = Hashing.sha256Hex(bytes)

        // 1) 첫 DB: 발견 -> 장부 등록 -> 도착
        var db = TestDb.file("restart.db").also { dbs += it }
        var env = SendEnv(db, clock)
        env.source.add("a.jpg", bytes)
        env.registerNow()
        assertEquals(SendState.WAITING, env.ledger.get(sha)!!.stateEnum)
        env.ledger.transition(sha, SendState.SENDING_LAN)
        env.ledger.transition(sha, SendState.RECEIVED)
        db.close()

        // 2) 재시작: 새 DB 인스턴스
        db = TestDb.file("restart.db").also { dbs += it }
        env = SendEnv(db, clock)
        env.source.add("a.jpg", bytes)                    // 같은 내용이 다시 발견돼도
        env.registerNow()
        val e = env.ledger.get(sha)!!
        assertEquals(SendState.RECEIVED, e.stateEnum)
        assertTrue(env.ledger.sendable().none { it.sha == sha })
        assertFalse(env.ledger.register(mkEntry(sha = sha)))   // 다시 등록 불가
        assertEquals(1, db.send().all().count { it.sha == sha })
    }

    @Test
    fun `원인 불명의 다시 보내기만 예외로 대기로 돌아간다`() = runBlocking {
        val db = TestDb.memory().also { dbs += it }
        val l = SenderLedger(db, FakeClock())
        val s = "9".repeat(64)
        l.register(mkEntry(sha = s, state = "VANISHED_UNKNOWN"))
        assertTrue(l.sendable().isEmpty())
        // 요청만으로는 보내지 않는다(RESEND_PENDING). 픽셀 확인(resendConfirmed) 뒤에만 대기로
        assertEquals("cmd-1", l.requestResend(s, "cmd-1")!!.resendCommandId)
        assertEquals(SendState.RESEND_PENDING, l.get(s)!!.stateEnum)
        assertTrue(l.sendable().isEmpty())
        assertTrue(l.resendConfirmed(s))
        assertEquals(SendState.WAITING, l.get(s)!!.stateEnum)
        assertEquals(1, l.sendable().size)
        // 다른 상태에서는 다시 보내기가 먹지 않는다
        val r = "8".repeat(64)
        l.register(mkEntry(sha = r, state = "RECEIVED"))
        assertNull(l.requestResend(r))
        assertFalse(l.resendConfirmed(r))
        assertEquals(SendState.RECEIVED, l.get(r)!!.stateEnum)
    }

    @Test
    fun `실패는 시도 횟수와 간격을 쌓고 20회에 FAILED`() = runBlocking {
        val clock = FakeClock()
        val db = TestDb.memory().also { dbs += it }
        val l = SenderLedger(db, clock)
        val s = "7".repeat(64)
        l.register(mkEntry(sha = s))
        val delays = ArrayList<Long>()
        repeat(5) {
            val before = clock.nowMs()
            val e = l.recordFailure(s, "시험")!!
            delays += e.nextAttemptAt - before
        }
        assertEquals(listOf(60_000L, 300_000L, 1_800_000L, 7_200_000L, 21_600_000L), delays)
        repeat(15) { l.recordFailure(s, "시험") }
        assertEquals(SendState.FAILED, l.get(s)!!.stateEnum)
        assertTrue(l.sendable().isEmpty())
    }
}

/** 완료 기준 7: 경로 판단 (순수 함수) */
class RoutePlannerTest {
    private val wifiHome = NetSnapshot(connected = true, unmeteredWifi = true, roaming = false)
    private val mobile = NetSnapshot(connected = true, unmeteredWifi = false, roaming = false)
    private val roaming = NetSnapshot(connected = true, unmeteredWifi = false, roaming = true)

    private fun plan(
        e: com.pixelrelay.core.db.SendEntry = mkEntry(), net: NetSnapshot = wifiHome, lan: Boolean = false,
        s: SenderSettings = SenderSettings(), used: Long = 0, q: Long? = 0, rem: Long? = 100 * GB, force: Boolean = false,
    ) = RoutePlanner.decide(PlanInput(e, net, lan, s, used, q, rem, force))

    @Test fun `비과금 와이파이 + 서명된 status 성공이면 LAN`() = assertEquals(Route.Lan, plan(lan = true))

    @Test fun `와이파이여도 픽셀이 안 보이면 드라이브(데이터 한도 적용 안 함)`() {
        assertEquals(Route.Drive, plan(lan = false))
        assertEquals(Route.Drive, plan(e = mkEntry(video = true), lan = false, used = 99 * GB))
    }

    @Test fun `모바일 데이터 사진은 드라이브`() = assertEquals(Route.Drive, plan(net = mobile))

    @Test fun `영상 + 과금망 + 설정 꺼짐이면 대기`() {
        val v = mkEntry(video = true, size = 50)
        assertEquals(Route.Wait(Reason.NETWORK), plan(e = v, net = mobile))
        assertEquals(Route.Drive, plan(e = v, net = mobile, s = SenderSettings(videoOverMobile = true)))
        assertEquals(Route.Drive, plan(e = v, net = mobile, force = true))         // 지금 보내기는 데이터 조건 무시
    }

    @Test fun `로밍이면 대기(지금 보내기여도)`() {
        assertEquals(Route.Wait(Reason.NETWORK), plan(net = roaming))
        assertEquals(Route.Wait(Reason.NETWORK), plan(net = roaming, force = true))
    }

    @Test fun `하루 한도를 넘기면 대기`() {
        val s = SenderSettings(dailyLimitBytes = 2 * GB)
        assertEquals(Route.Wait(Reason.DAILY_LIMIT), plan(e = mkEntry(size = 600L * 1024 * 1024), net = mobile, s = s, used = 1500L * 1024 * 1024))
        assertEquals(Route.Drive, plan(e = mkEntry(size = 100), net = mobile, s = s, used = 1000))
        assertEquals(Route.Drive, plan(e = mkEntry(size = 600L * 1024 * 1024), net = mobile, s = s, used = 1500L * 1024 * 1024, force = true))
    }

    @Test fun `queue 더하기 크기가 남은 용량 빼기 2GB 를 넘으면 드라이브용량 대기 + drain 요청`() {
        val e = mkEntry(size = 1000)
        val r = plan(e = e, net = mobile, q = 3 * GB, rem = 5 * GB - 500) as Route.Wait    // 3GB+1000 > 3GB-500
        assertEquals(Reason.DRIVE_QUOTA, r.reason)
        assertTrue(r.registerDrain)
        // 경계: 정확히 같으면 통과
        assertEquals(Route.Drive, plan(e = e, net = mobile, q = 3 * GB - 1000, rem = 5 * GB))
    }

    @Test fun `IN_DRIVE 는 LAN 으로 다시 보내지 않는다`() {
        assertEquals(Route.NothingToSend, plan(e = mkEntry(state = "IN_DRIVE"), lan = true))
        assertEquals(Route.NothingToSend, plan(e = mkEntry(state = "IN_DRIVE"), net = wifiHome, lan = false))
    }

    @Test fun `드라이브 업로드 중이면 집에 와도 드라이브로 마저 끝낸다`() =
        assertEquals(Route.Drive, plan(e = mkEntry(state = "UPLOADING_DRIVE"), lan = true))

    @Test fun `연결이 없으면 대기`() =
        assertEquals(Route.Wait(Reason.NETWORK), plan(net = NetSnapshot(false, false, false)))
}

/** 완료 기준 7 의 나머지: 엔진 단위(용량 부족 때 drainNow 자동 등록 1시간 1회, SENDING_LAN 끊김 처리) */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EngineRoutingTest {
    private val dbs = ArrayList<com.pixelrelay.core.db.AppDb>()
    @After fun tearDown() { dbs.forEach { runCatching { it.close() } } }

    private fun env(): SendEnv = SendEnv(TestDb.memory().also { dbs += it })

    @Test
    fun `드라이브 용량 부족이면 WAITING 드라이브용량 이고 drainNow 는 1시간에 한 번만 등록`() = runBlocking {
        val e = env()
        e.net.snap = NetSnapshot(true, false, false)             // 모바일
        e.drive.quota = com.pixelrelay.drive.Quota(2 * GB + 100, 0)   // 남은 − 2GB = 100 바이트
        val bytes = randomBytes(3000, 31)
        e.source.add("a.jpg", bytes)
        e.registerNow()

        e.engine.runOnce()
        val entry = e.entryFor(bytes)!!
        assertEquals(SendState.WAITING, entry.stateEnum)
        assertEquals(Reason.DRIVE_QUOTA, entry.reason)
        assertEquals(1, e.drive.createdCommands.size)
        assertEquals(RemoteCommand.DRAIN_NOW, JSONObject(e.drive.createdCommands[0]).getString("type"))
        assertTrue(e.drive.files.isEmpty())                       // 업로드는 하지 않음

        e.clock.advanceMin(30)
        e.engine.runOnce()
        assertEquals(1, e.drive.createdCommands.size)            // 1시간 안에는 다시 등록 안 함
        e.clock.advanceMin(31)
        e.engine.runOnce()
        assertEquals(2, e.drive.createdCommands.size)
    }

    @Test
    fun `LAN 이 끊기면 WAITING 네트워크조건 으로 돌아가고 이어서 드라이브로 가지 않고 offset 은 픽셀이 기억`() = runBlocking {
        val recv = RecvEnv()
        try {
            val e = env()
            val api = CoreBackedPixelApi(recv).also { it.failPutAfterCalls = 1 }
            e.lanApi = api
            val bytes = randomBytes(20_000, 32)
            e.source.add("a.jpg", bytes)
            e.registerNow()
            e.engine.runOnce()
            val en = e.entryFor(bytes)!!
            assertEquals(SendState.WAITING, en.stateEnum)
            assertEquals(Reason.NETWORK, en.reason)
            val sha = Hashing.sha256Hex(bytes)
            assertEquals(4096L, recv.sink.partialLength(sha))        // 첫 조각만 도착

            // LAN 복구 -> 이어서
            api.down = false; api.failPutAfterCalls = -1
            e.clock.advance(60_000)
            e.engine.runOnce()
            assertEquals(SendState.RECEIVED, e.ledger.get(sha)!!.stateEnum)
            assertEquals(RecvState.PUBLISHED, recv.ledger.get(sha)!!.stateEnum)
            assertTrue(e.drive.files.isEmpty())
        } finally { recv.close() }
    }
}
