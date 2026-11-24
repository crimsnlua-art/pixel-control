package com.pixelrelay

import com.pixelrelay.core.AccessibilityCheck
import com.pixelrelay.core.AutoCleanPolicy
import com.pixelrelay.core.BackfillMode
import com.pixelrelay.core.CleanupButton
import com.pixelrelay.core.CleanupGate
import com.pixelrelay.core.CleanupKeys
import com.pixelrelay.core.CleanupResult
import com.pixelrelay.core.CleanupScreenCheck
import com.pixelrelay.core.CleanupSession
import com.pixelrelay.core.CleanupText
import com.pixelrelay.core.DriveUse
import com.pixelrelay.core.GB
import com.pixelrelay.core.Hashing
import com.pixelrelay.core.HomeText
import com.pixelrelay.core.InMemoryPairedKeys
import com.pixelrelay.core.PairingManager
import com.pixelrelay.core.PixelStatus
import com.pixelrelay.core.ReceiverConfig
import com.pixelrelay.core.RecvState
import com.pixelrelay.core.RemoteCommand
import com.pixelrelay.core.RequestVerifier
import com.pixelrelay.core.StatusCounts
import com.pixelrelay.receiver.CleanupDevice
import com.pixelrelay.receiver.CleanupFlow
import com.pixelrelay.receiver.CleanupTrigger
import com.pixelrelay.receiver.CommandHandler
import com.pixelrelay.receiver.CommandResult
import com.pixelrelay.receiver.LanServer
import com.pixelrelay.receiver.PhotosConfirmer
import com.pixelrelay.receiver.ReceiverConfigStore
import com.pixelrelay.sender.ApiBegin
import com.pixelrelay.sender.HttpPixelApi
import com.pixelrelay.sender.LanSender
import com.pixelrelay.sender.PixelHttpException
import com.pixelrelay.sender.PixelPairing
import com.pixelrelay.sender.SendResult
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 순수 함수 시험: 접근성 버튼 고르기·화면 안전 판정·자동 실행 조건·문구. */
class CleanupPureTest {
    private fun node(t: String?, clickable: Boolean = true) = CleanupButton.Node(t, clickable)

    @Test
    fun `눌러야 하는 문구 - 여유 공간 으로 시작하고 확보 로 끝남`() {
        listOf(
            "여유 공간 14.78GB 확보", "여유 공간 780MB 확보", "여유 공간 확보", "  여유 공간   2.3GB   확보 ", "여유 공간 1.02TB 확보",
        ).forEach { assertTrue(it, CleanupButton.matches(it)) }
    }

    @Test
    fun `누르면 안 되는 문구 - 구매 삭제 휴지통 등`() {
        listOf(
            "저장용량 구매", "Google One 구독", "삭제", "휴지통으로 이동", "기기에서 삭제", "여유 공간 확보하기", "여유 공간을 확보하세요",
            "기기 저장용량의 83%를 사용 중입니다. 여유 공간을 확보하세요", "이 기기의 여유 공간 확보 중", "여유 공간", "확보",
            "여유 공간 구매 확보", "여유 공간 삭제 확보", "취소", "", "   ",
        ).forEach { assertFalse(it, CleanupButton.matches(it)) }
        assertFalse(CleanupButton.matches(null))
        assertFalse(CleanupButton.matches("여유 공간 " + "가".repeat(60) + " 확보"))   // 너무 긴 문장
    }

    @Test
    fun `후보 중 클릭 가능하고 문구가 맞는 하나만 고른다 - 없거나 둘 이상이면 아무것도 안 누른다`() {
        assertEquals(1, CleanupButton.pick(listOf(node("삭제"), node("여유 공간 14.78GB 확보"), node("저장용량 구매"))))
        assertNull(CleanupButton.pick(listOf(node("삭제"), node("저장용량 구매"), node("휴지통"))))
        assertNull(CleanupButton.pick(listOf(node("여유 공간 14.78GB 확보", clickable = false))))   // 클릭 불가
        assertNull(CleanupButton.pick(listOf(node("여유 공간 1GB 확보"), node("여유 공간 2GB 확보"))))    // 애매함
        assertNull(CleanupButton.pick(emptyList()))
        assertEquals(0, CleanupButton.pick(listOf(node("여유 공간 1GB 확보"), node("여유 공간 2GB 확보", clickable = false))))
    }

