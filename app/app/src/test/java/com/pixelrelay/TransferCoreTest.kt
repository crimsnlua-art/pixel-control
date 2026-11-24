package com.pixelrelay

import com.pixelrelay.core.GB
import com.pixelrelay.core.Hashing
import com.pixelrelay.core.RecvState
import com.pixelrelay.receiver.BeginResult
import com.pixelrelay.receiver.CommitOutcome
import com.pixelrelay.receiver.PutResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

/** 완료 기준 4: 이어받기 / 잘못된 offset / 해시 불일치, 5: 공간·고온 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransferCoreTest {
    private lateinit var env: RecvEnv

    @Before fun setUp() { env = RecvEnv() }
    @After fun tearDown() { env.close() }

    @Test
    fun `조각 중간에 끊고 다시 begin 하면 resume 이고 끝까지 받은 결과는 원본과 같다`() = runBlocking {
        val data = randomBytes(50_000, 5)
        val sha = Hashing.sha256Hex(data)
        val b1 = env.core.begin(sha, "a.jpg", data.size.toLong(), 5000, "image/jpeg", "lan") as BeginResult.Ok
        assertFalse(b1.resume); assertEquals(0L, b1.offset)
        // 20000 바이트만 보내고 끊김
        val p = env.core.put(sha, "lan", 0, ByteArrayInputStream(data, 0, 20_000), 20_000)
        assertEquals(PutResult.Ok(20_000), p)

        val b2 = env.core.begin(sha, "a.jpg", data.size.toLong(), 5000, "image/jpeg", "lan") as BeginResult.Ok
        assertTrue(b2.resume); assertEquals(20_000L, b2.offset)
        env.core.put(sha, "lan", 20_000, ByteArrayInputStream(data, 20_000, 30_000), 30_000)
        assertEquals(CommitOutcome.Published, env.core.commit(sha, "lan"))

        val e = env.ledger.get(sha)!!
        assertEquals(RecvState.PUBLISHED, e.stateEnum)
        assertEquals(sha, Hashing.sha256Hex(File(e.path!!).readBytes()))
        assertTrue(Math.abs(File(e.path!!).lastModified() - 5000L) <= 2000)      // setLastModified(takenAt)
        assertEquals(e.mediaId, env.index.byPath[e.path!!])       // scanFile 결과 기록
        assertFalse(File(env.dir, ".partial/$sha.part").exists())
    }

    @Test
    fun `잘못된 offset 은 현재 offset 과 함께 거절한다`() = runBlocking {
        val data = randomBytes(10_000, 6)
        val sha = Hashing.sha256Hex(data)
        env.core.begin(sha, "a.jpg", data.size.toLong(), 0, "image/jpeg", "lan")
        env.core.put(sha, "lan", 0, ByteArrayInputStream(data, 0, 4000), 4000)
        assertEquals(PutResult.Conflict(4000), env.core.put(sha, "lan", 0, ByteArrayInputStream(data, 0, 1000), 1000))
        assertEquals(PutResult.Conflict(4000), env.core.put(sha, "lan", 9000, ByteArrayInputStream(data, 0, 1000), 1000))
        // 거절돼도 파일은 그대로
        assertEquals(4000L, env.sink.partialLength(sha))
    }

    @Test
    fun `해시가 다른 commit 은 거절하고 부분 파일을 지운다`() = runBlocking {
        val real = randomBytes(10_000, 7)
        val fake = randomBytes(10_000, 8)
        val sha = Hashing.sha256Hex(real)
        env.core.begin(sha, "a.jpg", real.size.toLong(), 0, "image/jpeg", "lan")
        env.core.put(sha, "lan", 0, ByteArrayInputStream(fake), fake.size.toLong())
        assertEquals(CommitOutcome.Mismatch, env.core.commit(sha, "lan"))
        assertFalse(File(env.dir, ".partial/$sha.part").exists())
        assertEquals(0L, env.sink.partialLength(sha))
        assertEquals(0, env.dir.listFiles()!!.count { it.isFile })   // 공개된 파일 없음
        assertEquals(RecvState.RECEIVING, env.ledger.get(sha)!!.stateEnum)
    }

    @Test
    fun `이미 공개된 sha 는 already 이고 다시 받지 않는다`() = runBlocking {
        val data = randomBytes(2000, 9)
        val sha = env.receiveWhole(data, "a.jpg")
        assertEquals(BeginResult.Already, env.core.begin(sha, "a.jpg", 2000, 0, "image/jpeg", "lan"))
        assertEquals(BeginResult.Already, env.core.begin(sha, "a.jpg", 2000, 0, "image/jpeg", "drive"))
    }

    @Test
    fun `같은 이름이면 이름_sha앞8자리 로 공개한다`() = runBlocking {
        val a = randomBytes(1000, 10); val b = randomBytes(1000, 11)
        val s1 = env.receiveWhole(a, "IMG.jpg"); val s2 = env.receiveWhole(b, "IMG.jpg")
        assertEquals("IMG.jpg", File(env.ledger.get(s1)!!.path!!).name)
        assertEquals("IMG_${s2.take(8)}.jpg", File(env.ledger.get(s2)!!.path!!).name)
    }

    @Test
    fun `같은 sha 를 다른 경로가 받는 중이면 busy`() = runBlocking {
        val data = randomBytes(3000, 13)
        val sha = Hashing.sha256Hex(data)
        assertTrue(env.core.begin(sha, "a.jpg", 3000, 0, "image/jpeg", "lan") is BeginResult.Ok)
        assertEquals(BeginResult.Busy, env.core.begin(sha, "a.jpg", 3000, 0, "image/jpeg", "drive"))
        // 임대(2분)가 지나면 넘겨받을 수 있다
        env.clock.advanceMin(3)
        assertTrue(env.core.begin(sha, "a.jpg", 3000, 0, "image/jpeg", "drive") is BeginResult.Ok)
    }

    // ---- 완료 기준 5 ----

    @Test
    fun `남은 공간이 크기 더하기 4GB 보다 작으면 507(NoSpace)`() = runBlocking {
        val size = 10L * GB
        val sha = "a".repeat(64)
        env.free = size + 4 * GB - 1
        val r = env.core.begin(sha, "big.mp4", size, 0, "video/mp4", "lan")
        assertTrue(r is BeginResult.NoSpace)
        assertFalse((r as BeginResult.NoSpace).permanent)
        env.free = size + 4 * GB
        assertTrue(env.core.begin(sha, "big.mp4", size, 0, "video/mp4", "lan") is BeginResult.Ok)
    }

    @Test
    fun `저장소 전체 용량으로도 못 받는 크기는 영구 불가로 건너뜀 처리`() = runBlocking {
        val size = 10L * GB
        val sha = "b".repeat(64)
        env.total = 12L * GB            // 12 - 4 = 8GB < 10GB
        env.free = 5L * GB
        val r = env.core.begin(sha, "huge.mp4", size, 0, "video/mp4", "lan") as BeginResult.NoSpace
        assertTrue(r.permanent)
        assertEquals(RecvState.SKIPPED, env.ledger.get(sha)!!.stateEnum)
    }

    @Test
    fun `고온 중지 중에는 begin·put 모두 503(Hot)`() = runBlocking {
        val data = randomBytes(1000, 12)
        val sha = Hashing.sha256Hex(data)
        env.core.begin(sha, "a.jpg", 1000, 0, "image/jpeg", "lan")
        env.hot = true
        assertEquals(BeginResult.Hot, env.core.begin(sha, "a.jpg", 1000, 0, "image/jpeg", "lan"))
        assertEquals(PutResult.Hot, env.core.put(sha, "lan", 0, ByteArrayInputStream(data), 1000))
        env.hot = false
        assertTrue(env.core.begin(sha, "a.jpg", 1000, 0, "image/jpeg", "lan") is BeginResult.Ok)
    }
}
