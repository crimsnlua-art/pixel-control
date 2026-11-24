package com.pixelrelay

import com.pixelrelay.core.Hashing
import com.pixelrelay.core.Hmac
import com.pixelrelay.core.InMemoryPairedKeys
import com.pixelrelay.core.PairResult
import com.pixelrelay.core.PairingManager
import com.pixelrelay.core.RequestVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest

/** 완료 기준 1: 해시 */
class HashTest {
    @Test
    fun `같은 내용은 같은 SHA-256 이고 다른 내용은 다르다`() {
        val a = randomBytes(5000, 1)
        assertEquals(Hashing.sha256Hex(ByteArrayInputStream(a)), Hashing.sha256Hex(a.copyOf()))
        assertNotEquals(Hashing.sha256Hex(a), Hashing.sha256Hex(randomBytes(5000, 2)))
        // 알려진 값: 빈 입력
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Hashing.sha256Hex(ByteArray(0)))
    }

    @Test
    fun `100MB 스트리밍 해시는 한 번에 읽은 결과와 같다`() {
        val size = 100 * 1024 * 1024
        val all = randomBytes(size, 7)
        val oneShot = Hashing.hex(MessageDigest.getInstance("SHA-256").digest(all))
        // 작은 조각으로만 돌려주는 스트림(스트리밍 확인)
        val chunky = object : InputStream() {
            private var pos = 0
            override fun read(): Int = if (pos < all.size) all[pos++].toInt() and 0xFF else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= all.size) return -1
                val n = minOf(len, 37_001, all.size - pos)
                System.arraycopy(all, pos, b, off, n); pos += n
                return n
            }
        }
        assertEquals(oneShot, Hashing.sha256Hex(chunky, 64 * 1024))
    }
}

/** 완료 기준 2: HMAC */
class HmacTest {
    private val clock = FakeClock()
    private val key = randomBytes(32, 3)
    private val verifier = RequestVerifier(clock, { listOf(key) })

    private val empty = Hashing.sha256Hex(ByteArray(0))
    private fun v(m: String, p: String, t: String?, s: String?, body: String = empty) = verifier.verify(m, p, t, s, body)
    private fun sig(k: ByteArray, m: String, p: String, t: Long, body: String = empty) = Hmac.sign(k, m, p, t, body)

    @Test
    fun `올바른 서명만 통과한다`() {
        val t = clock.nowMs()
        assertTrue(v("GET", "/status", t.toString(), sig(key, "GET", "/status", t)))
    }

    @Test
    fun `시각이 5분 밖이면 거절한다`() {
        val t = clock.nowMs() - 5 * 60_000L - 1
        assertFalse(v("GET", "/status", t.toString(), sig(key, "GET", "/status", t)))
        val t2 = clock.nowMs() + 5 * 60_000L + 1
        assertFalse(v("GET", "/status", t2.toString(), sig(key, "GET", "/status", t2)))
        // 경계 안쪽은 통과
        val ok = clock.nowMs() - 5 * 60_000L
        assertTrue(v("GET", "/status", ok.toString(), sig(key, "GET", "/status", ok)))
    }

    @Test
    fun `다른 키, 바뀐 경로, 바뀐 메서드, 바뀐 쿼리는 거절한다`() {
        val t = clock.nowMs()
        assertFalse(v("GET", "/status", t.toString(), sig(randomBytes(32, 99), "GET", "/status", t)))
        assertFalse(v("GET", "/ledger", t.toString(), sig(key, "GET", "/status", t)))
        assertFalse(v("POST", "/status", t.toString(), sig(key, "GET", "/status", t)))
        assertFalse(v("GET", "/ledger?since=1", t.toString(), sig(key, "GET", "/ledger?since=0", t)))
        assertFalse(v("GET", "/status", null, null))
        assertFalse(v("GET", "/status", "abc", "zz"))
    }

    @Test
    fun `본문을 바꿔치기하면 거절한다(서명이 본문 SHA-256 을 덮는다)`() {
        val t = clock.nowMs()
        val bodyA = Hashing.sha256Hex("{\"type\":\"sendNow\"}".toByteArray())
        val bodyB = Hashing.sha256Hex("{\"type\":\"confirmVanished\",\"sha\":\"x\"}".toByteArray())
        val s = sig(key, "POST", "/command", t, bodyA)
        assertTrue(v("POST", "/command", t.toString(), s, bodyA))
        assertFalse(v("POST", "/command", t.toString(), s, bodyB))
        // PUT 조각은 "-" 로 서명: 본문이 달라도 통과(무결성은 commit 의 전체 해시), 그러나 해시 자리와 섞이면 거절
        val sp = sig(key, "PUT", "/files/a?offset=0", t, "-")
        assertTrue(v("PUT", "/files/a?offset=0", t.toString(), sp, "-"))
        assertFalse(v("PUT", "/files/a?offset=0", t.toString(), sp, empty))
    }

    @Test
    fun `서명 시각을 바꿔치기해도 거절한다`() {
        val t = clock.nowMs()
        assertFalse(v("GET", "/status", (t + 1000).toString(), sig(key, "GET", "/status", t)))
    }
}

/** 완료 기준 3: 짝짓기 코드 */
class PairingTest {
    private val clock = FakeClock()
    private fun mgr() = PairingManager(clock, InMemoryPairedKeys())

    @Test
    fun `코드는 5분이 지나면 무효`() {
        val m = mgr(); val c = m.newCode()
        clock.advanceMin(5); clock.advance(1)
        assertEquals(PairResult.Invalid, m.redeem(c, "폰"))
        val c2 = m.newCode()
        clock.advanceMin(4)
        assertTrue(m.redeem(c2, "폰") is PairResult.Ok)
    }

    @Test
    fun `코드는 1회용`() {
        val m = mgr(); val c = m.newCode()
        val r = m.redeem(c, "폰") as PairResult.Ok
        assertEquals(32, java.util.Base64.getDecoder().decode(r.keyBase64).size)
        assertEquals(PairResult.Invalid, m.redeem(c, "폰2"))
    }

    @Test
    fun `5회 틀리면 10분 잠기고 잠금 동안은 맞는 코드도 거절한다`() {
        val m = mgr(); val c = m.newCode()
        val wrong = if (c == "000000") "111111" else "000000"
        repeat(4) { assertEquals(PairResult.Invalid, m.redeem(wrong, "x")) }
        assertTrue(m.redeem(wrong, "x") is PairResult.Locked)
        assertTrue(m.redeem(c, "x") is PairResult.Locked)
        clock.advanceMin(9)
        assertTrue(m.redeem(c, "x") is PairResult.Locked)
        clock.advanceMin(2)
        // 잠금이 풀리면(코드 유효시간 5분은 지났으므로 새 코드가 필요)
        val c2 = m.newCode()
        assertTrue(m.redeem(c2, "x") is PairResult.Ok)
    }

    @Test
    fun `성공하면 틀린 횟수가 초기화된다`() {
        val m = mgr()
        var c = m.newCode()
        val wrong = if (c == "000000") "111111" else "000000"
        repeat(4) { m.redeem(wrong, "x") }
        assertTrue(m.redeem(c, "x") is PairResult.Ok)
        c = m.newCode()
        repeat(4) { assertEquals(PairResult.Invalid, m.redeem(wrong, "x")) }
    }
}
