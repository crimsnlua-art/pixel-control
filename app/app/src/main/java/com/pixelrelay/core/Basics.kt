package com.pixelrelay.core

import java.io.InputStream
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

const val KB = 1024L
const val MB = 1024L * KB
const val GB = 1024L * MB

/** 시계 경계. 테스트에서는 가짜 시계를 쓴다. */
interface Clock {
    fun nowMs(): Long
}

object SystemClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

/** 현재 네트워크 상태 한 장면. */
data class NetSnapshot(
    val connected: Boolean,
    /** 비과금(UNMETERED) 와이파이 여부 */
    val unmeteredWifi: Boolean,
    val roaming: Boolean,
) {
    val metered: Boolean get() = connected && !unmeteredWifi
}

/** 네트워크 경계. */
interface NetworkState {
    fun snapshot(): NetSnapshot
}

object Hashing {
    private const val DIGITS = "0123456789abcdef"

    fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(DIGITS[(b.toInt() shr 4) and 0xF])
            sb.append(DIGITS[b.toInt() and 0xF])
        }
        return sb.toString()
    }

    /** 원본 바이트 SHA-256 (소문자 hex). 스트리밍으로 읽는다. */
    fun sha256Hex(input: InputStream, bufferSize: Int = 1 shl 20): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(bufferSize)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return hex(md.digest())
    }

    fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
}

/** 요청 서명. 서명 대상 = METHOD 줄바꿈 path?query 줄바꿈 time (설계서 5절). */
object Hmac {
    /** bodySha: 본문 SHA-256 hex. PUT 조각만 "-" (무결성은 commit 의 전체 해시). */
    fun sign(key: ByteArray, method: String, pathQuery: String, timeMs: Long, bodySha: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val msg = "${method.uppercase()}\n$pathQuery\n$timeMs\n$bodySha"
        return Hashing.hex(mac.doFinal(msg.toByteArray(Charsets.UTF_8)))
    }
}

/** 받는 쪽의 서명 검증. 등록된 키 중 하나로 맞으면 통과. 시각 차 ±5분 초과는 거절. */
class RequestVerifier(
    private val clock: Clock,
    private val keys: () -> List<ByteArray>,
    private val toleranceMs: Long = 5 * 60_000L,
) {
    fun verify(method: String, pathQuery: String, timeHeader: String?, sigHeader: String?, bodySha: String): Boolean {
        val t = timeHeader?.trim()?.toLongOrNull() ?: return false
        val sig = sigHeader?.trim()?.lowercase() ?: return false
        if (kotlin.math.abs(clock.nowMs() - t) > toleranceMs) return false
        for (k in keys()) {
            val expect = Hmac.sign(k, method, pathQuery, t, bodySha)
            if (MessageDigest.isEqual(expect.toByteArray(), sig.toByteArray())) return true
        }
        return false
    }
}
