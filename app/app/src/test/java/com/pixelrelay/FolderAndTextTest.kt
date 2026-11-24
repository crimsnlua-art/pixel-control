package com.pixelrelay

import androidx.test.core.app.ApplicationProvider
import com.pixelrelay.core.BackfillDates
import com.pixelrelay.core.BackfillMode
import com.pixelrelay.core.DriveUse
import com.pixelrelay.core.FolderPolicy
import com.pixelrelay.core.FolderSelection
import com.pixelrelay.core.FolderVerdict
import com.pixelrelay.core.SenderSettings
import com.pixelrelay.core.StatusWarning
import com.pixelrelay.core.WarnCode
import com.pixelrelay.sender.FailureText
import com.pixelrelay.sender.PixelHttpException
import com.pixelrelay.ui.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.time.ZoneId

/** 폴더 체크 목록 <-> 규칙 변환, 하위 폴더 규칙 */
class FolderRulesTest {
    private val defaults = SenderSettings()

    @Test
    fun `기본 설정 판정 - 카메라만 보내고 스크린샷 등은 보내지 않는다`() {
        val w = defaults.watchFolders; val x = defaults.excludeFolders
        assertEquals(FolderVerdict.SEND, FolderPolicy.verdict("DCIM/Camera/", w, x))
        assertEquals(FolderVerdict.SEND, FolderPolicy.verdict("DCIM/Camera/Sub/", w, x))        // 하위 폴더 포함(기존 동작)
        assertEquals(FolderVerdict.OUT, FolderPolicy.verdict("Pictures/Screenshots/", w, x))
        assertEquals(FolderVerdict.OUT, FolderPolicy.verdict("DCIM/", w, x))
        assertEquals(FolderVerdict.EXCLUDED, FolderPolicy.verdict("DCIM/Camera/Screenshots/", w, x))
        assertEquals(FolderVerdict.EXCLUDED, FolderPolicy.verdict("DCIM/Camera/KakaoTalk/x/", w, x))   // 이름 하나는 어느 깊이든
        assertEquals(FolderVerdict.SEND, FolderPolicy.verdict("dcim/camera", w, x))              // 대소문자·슬래시 무시
    }

    @Test
    fun `기존 감시 폴더 + 제외 이름 설정이 목록에서 같은 체크 상태로 보인다`() {
        val counts = mapOf(
            "DCIM/Camera/" to 900, "Pictures/Screenshots/" to 40, "Download/" to 7, "Pictures/KakaoTalk/" to 12,
            "DCIM/Camera/Sub/" to 3, "Pictures/PixelRelay/" to 5,
        )
        val rows = FolderSelection.rows(counts, defaults.watchFolders, defaults.excludeFolders)
        val byPath = rows.associateBy { it.path }
        assertTrue(byPath["DCIM/Camera"]!!.checked)
        assertTrue(byPath["DCIM/Camera/Sub"]!!.checked)
        assertFalse(byPath["Pictures/Screenshots"]!!.checked)
        assertFalse(byPath["Download"]!!.checked)
        assertFalse(byPath["Pictures/KakaoTalk"]!!.checked)
        assertFalse(byPath["Pictures/PixelRelay"]!!.checked)
        assertEquals(900, byPath["DCIM/Camera"]!!.count)
        assertEquals("DCIM/Camera", rows.first().path)              // 체크된 것 먼저, 많은 것 먼저
    }

    @Test
    fun `저장된 감시 폴더가 목록에 없으면 0개로 덧붙여 잃지 않는다`() {
        val rows = FolderSelection.rows(mapOf("Pictures/A/" to 2), listOf("DCIM/Camera"), emptyList())
        assertTrue(rows.any { it.path == "DCIM/Camera" && it.count == 0 && it.checked })
    }

    private fun roundTrip(all: List<String>, checked: Set<String>) {
        val (watch, exclude) = FolderSelection.toRules(all, checked)
        for (p in all) {
            val v = FolderPolicy.verdict(p, watch, exclude)
            assertEquals("$p watch=$watch exclude=$exclude", p in checked, v == FolderVerdict.SEND)
        }
    }