    // ---- 안전 조건: 화면 문구 ----
    @Test
    fun `눌러도 되는 화면 - 백업 확인 문구가 있고 문제 문구가 없다`() {
        listOf(
            listOf("이미 안전하게 백업되었습니다", "여유 공간 14.78GB 확보"),
            listOf("기기 저장용량의 83%를 사용 중입니다. 여유 공간을 확보하세요", "14.78GB 항목이 이미 안전하게 백업되었습니다", "여유 공간 14.78GB 확보"),
            listOf("항목 1,203개가 백업되었습니다", "여유 공간 3GB 확보"),
            listOf("  이미  안전하게   백업되었습니다  ", "여유 공간 확보"),
        ).forEach { assertEquals(it.toString(), CleanupScreenCheck.Verdict.Safe, CleanupScreenCheck.verdict(it)) }
    }

    @Test
    fun `누르면 안 되는 화면 - 백업 문제 문구가 보이면 사유를 돌려준다`() {
        val cases = mapOf(
            "백업이 중지되었습니다" to listOf("백업이 중지되었습니다", "여유 공간 5GB 확보"),
            "백업 오류" to listOf("백업 오류", "이미 안전하게 백업되었습니다", "여유 공간 5GB 확보"),   // 확인 문구가 같이 있어도 문제가 우선
            "백업되지 않음" to listOf("일부 항목이 백업되지 않음", "여유 공간 5GB 확보"),
            "백업 실패" to listOf("백업 실패 항목이 있습니다", "백업되었습니다", "여유 공간 5GB 확보"),
        )
        for ((reason, texts) in cases) {
            val v = CleanupScreenCheck.verdict(texts)
            assertTrue(texts.toString(), v is CleanupScreenCheck.Verdict.Problem)
            assertEquals(reason, (v as CleanupScreenCheck.Verdict.Problem).reason)
        }
    }

    @Test
    fun `백업 확인 문구가 없으면 누르지 않는다`() {
        listOf(
            listOf("여유 공간 14.78GB 확보"),
            listOf("기기 저장용량의 83%를 사용 중입니다. 여유 공간을 확보하세요", "여유 공간 14.78GB 확보"),
            emptyList(),
            listOf("이 기기의 여유 공간 확보 중"),
        ).forEach { assertEquals(it.toString(), CleanupScreenCheck.Verdict.NoBackupText, CleanupScreenCheck.verdict(it)) }
    }

    @Test
    fun `결과 문구 - 사유와 한글 표기`() {
        assertEquals("눌렀음", CleanupText.korean(CleanupResult.PRESSED))
        assertTrue(CleanupText.korean(CleanupResult.NO_BACKUP_TEXT).contains("백업 확인 문구 없음"))
        assertTrue(CleanupText.korean(CleanupResult.problem("백업이 중지되었습니다")).contains("백업이 중지되었습니다"))
        assertEquals("마지막 공간 확보 결과: 아직 없음", CleanupText.resultLine("", ""))
        assertEquals("마지막 공간 확보 결과: 버튼 못 찾음 (3분 전)", CleanupText.resultLine(CleanupResult.NO_BUTTON, "3분 전"))
    }

    @Test
    fun `관문 - 2분 안 한 번만`() {
        assertTrue(CleanupGate.active(now = 1_000_000, requestedAt = 1_000_000, pressedFor = 0))
        assertTrue(CleanupGate.active(1_000_000 + 119_000, 1_000_000, 0))
        assertFalse(CleanupGate.active(1_000_000 + 121_000, 1_000_000, 0))
        assertFalse(CleanupGate.active(1_000_000, 0, 0))                         // 요청 없음
        assertFalse(CleanupGate.active(1_000_001, 1_000_000, 1_000_000))         // 이미 누름
        assertFalse(CleanupGate.active(999_000, 1_000_000, 0))                   // 시계가 거꾸로
        CleanupSession.reset()
        val rid = CleanupSession.begin(5_000)
        assertTrue(CleanupSession.claimPress(rid, 6_000))
        assertFalse(CleanupSession.claimPress(rid, 7_000))                       // 같은 요청에서 두 번째는 거절
        assertFalse(CleanupSession.claimPress(rid + 99, 7_000))                  // 다른 요청 식별자는 거절
        CleanupSession.reset()
    }

    @Test
    fun `자동 실행 조건 - 6GB 미만 이고 PUBLISHED 있고 30분 경과`() {
        val now = 10_000_000L
        val half = 30 * 60_000L
        assertTrue(AutoCleanPolicy.shouldRun(5 * GB, 3, 0, now))                       // 처음
        assertTrue(AutoCleanPolicy.shouldRun(6 * GB - 1, 1, now - half, now))          // 정확히 30분
        assertFalse(AutoCleanPolicy.shouldRun(6 * GB, 3, 0, now))                      // 6GB 이상(경계)
        assertFalse(AutoCleanPolicy.shouldRun(20 * GB, 3, 0, now))
        assertFalse(AutoCleanPolicy.shouldRun(1 * GB, 0, 0, now))                      // 대기 없음
        assertFalse(AutoCleanPolicy.shouldRun(1 * GB, 3, now - half + 1, now))         // 30분 안
        assertFalse(AutoCleanPolicy.shouldRun(1 * GB, 3, now - 60_000, now))
    }

