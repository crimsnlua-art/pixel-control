package com.pixelrelay

import com.pixelrelay.core.Hashing
import com.pixelrelay.core.UpdateException
import com.pixelrelay.core.UpdateInfo
import com.pixelrelay.core.UpdateRules
import com.pixelrelay.core.WarnCode
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
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

/** 앱 안 업데이트: version.json 검증, 버전 비교, 내려받은 파일 검증, 주소 제한 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateTest {
    private val sha = "a".repeat(64)
    private fun json(code: String = "2", name: String = "\"0.2.0\"", sha: String = "\"${this.sha}\"", size: String = "1000", notes: String = "\"고침\"") =
        """{"versionCode":$code,"versionName":$name,"sha256":$sha,"size":$size,"notes":$notes}"""

    @Test
    fun `정상 version json 을 읽는다`() {
        val i = UpdateRules.parse(json())!!
        assertEquals(2, i.versionCode); assertEquals("0.2.0", i.versionName); assertEquals(sha, i.sha256)
        assertEquals(1000L, i.size); assertEquals("고침", i.notes)
        // 대문자 hex 는 소문자로 정규화
        assertEquals(sha, UpdateRules.parse(json(sha = "\"${sha.uppercase()}\""))!!.sha256)
    }

    @Test
    fun `잘못된 입력은 거부한다`() {
        assertNull(UpdateRules.parse("not json"))
        assertNull(UpdateRules.parse("{}"))
        assertNull(UpdateRules.parse(json(code = "\"2\"")))                    // 숫자가 아님
        assertNull(UpdateRules.parse(json(code = "0")))
        assertNull(UpdateRules.parse(json(code = "-3")))
        assertNull(UpdateRules.parse(json(code = "2.5")))
        assertNull(UpdateRules.parse(json(name = "\"\"")))
        assertNull(UpdateRules.parse(json(name = "\"0.2.0; rm -rf\"")))
        assertNull(UpdateRules.parse(json(sha = "\"abc\"")))                    // 길이 틀림
        assertNull(UpdateRules.parse(json(sha = "\"${"g".repeat(64)}\"")))      // hex 아님
        assertNull(UpdateRules.parse(json(size = "0")))
        assertNull(UpdateRules.parse(json(size = "-1")))
        assertNull(UpdateRules.parse(json(size = "${UpdateRules.MAX_APK_BYTES + 1}")))   // 200MB 초과
        assertNull(UpdateRules.parse(json(size = "\"1000\"")))
        assertNull(UpdateRules.parse(json(notes = "\"${"가".repeat(2001)}\"")))
        assertNotNull(UpdateRules.parse(json(size = "${UpdateRules.MAX_APK_BYTES}")))
    }

    @Test
    fun `버전 비교 - 큰 때만 업데이트이고 같거나 낮으면 없다`() {
        val i = UpdateRules.parse(json(code = "5"))!!
        assertTrue(UpdateRules.isNewer(i, 4))
        assertFalse(UpdateRules.isNewer(i, 5))      // 같은 버전
        assertFalse(UpdateRules.isNewer(i, 6))
    }

    private fun info(bytes: ByteArray) = UpdateInfo(3, "0.3.0", Hashing.sha256Hex(ByteArrayInputStream(bytes)), bytes.size.toLong(), "")
    private fun tmp(): File = Files.createTempDirectory("pr_upd").toFile().resolve("update/pixelrelay.apk")

    @Test
    fun `내려받은 파일이 맞으면 저장되고 크기나 sha 가 다르면 지우고 한글 오류`() {
        val b = randomBytes(5000, 7)
        val ok = tmp()
        UpdateRules.saveAndVerify(ByteArrayInputStream(b), ok, info(b))
        assertTrue(ok.exists()); assertEquals(5000L, ok.length())

        // sha 불일치(같은 크기의 다른 내용)
        val other = randomBytes(5000, 8)
        val bad = tmp()
        try { UpdateRules.saveAndVerify(ByteArrayInputStream(other), bad, info(b)); fail() } catch (e: UpdateException) {
            assertTrue(e.message, e.message!!.contains("SHA-256"))
        }
        assertFalse(bad.exists())

        // 크기 부족
        val shortF = tmp()
        try { UpdateRules.saveAndVerify(ByteArrayInputStream(b.copyOf(4000)), shortF, info(b)); fail() } catch (e: UpdateException) {
            assertTrue(e.message!!.contains("크기"))
        }
        assertFalse(shortF.exists())

        // 예상보다 큰 스트림은 읽다가 중단
        val big = tmp()
        try { UpdateRules.saveAndVerify(ByteArrayInputStream(b + b), big, info(b)); fail() } catch (e: UpdateException) {
            assertTrue(e.message!!.contains("큽니다"))
        }
        assertFalse(big.exists())
    }

    @Test
    fun `외부 주소는 GitHub https 만 허용한다`() {
        assertTrue(UpdateRules.allowedFinalUrl("https", "github.com"))
        assertTrue(UpdateRules.allowedFinalUrl("https", "objects.githubusercontent.com"))
        assertTrue(UpdateRules.allowedFinalUrl("https", "release-assets.githubusercontent.com"))
        assertFalse(UpdateRules.allowedFinalUrl("http", "github.com"))
        assertFalse(UpdateRules.allowedFinalUrl("https", "evil.com"))
        assertFalse(UpdateRules.allowedFinalUrl("https", "github.com.evil.com"))
        assertFalse(UpdateRules.allowedFinalUrl("https", "notgithub.com"))
        assertTrue(UpdateRules.VERSION_URL.startsWith("https://github.com/crimsnlua-art/pixel-control/releases/latest/download/"))
        assertTrue(UpdateRules.APK_URL.endsWith("/pixelrelay.apk"))
    }

    @Test
    fun `포토 신호 없음 경고는 확인할 곳까지 알려 준다`() {
        val t = WarnCode.korean(WarnCode.PHOTOS_STALLED)
        assertTrue(t, t.contains("6시간") && t.contains("구글 포토 로그인"))
    }

    @Test
    fun `리디렉션은 요청 전에 검사한다 - https 와 허용 호스트만`() {
        val cur = UpdateRules.VERSION_URL
        assertTrue(UpdateRules.isAllowedUrl(cur))
        assertEquals("https://objects.githubusercontent.com/a/b", UpdateRules.resolveRedirect(cur, "https://objects.githubusercontent.com/a/b"))
        assertEquals("https://github.com/x/y", UpdateRules.resolveRedirect(cur, "/x/y"))                       // 상대 경로
        assertNull(UpdateRules.resolveRedirect(cur, "http://github.com/x"))                                  // https -> http 금지
        assertNull(UpdateRules.resolveRedirect(cur, "http://objects.githubusercontent.com/x"))
        assertNull(UpdateRules.resolveRedirect(cur, "https://evil.com/x"))
        assertNull(UpdateRules.resolveRedirect(cur, "https://github.com.evil.com/x"))
        assertNull(UpdateRules.resolveRedirect(cur, "https://user:pw@github.com/x"))                         // 사용자 정보 금지
        assertNull(UpdateRules.resolveRedirect(cur, "https://github.com:8443/x"))                            // 기본 포트만
        assertNull(UpdateRules.resolveRedirect(cur, ""))
        assertNull(UpdateRules.resolveRedirect(cur, null))
        assertFalse(UpdateRules.isAllowedUrl("ftp://github.com/x"))
        assertFalse(UpdateRules.isAllowedUrl("not a url"))
        assertEquals(5, UpdateRules.MAX_REDIRECTS)
    }
}
