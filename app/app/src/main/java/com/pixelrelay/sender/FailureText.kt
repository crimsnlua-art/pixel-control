package com.pixelrelay.sender

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** 연결·전송 실패를 영어 예외 문구 대신 원인별 한글로 바꾼다(순수 함수). */
object FailureText {
    private const val NO_RESPONSE = "픽셀이 응답하지 않습니다. 두 폰이 같은 와이파이인지, 픽셀 앱이 켜져 있는지 확인하세요."
    private const val NO_LOCAL_PERM = "로컬 네트워크 권한이 없어 픽셀에 연결하지 못했습니다. '연결' 탭의 권한에서 로컬 네트워크를 허용하세요."

    private fun isConnectFailure(e: Throwable): Boolean {
        var c: Throwable? = e
        while (c != null) {
            if (c is ConnectException || c is SocketTimeoutException || c is NoRouteToHostException) return true
            val m = c.message?.lowercase().orEmpty()
            if ("failed to connect" in m || "timeout" in m || "timed out" in m || "unreachable" in m || "eperm" in m) return true
            c = c.cause?.takeIf { it !== c }
        }
        return false
    }

    private fun withRaw(korean: String, e: Throwable): String {
        val raw = e.message.orEmpty().trim()
        val hasLatin = raw.any { it in 'a'..'z' || it in 'A'..'Z' }
        return if (hasLatin) "$korean (원문: ${raw.take(120)})" else korean
    }

    /** 짝짓기 실패 문구. localNetGranted = 로컬 네트워크 권한이 허용돼 있는가(없는 기기는 true 로 본다). */
    fun pairing(e: Throwable, localNetGranted: Boolean): String {
        if (e is PixelHttpException) {
            return when (e.code) {
                401 -> "코드가 틀렸거나 만료되었습니다. 픽셀 화면의 새 6자리 코드를 입력하세요."
                429 -> "코드를 5회 틀려 픽셀이 10분 동안 잠겼습니다. 잠시 뒤 다시 시도하세요."
                else -> withRaw("픽셀이 오류로 답했습니다(${e.code}).", e)
            }
        }
        if (e is UnknownHostException) return withRaw("픽셀 주소를 찾을 수 없습니다. IP 주소를 확인하세요.", e)
        if (isConnectFailure(e)) return withRaw(if (!localNetGranted) NO_LOCAL_PERM else NO_RESPONSE, e)
        val m = e.message.orEmpty()
        if (m.any { it in '가'..'힣' }) return m   // 이미 한글 문구
        return withRaw("짝짓기에 실패했습니다.", e)
    }

    /** 전송·명령 실패 문구(서명된 요청). */
    fun korean(e: Throwable, localNetGranted: Boolean = true): String {
        if (e is PixelHttpException) {
            return when (e.code) {
                401 -> "픽셀이 인증을 거절했습니다. 두 폰의 시각을 확인하거나 짝짓기를 다시 하세요."
                503 -> "픽셀이 뜨거워 받기를 멈췄습니다. 식은 뒤 다시 시도됩니다."
                507 -> "픽셀 저장 공간이 부족합니다. 포토 공간 확보 뒤 자동으로 이어 보냅니다."
                else -> withRaw("픽셀이 오류로 답했습니다(${e.code}).", e)
            }
        }
        if (e is UnknownHostException) return withRaw("픽셀 주소를 찾을 수 없습니다.", e)
        if (isConnectFailure(e)) return withRaw(if (!localNetGranted) NO_LOCAL_PERM else NO_RESPONSE, e)
        val m = e.message.orEmpty()
        if (m.any { it in '가'..'힣' }) return m   // 이미 한글 문구
        return withRaw("알 수 없는 오류", e)
    }

    /** 장부에 남은 사유 글(영어 예외 문구가 섞였을 수 있음)을 화면용으로 바꾼다. */
    fun displayReason(raw: String): String {
        val low = raw.lowercase()
        return when {
            "failed to connect" in low || "timeout" in low || "timed out" in low -> NO_RESPONSE
            "unreachable" in low || "econnrefused" in low || "connection refused" in low -> NO_RESPONSE
            "connection reset" in low || "broken pipe" in low || "unexpected end of stream" in low -> "연결이 끊겼습니다. 자동으로 다시 시도합니다."
            raw.any { it in 'a'..'z' || it in 'A'..'Z' } && !raw.any { it in '가'..'힣' } -> "전송 오류 (원문: ${raw.take(120)})"
            else -> raw
        }
    }
}