    @Test
    fun `접근성 켜짐 판정 - 설정 문자열에서 우리 서비스만`() {
        val cls = "com.pixelrelay.receiver.PhotosCleanupService"
        assertTrue(AccessibilityCheck.enabled("com.pixelrelay/com.pixelrelay.receiver.PhotosCleanupService", "com.pixelrelay", cls))
        assertTrue(AccessibilityCheck.enabled("a.b/.C:com.pixelrelay/.receiver.PhotosCleanupService", "com.pixelrelay", cls))
        assertFalse(AccessibilityCheck.enabled("a.b/.C:x.y/.Z", "com.pixelrelay", cls))
        assertFalse(AccessibilityCheck.enabled("com.pixelrelay/com.pixelrelay.other.Service", "com.pixelrelay", cls))
        assertFalse(AccessibilityCheck.enabled("", "com.pixelrelay", cls))
        assertFalse(AccessibilityCheck.enabled(null, "com.pixelrelay", cls))
    }

    @Test
    fun `보내기 폰 문구 - 지금 보내기 결과 빈 목록 안내 접근성 경고`() {
        assertEquals("지금 보내기를 시작했습니다. 픽셀에 지금 받기 요청을 보냈습니다(집 와이파이).", HomeText.sendNow("LAN"))
        assertTrue(HomeText.sendNow("드라이브").contains("구글 드라이브"))
        assertFalse(HomeText.sendNow("LAN").contains("sendNow"))
        assertFalse(HomeText.sendNow("드라이브").contains("sendNow"))
        assertNotNull(HomeText.emptyHint(0, 0, BackfillMode.NONE))
        assertEquals("새로 찍은 사진만 보냅니다. 예전 사진도 보내려면 설정의 '기존 사진도 보내기'를 켜세요", HomeText.emptyHint(0, 0, BackfillMode.NONE))
        assertNull(HomeText.emptyHint(2, 0, BackfillMode.NONE))
        assertNull(HomeText.emptyHint(0, 5, BackfillMode.NONE))
        assertNull(HomeText.emptyHint(0, 0, BackfillMode.ALL))
        assertNull(HomeText.emptyHint(0, 0, BackfillMode.SINCE))

        val st = PixelStatus(3 * GB, 80, true, 30.0, StatusCounts(7, 0, 0, 0), 0, ReceiverConfig(), emptyList(), 1, "t", 1000, accessibilityOn = false)
        assertEquals("자동 공간 확보 꺼짐(픽셀 접근성 권한 필요)", HomeText.accessibilityWarning(st))
        assertNull(HomeText.accessibilityWarning(st.copy(accessibilityOn = true)))
        assertNull(HomeText.accessibilityWarning(null))
        val lines = HomeText.pixelSpaceLines(st.freeBytes, 7, "3.0GB")
        assertTrue(lines[0].contains("3.0GB") && lines[0].contains("부족"))
        assertEquals("픽셀 업로드 대기 7개(포토 확인 대기)", lines[1])
        // 픽셀 시계로 5분 전 결과 -> 보내기 폰 시계로도 5분 전
        val s2 = st.copy(reportedAt = 1_000_000, cleanupResultAt = 1_000_000 - 300_000)
        assertEquals(10_000_000L - 300_000, HomeText.resultAtOnLocalClock(s2, 10_000_000L))
        assertEquals(0L, HomeText.resultAtOnLocalClock(st, 10_000_000L))
    }

    @Test
    fun `상태 JSON - 새 필드 왕복 그리고 옛 픽셀(필드 없음)은 접근성 켜짐으로 본다`() {
        val st = PixelStatus(1, 2, false, 3.0, StatusCounts(1, 2, 3, 4), 5, ReceiverConfig(), emptyList(), 6, "v", 7,
            cleanupResult = CleanupResult.PRESSED, cleanupResultAt = 99, accessibilityOn = false)
        val back = PixelStatus.fromJson(st.toJson())
        assertEquals(CleanupResult.PRESSED, back.cleanupResult); assertEquals(99L, back.cleanupResultAt); assertFalse(back.accessibilityOn)
        val old = st.toJson(); old.remove("accessibilityOn"); old.remove("cleanupResult")
        assertTrue(PixelStatus.fromJson(old).accessibilityOn)
        assertEquals("", PixelStatus.fromJson(old).cleanupResult)
    }