    @Test
    fun `체크 목록을 규칙으로 바꿔도 다시 판정하면 같은 결과가 된다`() {
        val all = listOf("DCIM/Camera", "DCIM/Camera/Sub", "DCIM/Camera/Trip", "Pictures", "Pictures/Screenshots", "Pictures/KakaoTalk", "Download")
        roundTrip(all, setOf("DCIM/Camera", "DCIM/Camera/Sub", "DCIM/Camera/Trip"))
        roundTrip(all, setOf("DCIM/Camera", "DCIM/Camera/Trip"))                  // Sub 만 뺌
        roundTrip(all, setOf("Pictures", "Download"))                              // 하위 Screenshots·KakaoTalk 은 제외로
        roundTrip(all, setOf("Pictures/Screenshots"))
        roundTrip(all, emptySet())
        // 안쪽 폴더를 다시 체크하는 경우(바깥 체크 > 중간 해제 > 안쪽 체크)
        roundTrip(listOf("A/B", "A/B/C", "A/B/C/D", "A/B/C/E"), setOf("A/B", "A/B/C/D"))
    }

    @Test
    fun `체크한 폴더의 체크 안 한 하위 폴더는 제외 규칙이 된다`() {
        val (watch, exclude) = FolderSelection.toRules(listOf("DCIM/Camera", "DCIM/Camera/Sub", "Download"), setOf("DCIM/Camera"))
        assertEquals(listOf("DCIM/Camera"), watch)
        assertEquals(listOf("DCIM/Camera/Sub"), exclude)
    }

    @Test
    fun `경로 제외 규칙은 이름이 비슷한 다른 폴더를 건드리지 않는다`() {
        val w = listOf("DCIM"); val x = listOf("DCIM/Cam")
        assertEquals(FolderVerdict.EXCLUDED, FolderPolicy.verdict("DCIM/Cam/", w, x))
        assertEquals(FolderVerdict.EXCLUDED, FolderPolicy.verdict("DCIM/Cam/Sub/", w, x))
        assertEquals(FolderVerdict.SEND, FolderPolicy.verdict("DCIM/Camera/", w, x))
        assertEquals(FolderVerdict.SEND, FolderPolicy.verdict("DCIM/Camera2/", w, x))
    }

    @Test
    fun `날짜 선택 변환은 기기 시간대의 그 날 자정이다`() {
        val seoul = ZoneId.of("Asia/Seoul")
        val pickerMs = java.time.LocalDate.of(2026, 3, 5).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val sec = BackfillDates.startOfDaySec(pickerMs, seoul)
        assertEquals(java.time.LocalDate.of(2026, 3, 5).atStartOfDay(seoul).toEpochSecond(), sec)
        assertEquals(pickerMs, BackfillDates.toPickerMs(sec, seoul))
        assertEquals("2026-03-05", BackfillDates.label(sec, seoul))
    }
}

/** 드라이브 연결 안 함이면 빨간 드라이브 경고를 숨긴다 */
class DriveUseTest {
    private val auth = StatusWarning(WarnCode.DRIVE_AUTH, 1)
    private val low = StatusWarning(WarnCode.LOW_SPACE, 2)

    @Test
    fun `드라이브를 안 쓰면 픽셀의 DRIVE_AUTH 경고만 숨긴다`() {
        assertEquals(listOf(low), DriveUse.visibleWarnings(listOf(auth, low), driveUsed = false))
        assertEquals(listOf(auth, low), DriveUse.visibleWarnings(listOf(auth, low), driveUsed = true))
    }

