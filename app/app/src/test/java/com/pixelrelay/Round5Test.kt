package com.pixelrelay

import com.pixelrelay.core.CleanupKeys
import com.pixelrelay.core.CleanupResult
import com.pixelrelay.core.CleanupSession
import com.pixelrelay.core.PhotosOpen
import com.pixelrelay.core.RecvState
import com.pixelrelay.receiver.CleanupDevice
import com.pixelrelay.receiver.CleanupFlow
import com.pixelrelay.receiver.CleanupTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** 재검토 반영: 클릭·취소·결과 저장이 한 직렬 경계에서 일어난다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CleanupSerializationTest {
    private lateinit var env: RecvEnv

    private class Dev(var screen: Boolean = true) : CleanupDevice {
        var opened = 0; var notified = 0
        override fun accessibilityOn() = true
        override fun screenOn() = screen
        override fun openPhotos(): PhotosOpen { opened++; return PhotosOpen.LINK }
        override fun notifyManual() { notified++ }
    }

    @Before fun setUp() { env = RecvEnv(); CleanupSession.reset() }
    @After fun tearDown() { env.close(); CleanupSession.reset() }

    private fun flow(dev: Dev, onSleep: () -> Unit = {}) =
        CleanupFlow(env.ledger, env.confirmer, env.clock, dev, CoroutineScope(Job()), sleep = { env.clock.advance(it); onSleep() })

    private suspend fun result() = env.ledger.getString(CleanupKeys.RESULT)

    // 1. 취소가 끝난 뒤에는 절대 클릭하지 않는다
    @Test
    fun `취소된 요청으로는 클릭 함수가 불리지 않는다`() = runBlocking {
        val dev = Dev()
        val f = flow(dev)
        val run = f.start(CleanupTrigger.REMOTE)!!
        dev.screen = false
        assertNull(f.start(CleanupTrigger.AUTO))                                           // 알림 전환 = 취소
        val clicks = AtomicInteger()
        assertFalse(f.performPress(run.id) { clicks.incrementAndGet(); true })
        assertEquals(0, clicks.get())
    }

    @Test
    fun `다른 요청 식별자로는 클릭하지 못하고 같은 요청에서는 한 번만 클릭한다`() = runBlocking {
        val f = flow(Dev())
        val run = f.start(CleanupTrigger.UI)!!
        val clicks = AtomicInteger()
        assertFalse(f.performPress(run.id + 1000) { clicks.incrementAndGet(); true })
        assertTrue(f.performPress(run.id) { clicks.incrementAndGet(); true })
        assertFalse(f.performPress(run.id) { clicks.incrementAndGet(); true })
        assertEquals(1, clicks.get())
        f.watch(run)
        assertEquals(CleanupResult.PRESSED, result())
    }

    @Test
    fun `클릭에서 예외가 나거나 false 면 CLICK_FAILED`() = runBlocking {
        for (mode in listOf("throw", "false")) {
            CleanupSession.reset()
            val f = flow(Dev())
            val run = f.start(CleanupTrigger.UI)!!
            f.performPress(run.id) { if (mode == "throw") throw IllegalStateException("터짐") else false }
            f.watch(run)
            assertEquals(mode, CleanupResult.CLICK_FAILED, result())
        }
    }

    @Test
    fun `동시에 여러 곳에서 클릭을 요청해도 한 번만 클릭된다`() = runBlocking {
        val f = flow(Dev())
        val run = f.start(CleanupTrigger.UI)!!
        val clicks = AtomicInteger()
        val rs = (1..8).map { async(kotlinx.coroutines.Dispatchers.Default) { f.performPress(run.id) { clicks.incrementAndGet(); true } } }.awaitAll()
        assertEquals(1, rs.count { it }); assertEquals(1, clicks.get())
    }

    // 2. 오래된 요청은 기록·알림 금지
    @Test
    fun `대체된 오래된 요청은 결과도 알림도 남기지 않는다`() = runBlocking {
        val dev = Dev()
        val f = flow(dev)
        val old = f.start(CleanupTrigger.REMOTE)!!
        CleanupSession.cancel()
        val fresh = f.start(CleanupTrigger.UI)!!
        env.clock.advance(20_000)                                                          // 오래된 요청이었다면 15초 알림 전환 시점
        f.watch(old)
        assertEquals(0, dev.notified)
        assertNull(result()); assertNull(env.ledger.getLong(CleanupKeys.RESULT_AT))
        assertTrue(CleanupSession.isCurrent(fresh.id))
    }

    @Test
    fun `결과 코드와 시각은 함께 저장된다`() = runBlocking {
        val f = flow(Dev()) { svcPress(env.clock.nowMs()) }
        f.watch(f.start(CleanupTrigger.UI)!!)
        assertEquals(CleanupResult.PRESSED, result())
        assertTrue((env.ledger.getLong(CleanupKeys.RESULT_AT) ?: 0L) > 0L)
    }

    // 3. 클릭 직후 결과 저장 전에도 합친다
    @Test
    fun `클릭이 끝났어도 결과 기록이 끝나기 전에 들어온 실행은 합친다`() = runBlocking {
        val dev = Dev()
        val f = flow(dev)
        val run = f.start(CleanupTrigger.REMOTE)!!
        assertTrue(f.performPress(run.id) { true })                                       // 눌렀음 - 아직 settle 전
        val again = f.start(CleanupTrigger.AUTO)!!
        assertTrue(again.merged); assertEquals(run.id, again.id); assertEquals(1, dev.opened)
        f.watch(run)                                                                       // 결과 기록 완료
        assertEquals(CleanupResult.PRESSED, result())
        val next = f.start(CleanupTrigger.AUTO)!!                                          // 기록이 끝난 뒤에는 새로 시작
        assertFalse(next.merged); assertEquals(2, dev.opened)
    }

    // 4. missingSince 가 있으면 사라진 시각 - 후보 시각 차이로 판단
    @Test
    fun `재확인이 끝나기 전에 정리를 다시 눌러도 후보를 보존해 정상 삭제로 확정된다`() = runBlocking {
        val sha = env.receiveWhole(randomBytes(700, 1800), "r.jpg")
        env.confirmer.recordButton()
        val cand = env.ledger.get(sha)!!.candidateAt
        env.clock.advanceMin(50)
        val e = env.ledger.get(sha)!!
        File(e.path!!).delete(); env.index.removePath(e.path!!)
        env.confirmer.check()                                                              // 사라짐을 처음 봄: 후보로부터 50분
        assertNotNull(env.ledger.get(sha)!!.missingSince)
        env.clock.advanceMin(15)                                                           // 지금 기준으로는 후보가 65분 전(옛 방식이면 지워짐)
        env.confirmer.recordButton()
        assertEquals(cand, env.ledger.get(sha)!!.candidateAt)
        assertEquals(1, env.confirmer.check().confirmed)                                   // 사라진 시각 - 후보 = 50분 < 1시간
        assertEquals(RecvState.PHOTOS_CONFIRMED, env.ledger.get(sha)!!.stateEnum)
    }

    @Test
    fun `사라진 시각이 후보로부터 1시간을 넘으면 보존해도 원인 불명`() = runBlocking {
        val sha = env.receiveWhole(randomBytes(700, 1801), "s.jpg")
        env.confirmer.recordButton()
        env.clock.advanceMin(70)
        val e = env.ledger.get(sha)!!
        File(e.path!!).delete(); env.index.removePath(e.path!!)
        env.confirmer.check()
        env.clock.advance(61_000)
        env.confirmer.recordButton()
        assertEquals(1, env.confirmer.check().vanishedUnknown)
        assertEquals(RecvState.VANISHED_UNKNOWN, env.ledger.get(sha)!!.stateEnum)
    }
}