    @Test
    fun `새 명령 분류 - 일괄 확정만 파괴적, 공간 확보는 파괴적 아님이지만 1회 처리`() {
        assertTrue(RemoteCommand.CONFIRM_ALL_VANISHED in RemoteCommand.DESTRUCTIVE)
        assertFalse(RemoteCommand.FREE_UP_SPACE in RemoteCommand.DESTRUCTIVE)
        assertTrue(RemoteCommand.FREE_UP_SPACE in RemoteCommand.ONCE_ONLY)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CleanupCommandTest {
    private lateinit var env: RecvEnv

    @Before fun setUp() { env = RecvEnv(); CleanupSession.reset() }
    @After fun tearDown() { env.close(); CleanupSession.reset() }

    private fun handler(freeUp: (suspend () -> CommandResult)?) =
        CommandHandler(env.ledger, ReceiverConfigStore(env.meta), env.confirmer, env.clock, {}, freeUp)

    @Test
    fun `FREE_UP_SPACE - commandId 필수, 한 번만 처리, 재사용 거절`() = runBlocking {
        var calls = 0
        val h = handler { calls++; CommandResult(true, "공간 확보 시작") }
        assertFalse(h.handle(RemoteCommand(RemoteCommand.FREE_UP_SPACE), remote = true).ok)          // id 없음
        assertEquals(0, calls)
        val r = h.handle(RemoteCommand(RemoteCommand.FREE_UP_SPACE, id = "f-1"), remote = true)
        assertTrue(r.ok); assertEquals(1, calls)
        val again = h.handle(RemoteCommand(RemoteCommand.FREE_UP_SPACE, id = "f-1"), remote = true)    // LAN·드라이브 어느 쪽이든 같은 id 는 거절
        assertFalse(again.ok); assertEquals(1, calls)
        assertTrue(h.handle(RemoteCommand(RemoteCommand.FREE_UP_SPACE, id = "f-2"), remote = true).ok)
        assertEquals(2, calls)
        // 7일이 지나면 id 보관에서 빠진다(다른 파괴적 명령과 같은 규칙)
        env.clock.advanceMin(7 * 24 * 60 + 1)
        assertTrue(h.handle(RemoteCommand(RemoteCommand.FREE_UP_SPACE, id = "f-1"), remote = true).ok)
    }

    @Test
    fun `FREE_UP_SPACE - 처리기가 없거나 예외여도 안전하게 실패로 답한다`() = runBlocking {
        assertFalse(handler(null).handle(RemoteCommand(RemoteCommand.FREE_UP_SPACE, id = "f-1"), remote = true).ok)
        val h = handler { throw IllegalStateException("터짐") }
        val r = h.handle(RemoteCommand(RemoteCommand.FREE_UP_SPACE, id = "f-2"), remote = true)
        assertFalse(r.ok); assertTrue(r.message.contains("터짐"))
    }

    @Test
    fun `FREE_UP_SPACE 는 JSON 으로 왕복된다`() {
        val c = RemoteCommand(RemoteCommand.FREE_UP_SPACE, id = "x")
        val back = RemoteCommand.fromJson(c.toJson())
        assertEquals(RemoteCommand.FREE_UP_SPACE, back.type); assertEquals("x", back.id)
    }

    private suspend fun vanished(n: Int, seed: Int): List<String> = (0 until n).map { i ->
        val bytes = randomBytes(300 + i, (seed + i).toLong())
        val sha = env.receiveWhole(bytes, "v$i.jpg")
        val f = env.drive.put(sha, "v$i.jpg", bytes)
        env.ledger.attachCopy(sha, f.id, bytes.size.toLong())
        env.ledger.transition(sha, RecvState.VANISHED_UNKNOWN)
        sha
    }

    @Test
    fun `일괄 확정 - 원인 불명 전부 포토 확인됨 + 커밋 뒤 드라이브 사본 정리, 다른 항목은 그대로`() = runBlocking {
        val h = handler(null)
        val shas = vanished(3, 700)
        val published = env.receiveWhole(randomBytes(250, 799), "p.jpg")                // PUBLISHED 는 대상 아님
        // commandId 없으면 거절(파괴적)
        assertFalse(h.handle(RemoteCommand(RemoteCommand.CONFIRM_ALL_VANISHED), remote = true).ok)
        shas.forEach { assertEquals(RecvState.VANISHED_UNKNOWN, env.ledger.get(it)!!.stateEnum) }

        val r = h.handle(RemoteCommand(RemoteCommand.CONFIRM_ALL_VANISHED, id = "all-1"), remote = true)
        assertTrue(r.message, r.ok)
        for (s in shas) {
            val st = env.ledger.get(s)!!.stateEnum
            assertEquals(RecvState.CLEANED, st)                                            // 확정 -> 사본 삭제 -> CLEANED
            assertTrue(env.ledger.copies(s).isEmpty())
        }
        assertEquals(RecvState.PUBLISHED, env.ledger.get(published)!!.stateEnum)
        assertEquals(3, env.drive.deletes.size)
        assertTrue("허용되지 않은 삭제 없음", env.drive.illegalDeletes().isEmpty())
        // 같은 commandId 재사용 거절
        assertFalse(h.handle(RemoteCommand(RemoteCommand.CONFIRM_ALL_VANISHED, id = "all-1"), remote = true).ok)
    }

    @Test
    fun `일괄 확정 - 대상이 없으면 실패로 답하고 commandId 도 남기지 않는다`() = runBlocking {
        val h = handler(null)
        assertFalse(h.handle(RemoteCommand(RemoteCommand.CONFIRM_ALL_VANISHED, id = "z-1"), remote = true).ok)
        vanished(1, 810)
        assertTrue(h.handle(RemoteCommand(RemoteCommand.CONFIRM_ALL_VANISHED, id = "z-1"), remote = true).ok)   // 같은 id 로 다시 가능(앞선 건 처리되지 않음)
    }

    @Test
    fun `일괄 확정 - 드라이브 삭제가 일부 실패해도 상태는 확정이고 나머지 정리는 계속된다`() = runBlocking {
        val h = handler(null)
        val shas = vanished(2, 820)
        val failing = env.ledger.copies(shas[0]).first().driveFileId
        val real = env.drive
        val flaky = object : com.pixelrelay.drive.DriveApi by real {
            override suspend fun deleteFile(fileId: String) { if (fileId == failing) throw java.io.IOException("삭제 실패(시험)") else real.deleteFile(fileId) }
        }
        val cleaner = com.pixelrelay.receiver.Cleaner(env.ledger, flaky)
        val confirmer = PhotosConfirmer(env.ledger, env.sink, env.clock, cleaner)
        val h2 = CommandHandler(env.ledger, ReceiverConfigStore(env.meta), confirmer, env.clock)
        assertTrue(h2.handle(RemoteCommand(RemoteCommand.CONFIRM_ALL_VANISHED, id = "p-1"), remote = true).ok)
        assertEquals(RecvState.PHOTOS_CONFIRMED, env.ledger.get(shas[0])!!.stateEnum)    // 사본 삭제 실패 -> 나중에 다시 시도
        assertEquals(RecvState.CLEANED, env.ledger.get(shas[1])!!.stateEnum)
        cleaner.cleanAllConfirmed()                                                      // (같은 실패가 계속되니 그대로)
        assertEquals(RecvState.PHOTOS_CONFIRMED, env.ledger.get(shas[0])!!.stateEnum)
    }
}

/** 자동 공간 확보 절차(접근성 서비스는 가짜로 흉내). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CleanupFlowTest {
    private lateinit var env: RecvEnv

    private class Dev(var access: Boolean = true, var screen: Boolean = true, var opens: Boolean = true, var via: com.pixelrelay.core.PhotosOpen = com.pixelrelay.core.PhotosOpen.LINK) : CleanupDevice {
        var opened = 0; var notified = 0
        var onOpen: () -> Unit = {}
        override fun accessibilityOn() = access
        override fun screenOn() = screen
        override fun openPhotos(): com.pixelrelay.core.PhotosOpen { opened++; onOpen(); return if (opens) via else com.pixelrelay.core.PhotosOpen.FAILED }
        override fun notifyManual() { notified++ }
    }

    @Before fun setUp() { env = RecvEnv(); CleanupSession.reset() }
    @After fun tearDown() { env.close(); CleanupSession.reset() }

    private fun flow(dev: Dev, onSleep: () -> Unit = {}) = CleanupFlow(
        env.ledger, env.confirmer, env.clock, dev, CoroutineScope(Job()),
        sleep = { env.clock.advance(it); onSleep() },
    )

    private suspend fun result() = env.ledger.getString(CleanupKeys.RESULT)

    @Test
    fun `정상 - recordButton 이 포토 열기 전에 기록되고 접근성이 누르면 눌렀음`() = runBlocking {
        val sha = env.receiveWhole(randomBytes(300, 900), "a.jpg")
        val dev = Dev()
        var buttonBeforeOpen = false
        dev.onOpen = { buttonBeforeOpen = runBlocking { env.ledger.getLong(PhotosConfirmer.KEY_BUTTON_AT) } != null }
        val f = flow(dev) { svcPress(env.clock.nowMs()) }
        val run = f.start(CleanupTrigger.REMOTE)
        assertNotNull(run); assertTrue(buttonBeforeOpen); assertEquals(1, dev.opened)
        assertNotNull(env.ledger.get(sha)!!.candidateAt)                                  // CLEANUP_OPENED 와 같은 후보 기록
        f.watch(run!!)
        assertEquals(CleanupResult.PRESSED, result())
        assertEquals(0, dev.notified)
        assertTrue((env.ledger.getLong(CleanupKeys.RESULT_AT) ?: 0) > 0)
    }

    @Test
    fun `접근성 꺼짐 - 권한 없음 기록, 자동·원격은 알림, 사람이 누르면 포토를 열어 준다`() = runBlocking {
        val dev = Dev(access = false)
        assertNull(flow(dev).start(CleanupTrigger.REMOTE))
        assertEquals(CleanupResult.NO_PERMISSION, result()); assertEquals(1, dev.notified); assertEquals(0, dev.opened)
        assertNull(flow(dev).start(CleanupTrigger.AUTO))
        assertEquals(2, dev.notified)
        assertNull(flow(dev).start(CleanupTrigger.UI))
        assertEquals(CleanupResult.NO_PERMISSION, result()); assertEquals(2, dev.notified); assertEquals(1, dev.opened)
        assertFalse(CleanupSession.active(env.clock.nowMs()))                              // 접근성이 꺼져 있으면 요청도 열지 않는다
    }

    @Test
    fun `화면 꺼짐 - 자동·원격은 열지 않고 알림, 사람이 누른 경우는 연다`() = runBlocking {
        val dev = Dev(screen = false)
        assertNull(flow(dev).start(CleanupTrigger.AUTO))
        assertEquals(CleanupResult.NOTIFIED, result()); assertEquals(1, dev.notified); assertEquals(0, dev.opened)
        assertNotNull(flow(dev).start(CleanupTrigger.UI))
        assertEquals(1, dev.opened)
    }

    @Test
    fun `백그라운드에서 포토가 15초 안에 안 보이면 알림으로 대신`() = runBlocking {
        val dev = Dev()
        val f = flow(dev)                                                                 // 서비스가 포토 창을 못 봄
        val run = f.start(CleanupTrigger.REMOTE)!!
        f.watch(run)
        assertEquals(CleanupResult.NOTIFIED, result()); assertEquals(1, dev.notified)
        assertFalse(CleanupSession.active(env.clock.nowMs()))                              // 알림 뒤에는 아무것도 안 누른다
        assertTrue(env.clock.nowMs() - run.startedAt <= 16_000)
    }

    @Test
    fun `포토는 보였는데 버튼이 없으면 2분 뒤 버튼 못 찾음`() = runBlocking {
        val dev = Dev()
        val f = flow(dev) { svcSeen(env.clock.nowMs()) }
        val run = f.start(CleanupTrigger.UI)!!
        f.watch(run)
        assertEquals(CleanupResult.NO_BUTTON, result())
        assertTrue(env.clock.nowMs() - run.startedAt > 120_000)
        assertEquals(0, dev.notified)
    }

    @Test
    fun `안전 조건 - 백업 문제 문구와 백업 확인 문구 없음은 누르지 않고 사유를 기록`() = runBlocking {
        run {
            val f = flow(Dev()) { val r = svcSeen(env.clock.nowMs()); if (r != 0L) CleanupSession.noteBlock(r, "백업이 중지되었습니다") }
            f.watch(f.start(CleanupTrigger.UI)!!)
            assertEquals(CleanupResult.problem("백업이 중지되었습니다"), result())
        }
        run {
            val f = flow(Dev()) { val r = svcSeen(env.clock.nowMs()); if (r != 0L) CleanupSession.noteNoBackupText(r) }
            f.watch(f.start(CleanupTrigger.UI)!!)
            assertEquals(CleanupResult.NO_BACKUP_TEXT, result())
        }
    }

    @Test
    fun `포토를 못 열면 열기 실패를 기록하고 요청을 열지 않는다`() = runBlocking {
        val dev = Dev(opens = false)
        assertNull(flow(dev).start(CleanupTrigger.UI))
        assertEquals(CleanupResult.OPEN_FAILED, result())
    }

    @Test
    fun `새 요청은 이전 요청의 눌림 기록을 이어받지 않는다`() = runBlocking {
        val dev = Dev()
        val f = flow(dev) { svcPress(env.clock.nowMs()) }
        f.watch(f.start(CleanupTrigger.UI)!!)
        assertEquals(CleanupResult.PRESSED, result())
        env.clock.advance(1_000)
        val second = flow(dev).start(CleanupTrigger.UI)!!                                 // 새 요청: 아직 안 눌림
        assertTrue(CleanupSession.active(env.clock.nowMs()))
        val rid2 = second.id
        assertTrue(CleanupSession.claimPress(rid2, env.clock.nowMs())); assertFalse(CleanupSession.claimPress(rid2, env.clock.nowMs()))
        assertTrue(second.startedAt > 0)
    }
}

/** 보내기 폰 2대가 서로 다른 짝짓기 키로 번갈아 보낸다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TwoSendersTest {
    private lateinit var recv: RecvEnv
    private lateinit var server: LanServer
    private lateinit var keys: InMemoryPairedKeys
    private lateinit var pairing: PairingManager
    private val client = OkHttpClient()
    private var port = 0
    private val envs = ArrayList<SendEnv>()

    @Before
    fun setUp() {
        recv = RecvEnv()
        keys = InMemoryPairedKeys()
        pairing = PairingManager(recv.clock, keys)
        val verifier = RequestVerifier(recv.clock, { keys.all() })
        val handler = CommandHandler(recv.ledger, ReceiverConfigStore(recv.meta), recv.confirmer, recv.clock)
        server = LanServer(0, recv.core, recv.ledger, verifier, pairing, {
            PixelStatus(recv.free, 77, true, 31.5, StatusCounts(0, 0, 0, 0), 0, ReceiverConfig(), emptyList(), recv.ledger.maxSeq(), "test", recv.clock.nowMs())
        }, handler)
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        port = server.listeningPort
    }

    @After fun tearDown() { server.stop(); recv.close(); envs.forEach { it.db.close() } }

    private suspend fun pairNew(name: String): ByteArray {
        val code = pairing.newCode()
        return java.util.Base64.getDecoder().decode(PixelPairing.pair(client, "127.0.0.1", port, code, name))
    }

    private fun phone(): SendEnv = SendEnv(TestDb.memory(), recv.clock, chunk = 4096).also { envs += it }

    private suspend fun sendAll(env: SendEnv, api: HttpPixelApi, bytes: ByteArray): SendResult {
        val entry = env.ledger.get(Hashing.sha256Hex(bytes))!!
        return LanSender(env.source, env.ledger, env.clock, chunk = 4096).send(entry, api, Long.MAX_VALUE)
    }

    @Test
    fun `두 폰이 다른 키로 번갈아 보내고 같은 파일은 한 번만 저장된다`() = runBlocking {
        val keyA = pairNew("폰A"); val keyB = pairNew("폰B")
        assertFalse(keyA.contentEquals(keyB))
        assertEquals(2, keys.all().size)
        val apiA = HttpPixelApi("127.0.0.1", port, keyA, recv.clock, client)
        val apiB = HttpPixelApi("127.0.0.1", port, keyB, recv.clock, client)
        assertEquals(77, apiA.status().batteryPct); assertEquals(77, apiB.status().batteryPct)

        val a1 = randomBytes(6000, 1001); val a2 = randomBytes(5000, 1002)
        val b1 = randomBytes(7000, 1003)
        val same = randomBytes(4500, 1004)                                               // 두 폰에 모두 있는 같은 사진
        val phoneA = phone(); val phoneB = phone()
        phoneA.source.add("A1.jpg", a1); phoneA.source.add("A2.jpg", a2); phoneA.source.add("SAME.jpg", same)
        phoneB.source.add("B1.jpg", b1); phoneB.source.add("SAME_copy.jpg", same)
        phoneA.registerNow(); phoneB.registerNow()

        // A1 -> B1 -> A2 -> (같은 파일) B -> A
        assertTrue(sendAll(phoneA, apiA, a1) is SendResult.Done)
        assertTrue(sendAll(phoneB, apiB, b1) is SendResult.Done)
        assertTrue(sendAll(phoneA, apiA, a2) is SendResult.Done)
        assertEquals(SendResult.Done(same.size.toLong()), sendAll(phoneB, apiB, same))   // 처음 받는 쪽
        assertEquals(SendResult.Done(0), sendAll(phoneA, apiA, same))                    // 이미 있음: 바이트 0, 중복 저장 없음

        for (b in listOf(a1, a2, b1, same)) assertEquals(RecvState.PUBLISHED, recv.ledger.get(Hashing.sha256Hex(b))!!.stateEnum)
        assertEquals(4, recv.ledger.all().size)
        assertEquals(4, recv.dir.walkTopDown().filter { it.isFile && it.extension == "jpg" }.count())   // 같은 파일이 두 번 저장되지 않음
        // 폰마다 상태 장부에도 반영(LAN 변화 동기화)
        for (env in envs) env.sync.syncViaLan(if (env === phoneA) apiA else apiB)
        assertEquals(com.pixelrelay.core.SendState.RECEIVED.name, phoneA.ledger.get(Hashing.sha256Hex(same))!!.state)
        assertEquals(com.pixelrelay.core.SendState.RECEIVED.name, phoneB.ledger.get(Hashing.sha256Hex(same))!!.state)
    }

    @Test
    fun `짝지어지지 않은 키는 거절되고 짝지은 두 키는 모두 통과`() = runBlocking {
        val keyA = pairNew("폰A"); val keyB = pairNew("폰B")
        val stranger = HttpPixelApi("127.0.0.1", port, randomBytes(32, 77), recv.clock, client)
        try { stranger.status(); fail() } catch (e: PixelHttpException) { assertEquals(401, e.code) }
        assertEquals(77, HttpPixelApi("127.0.0.1", port, keyA, recv.clock, client).status().batteryPct)
        assertEquals(77, HttpPixelApi("127.0.0.1", port, keyB, recv.clock, client).status().batteryPct)
    }
}

/** 연결 안 함 상태 정리. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriveSkipTest {
    @Test
    fun `연결 안 함을 고르면 옛 인증 문제 표시도 지워지고 경고가 없다`() = runBlocking {
        val db = TestDb.memory()
        val meta = com.pixelrelay.core.MetaStore(db)
        meta.putLong("driveAuthProblem", 1)
        DriveUse.setSkipped(meta, true)
        assertEquals(1L, meta.getLong(DriveUse.KEY_SKIPPED)); assertEquals(0L, meta.getLong("driveAuthProblem"))
        val authProblem = meta.getLong("driveAuthProblem") == 1L
        assertNull(DriveUse.homeAuthWarning(driveUsed = false, authProblem = authProblem))
        assertNull(DriveUse.homeAuthWarning(driveUsed = false, authProblem = true))
        DriveUse.setSkipped(meta, false)
        assertEquals(0L, meta.getLong(DriveUse.KEY_SKIPPED))
        db.close()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosLinkTest {
    @Test
    fun `공간 확보 직접 링크 인텐트 - VIEW + 포토 패키지 고정`() {
        val i = com.pixelrelay.ui.PhotosLink.freeUpIntent()
        assertEquals(android.content.Intent.ACTION_VIEW, i.action)
        assertEquals("https://photos.google.com/link/freeupspace", i.data.toString())
        assertEquals("com.google.android.apps.photos", i.`package`)
        assertTrue(i.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `링크가 안 열려 런처로 돌아가면 그 사실을 기록하고 실패로 보지 않는다`() = runBlocking {
        val env = RecvEnv(); CleanupSession.reset()
        try {
            class D(val r: com.pixelrelay.core.PhotosOpen) : CleanupDevice {
                override fun accessibilityOn() = true
                override fun screenOn() = true
                override fun openPhotos() = r
                override fun notifyManual() {}
            }
            val f = { r: com.pixelrelay.core.PhotosOpen -> CleanupFlow(env.ledger, env.confirmer, env.clock, D(r), CoroutineScope(Job()), sleep = { env.clock.advance(it) }) }
            assertNotNull(f(com.pixelrelay.core.PhotosOpen.LAUNCHER).start(CleanupTrigger.UI))
            assertEquals("LAUNCHER", env.ledger.getString(CleanupKeys.OPEN_VIA))
            assertNull(env.ledger.getString(CleanupKeys.RESULT))                                  // OPEN_FAILED 아님
            assertNotNull(HomeText.let { com.pixelrelay.core.OpenViaText.note("LAUNCHER") })
            assertNull(com.pixelrelay.core.OpenViaText.note("LINK"))
            assertNotNull(f(com.pixelrelay.core.PhotosOpen.LINK).start(CleanupTrigger.UI))
            assertEquals("LINK", env.ledger.getString(CleanupKeys.OPEN_VIA))
            assertNull(f(com.pixelrelay.core.PhotosOpen.FAILED).start(CleanupTrigger.UI))
            assertEquals(CleanupResult.OPEN_FAILED, env.ledger.getString(CleanupKeys.RESULT))
        } finally { env.close(); CleanupSession.reset() }
    }
}

internal fun svcSeen(now: Long): Long { val r = CleanupSession.activeId(now); if (r != 0L) CleanupSession.noteSeen(r, now); return r }
internal fun svcPress(now: Long, ok: Boolean = true) {
    if (svcSeen(now) == 0L) return
    val c = svcSeen(now).takeIf { CleanupSession.claimPress(it, now) } ?: 0L
    if (c != 0L) CleanupSession.reportClick(c, ok)
}