    @Test
    fun `홈 경고와 회색 안내`() {
        assertEquals("구글 드라이브 연결이 필요합니다(집 밖 전송 중지).", DriveUse.homeAuthWarning(driveUsed = true, authProblem = true))
        assertNull(DriveUse.homeAuthWarning(driveUsed = false, authProblem = true))
        assertNull(DriveUse.homeAuthWarning(driveUsed = true, authProblem = false))
        assertTrue(DriveUse.skippedNote(false)!!.contains("집 와이파이"))
        assertNull(DriveUse.skippedNote(true))
    }
}

/** 영어 예외 문구 대신 원인별 한글 */
class FailureTextTest {
    @Test
    fun `짝짓기 - 로컬 네트워크 권한 없음과 응답 없음을 구분한다`() {
        val e = ConnectException("failed to connect to /192.168.0.5 (port 47321) from /192.168.0.9 (port 40000) after 4000ms")
        val noPerm = FailureText.pairing(e, localNetGranted = false)
        assertTrue(noPerm, noPerm.contains("로컬 네트워크 권한"))
        assertTrue(noPerm.contains("원문"))
        val noResp = FailureText.pairing(SocketTimeoutException("timeout"), localNetGranted = true)
        assertTrue(noResp, noResp.contains("응답하지 않습니다") && noResp.contains("같은 와이파이"))
    }

    @Test
    fun `짝짓기 - 코드 틀림 잠금 그 밖`() {
        assertTrue(FailureText.pairing(PixelHttpException(401, "invalid"), true).contains("틀렸거나 만료"))
        assertTrue(FailureText.pairing(PixelHttpException(429, "locked"), true).contains("잠겼"))
        assertTrue(FailureText.pairing(PixelHttpException(500, "x"), true).contains("500"))
        assertEquals("픽셀을 찾지 못했습니다. IP 를 직접 입력하세요", FailureText.pairing(IllegalStateException("픽셀을 찾지 못했습니다. IP 를 직접 입력하세요"), true))
    }

    @Test
    fun `전송 오류와 장부 사유 글도 한글로`() {
        assertTrue(FailureText.korean(PixelHttpException(503, "hot"), true).contains("뜨거워"))
        assertTrue(FailureText.korean(PixelHttpException(401, "x"), true).contains("시각"))
        assertTrue(FailureText.korean(SocketTimeoutException("x"), false).contains("로컬 네트워크 권한"))
        assertTrue(FailureText.displayReason("예외: failed to connect to /1.2.3.4 after 4000ms").contains("응답하지 않습니다"))
        assertEquals("픽셀 해시 불일치", FailureText.displayReason("픽셀 해시 불일치"))
        assertTrue(FailureText.displayReason("java.lang.Whatever: boom").startsWith("전송 오류"))
    }
}

/** 기존에 저장된 설정(감시·제외 폴더 문자열, 기준 시각 없음)이 그대로 읽힌다 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsCompatTest {
    @Test
    fun `기존 저장값은 그대로 읽히고 기존 사진 기준은 기본 안 보냄`() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SettingsStore(ctx)
        store.saveSender(SenderSettings(watchFolders = listOf("DCIM/Camera", "Pictures/Mine"), excludeFolders = listOf("Screenshots", "Download")))
        val s = store.sender()
        assertEquals(listOf("DCIM/Camera", "Pictures/Mine"), s.watchFolders)
        assertEquals(listOf("Screenshots", "Download"), s.excludeFolders)
        assertNull(s.sendFromSec)
        assertEquals(BackfillMode.NONE, s.backfillMode)
        assertEquals(s.roleSetAtSec, s.scanFromSec)

        store.setSendFrom(0L)
        assertEquals(BackfillMode.ALL, store.sender().backfillMode)
        store.setSendFrom(1_700_000_000L)
        assertEquals(1_700_000_000L, store.sender().scanFromSec)
        store.saveSender(store.sender().copy(photoOverMobile = false))      // 설정 저장이 기준 시각을 지우지 않는다
        assertEquals(1_700_000_000L, store.sender().sendFromSec)
        store.setSendFrom(null)
        assertNull(store.sender().sendFromSec)
    }
}
