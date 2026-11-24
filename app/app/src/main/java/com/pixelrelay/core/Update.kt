package com.pixelrelay.core

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** 릴리스의 version.json 한 건. */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val sha256: String,
    val size: Long,
    val notes: String,
)

/** 사용자에게 보여 줄 한글 오류. */
class UpdateException(msg: String) : Exception(msg)

/** 앱 안 업데이트 규칙(순수 로직): 주소·입력 검증·버전 비교·내려받은 파일 검증. */
object UpdateRules {
    /** 외부 주소는 이 고정 주소(GitHub 공개 저장소 최신 릴리스)만 쓴다. */
    const val VERSION_URL = "https://github.com/crimsnlua-art/pixel-control/releases/latest/download/version.json"
    const val APK_URL = "https://github.com/crimsnlua-art/pixel-control/releases/latest/download/pixelrelay.apk"
    const val MAX_APK_BYTES = 200L * 1024 * 1024
    const val MAX_JSON_BYTES = 64 * 1024
    private const val MAX_NOTES = 2000
    private val NAME_OK = Regex("^[0-9A-Za-z._+-]{1,32}$")
    private val HEX64 = Regex("^[0-9a-fA-F]{64}$")

    /** 리디렉션을 따라간 뒤의 최종 주소도 GitHub(https)여야 한다. */
    fun allowedFinalUrl(scheme: String, host: String): Boolean {
        if (scheme != "https") return false
        val h = host.lowercase()
        return h == "github.com" || h.endsWith(".github.com") || h.endsWith(".githubusercontent.com")
    }

    const val MAX_REDIRECTS = 5

    /** 요청하기 전에 확인하는 주소 검사: https · 허용 호스트 · 사용자 정보 없음 · 기본 포트만. */
    fun isAllowedUrl(url: String): Boolean {
        val u = try { java.net.URI(url) } catch (e: Exception) { return false }
        if (u.userInfo != null) return false
        if (u.port != -1 && u.port != 443) return false
        return allowedFinalUrl(u.scheme ?: return false, u.host ?: return false)
    }

    /** 리디렉션 Location 을 현재 주소 기준으로 풀어 검사한다. 허용되지 않으면(https→http 포함) null. */
    fun resolveRedirect(current: String, location: String?): String? {
        if (location.isNullOrBlank()) return null
        val next = try { java.net.URI(current).resolve(location.trim()).toString() } catch (e: Exception) { return null }
        return next.takeIf { isAllowedUrl(it) }
    }

    /** version.json 파싱·검증. 잘못된 입력이면 null. */
    fun parse(raw: String): UpdateInfo? {
        if (raw.length > MAX_JSON_BYTES) return null
        return try {
            val o = JSONObject(raw)
            val code = o.opt("versionCode")
            val size = o.opt("size")
            if (code !is Int || size !is Number) return null
            val name = o.optString("versionName", "")
            val sha = o.optString("sha256", "")
            val notes = o.optString("notes", "")
            if (code < 1 || code > 100_000_000) return null
            if (!NAME_OK.matches(name)) return null
            if (!HEX64.matches(sha)) return null
            val sz = size.toLong()
            if (size.toDouble() != sz.toDouble() || sz < 1 || sz > MAX_APK_BYTES) return null
            if (notes.length > MAX_NOTES) return null
            UpdateInfo(code, name, sha.lowercase(), sz, notes)
        } catch (e: Exception) {
            null
        }
    }

    fun isNewer(info: UpdateInfo, currentCode: Int): Boolean = info.versionCode > currentCode

    /** 내려받은 바이트 수·SHA-256 확인. 맞으면 null, 아니면 한글 오류 문구. */
    fun verify(info: UpdateInfo, actualSize: Long, actualSha: String): String? = when {
        actualSize != info.size -> "내려받은 파일 크기가 다릅니다(받은 ${actualSize}바이트, 예상 ${info.size}바이트). 다시 시도하세요."
        !actualSha.equals(info.sha256, ignoreCase = true) -> "내려받은 파일의 SHA-256 이 맞지 않습니다. 파일을 지웠으니 다시 시도하세요."
        else -> null
    }

    /**
     * 스트림을 dest 로 저장하며 크기 상한을 지키고, 끝나면 크기·SHA-256 을 확인한다.
     * 어긋나면 파일을 지우고 UpdateException(한글).
     */
    fun saveAndVerify(input: InputStream, dest: File, info: UpdateInfo) {
        dest.parentFile?.mkdirs()
        val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            dest.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > info.size || total > MAX_APK_BYTES) throw UpdateException("내려받은 파일이 예상보다 큽니다. 파일을 지웠으니 다시 시도하세요.")
                    md.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
        } catch (e: Exception) {
            dest.delete()
            if (e is UpdateException) throw e
            throw UpdateException("내려받는 중 오류가 났습니다. 네트워크를 확인하고 다시 시도하세요.")
        }
        val err = verify(info, total, Hashing.hex(md.digest()))
        if (err != null) { dest.delete(); throw UpdateException(err) }
    }
}
