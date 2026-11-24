package com.pixelrelay.core

import java.security.SecureRandom
import java.util.Base64

/** 받는 쪽이 기억하는 짝지은 키 목록. */
interface PairedKeys {
    fun add(deviceName: String, key: ByteArray)
    fun all(): List<ByteArray>
}

class InMemoryPairedKeys : PairedKeys {
    private val list = ArrayList<ByteArray>()
    override fun add(deviceName: String, key: ByteArray) { list.add(key) }
    override fun all(): List<ByteArray> = list.toList()
}

sealed class PairResult {
    data class Ok(val keyBase64: String) : PairResult()
    object Invalid : PairResult()
    data class Locked(val untilMs: Long) : PairResult()
}

/**
 * 짝짓기 코드: 6자리, 5분 유효, 1회용, 5회 틀리면 10분 잠금 (설계서 5절).
 */
class PairingManager(
    private val clock: Clock,
    private val keys: PairedKeys,
    private val random: SecureRandom = SecureRandom(),
) {
    companion object {
        const val CODE_TTL_MS = 5 * 60_000L
        const val MAX_WRONG = 5
        const val LOCK_MS = 10 * 60_000L
    }

    private var code: String? = null
    private var codeAt: Long = 0
    private var wrong = 0
    private var lockedUntil = 0L

    @Synchronized
    fun newCode(): String {
        val c = String.format("%06d", random.nextInt(1_000_000))
        code = c
        codeAt = clock.nowMs()
        return c
    }

    @Synchronized
    fun currentCode(): String? = code?.takeIf { clock.nowMs() - codeAt <= CODE_TTL_MS }

    @Synchronized
    fun lockedUntilMs(): Long = if (clock.nowMs() < lockedUntil) lockedUntil else 0L

    @Synchronized
    fun redeem(input: String, deviceName: String): PairResult {
        val now = clock.nowMs()
        if (now < lockedUntil) return PairResult.Locked(lockedUntil)
        if (lockedUntil != 0L) { lockedUntil = 0L; wrong = 0 }
        val c = code
        val valid = c != null && now - codeAt <= CODE_TTL_MS && c == input.trim()
        if (!valid) {
            wrong++
            if (wrong >= MAX_WRONG) {
                lockedUntil = now + LOCK_MS
                return PairResult.Locked(lockedUntil)
            }
            return PairResult.Invalid
        }
        code = null // 1회용
        wrong = 0
        val key = ByteArray(32).also { random.nextBytes(it) }
        keys.add(deviceName, key)
        return PairResult.Ok(Base64.getEncoder().encodeToString(key))
    }
}
