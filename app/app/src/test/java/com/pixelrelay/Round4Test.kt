package com.pixelrelay

import com.pixelrelay.core.CleanupButton
import com.pixelrelay.core.CleanupPlanner
import com.pixelrelay.core.CleanupResult
import com.pixelrelay.core.CleanupKeys
import com.pixelrelay.core.CleanupScreenCheck
import com.pixelrelay.core.CleanupSession
import com.pixelrelay.core.DriveKnown
import com.pixelrelay.core.DriveUse
import com.pixelrelay.core.LedgerItem
import com.pixelrelay.core.PhotosOpen
import com.pixelrelay.core.RecvState
import com.pixelrelay.core.RemoteCommand
import com.pixelrelay.core.ScreenSnapshot
import com.pixelrelay.core.SnapNode
import com.pixelrelay.core.StatusWarning
import com.pixelrelay.core.WarnCode
import com.pixelrelay.receiver.CleanupDevice
import com.pixelrelay.receiver.CleanupFlow
import com.pixelrelay.receiver.CleanupTrigger
import com.pixelrelay.receiver.CommandHandler
import com.pixelrelay.receiver.PhotosConfirmer
import com.pixelrelay.receiver.ReceiverConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

/** 교차검증 반영: 화면 판정(순수 함수) 재현 시험. */
class CleanupPlannerTest {
    private val SAFE = "이 항목은 선택한 화질로 이미 안전하게 백업되었습니다."
    private val BTN = "여유 공간 203MB 확보"

    private fun n(id: Int, parent: Int, text: String? = null, desc: String? = null, clickable: Boolean = false, visible: Boolean = true, enabled: Boolean = true) =
        SnapNode(id, parent, text, desc, clickable, enabled, visible)

    /** 정상 화면: 안전 문구 + 클릭 가능한 버튼 */
    private fun good() = listOf(n(0, -1, text = SAFE), n(1, -1, text = BTN, clickable = true))

    private fun decide(nodes: List<SnapNode>, complete: Boolean = true) = CleanupPlanner.decide(ScreenSnapshot(nodes, complete))

    private val NO_BUTTON = CleanupPlanner.Decision.Skip(CleanupPlanner.Why.NO_BUTTON)

    @Test
    fun `정상 화면은 버튼을 누른다`() {
        assertEquals(CleanupPlanner.Decision.Press(1), decide(good()))
    }

    // 1. 검증 안 된 부모 승격 금지
    @Test
    fun `문구 노드가 클릭 불가이고 부모가 삭제 문구를 품고 있으면 부모를 누르지 않는다`() {
        val nodes = listOf(
            n(0, -1, text = SAFE),
            n(1, -1, clickable = true),
            n(2, 1, text = BTN),
            n(3, 1, text = "기기에서 삭제"),
        )
        assertEquals(NO_BUTTON, decide(nodes))
    }

    @Test
    fun `부모의 contentDescription 에 금지 단어가 있으면 누르지 않는다`() {
        val nodes = listOf(n(0, -1, text = SAFE), n(1, -1, desc = "휴지통으로 이동", clickable = true), n(2, 1, text = BTN))
        assertEquals(NO_BUTTON, decide(nodes))
    }

    @Test
    fun `text 는 맞아도 contentDescription 에 금지 단어가 있으면 후보가 아니다`() {
        assertFalse(CleanupButton.matchesNode(BTN, "저장용량 구매"))
        assertTrue(CleanupButton.matchesNode(BTN, null))
        assertTrue(CleanupButton.matchesNode(null, BTN))
        val nodes = listOf(n(0, -1, text = SAFE), n(1, -1, text = BTN, desc = "Google One 구독", clickable = true))
        assertEquals(NO_BUTTON, decide(nodes))
    }

    @Test
    fun `깨끗한 클릭 가능 부모는 눌러도 된다 - 후보가 둘이어도 대상이 같으면 하나`() {
        val nodes = listOf(n(0, -1, text = SAFE), n(1, -1, clickable = true), n(2, 1, text = BTN), n(3, 1, desc = BTN))
        assertEquals(CleanupPlanner.Decision.Press(1), decide(nodes))
    }

    @Test
    fun `누를 대상이 둘이면 아무것도 누르지 않는다`() {
        val nodes = good() + n(2, -1, text = "여유 공간 5MB 확보", clickable = true)
        assertEquals(NO_BUTTON, decide(nodes))
    }

    @Test
    fun `클릭 가능한 부모가 3단계보다 멀리 있으면 누르지 않는다`() {
        val nodes = listOf(
            n(0, -1, text = SAFE), n(1, -1, clickable = true), n(2, 1), n(3, 2), n(4, 3), n(5, 4, text = BTN),
        )
        assertEquals(NO_BUTTON, decide(nodes))
    }

    // 2. 불완전 탐색
    @Test
    fun `화면을 끝까지 못 봤으면 정상 화면이어도 누르지 않는다`() {
        assertEquals(CleanupPlanner.Decision.Skip(CleanupPlanner.Why.INCOMPLETE), decide(good(), complete = false))
    }

    // 3. 보이는 노드만
    @Test
    fun `안전 문구가 보이지 않는 노드에만 있으면 누르지 않는다`() {
        val nodes = listOf(n(0, -1, text = SAFE, visible = false), n(1, -1, text = BTN, clickable = true))
        assertEquals(CleanupPlanner.Decision.Skip(CleanupPlanner.Why.NO_BACKUP_TEXT), decide(nodes))
    }

    @Test
    fun `보이지 않거나 비활성인 버튼과 클릭 부모는 누르지 않는다`() {
        assertEquals(NO_BUTTON, decide(listOf(n(0, -1, text = SAFE), n(1, -1, text = BTN, clickable = true, visible = false))))
        assertEquals(NO_BUTTON, decide(listOf(n(0, -1, text = SAFE), n(1, -1, clickable = true, visible = false), n(2, 1, text = BTN))))
        assertEquals(NO_BUTTON, decide(listOf(n(0, -1, text = SAFE), n(1, -1, text = BTN, clickable = true, enabled = false))))
    }

    @Test
    fun `보이지 않는 노드의 백업 문제 문구도 막는다`() {
        val d = decide(good() + n(2, -1, text = "백업 오류", visible = false))
        assertTrue(d is CleanupPlanner.Decision.Skip && d.why == CleanupPlanner.Why.PROBLEM && d.detail == "백업 오류")
        assertEquals(CleanupScreenCheck.Verdict.NoBackupText, CleanupScreenCheck.verdict(emptyList(), listOf(SAFE)))
    }

    // 9. 드라이브 사용 여부를 읽는 중
    @Test
    fun `드라이브 사용 여부를 아직 못 읽었으면 드라이브 경고를 숨긴다`() {
        assertFalse(DriveKnown.used(null)); assertFalse(DriveKnown.used(false)); assertTrue(DriveKnown.used(true))
        val w = listOf(StatusWarning(WarnCode.DRIVE_AUTH, 1), StatusWarning(WarnCode.LOW_SPACE, 1))
        assertEquals(listOf(WarnCode.LOW_SPACE), DriveUse.visibleWarnings(w, DriveKnown.used(null)).map { it.code })
        assertNull(DriveUse.homeAuthWarning(DriveKnown.used(null), true))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CleanupFlowRound4Test {
    private lateinit var env: RecvEnv

    private class Dev(var screen: Boolean = true, var via: PhotosOpen = PhotosOpen.LINK) : CleanupDevice {
        var opened = 0; var notified = 0
        override fun accessibilityOn() = true
        override fun screenOn() = screen
        override fun openPhotos(): PhotosOpen { opened++; return via }
        override fun notifyManual() { notified++ }
    }

    @Before fun setUp() { env = RecvEnv(); CleanupSession.reset() }
    @After fun tearDown() { env.close(); CleanupSession.reset() }

    private fun flow(dev: Dev, onSleep: () -> Unit = {}) =
        CleanupFlow(env.ledger, env.confirmer, env.clock, dev, CoroutineScope(Job()), sleep = { env.clock.advance(it); onSleep() })

    private suspend fun result() = env.ledger.getString(CleanupKeys.RESULT)

    // 4. 화면 꺼짐 알림 전환은 활성 요청을 무효화
    @Test
    fun `화면 꺼짐으로 알림 전환하면 진행 중이던 요청도 무효가 되어 아무것도 못 누른다`() = runBlocking {
        val dev = Dev()
        val f = flow(dev)
        val run = f.start(CleanupTrigger.UI)!!
        assertTrue(CleanupSession.active(env.clock.nowMs()))
        dev.screen = false
        assertNull(f.start(CleanupTrigger.AUTO))
        assertEquals(CleanupResult.NOTIFIED, result()); assertEquals(1, dev.notified)
        assertFalse(CleanupSession.active(env.clock.nowMs()))
        assertFalse(CleanupSession.claimPress(run.id, env.clock.nowMs()))
        val before = env.ledger.getLong(CleanupKeys.RESULT_AT)
        f.watch(run)
        assertEquals(CleanupResult.NOTIFIED, result()); assertEquals(before, env.ledger.getLong(CleanupKeys.RESULT_AT))
    }

    // 5. 요청 식별자 한정
    @Test
    fun `이전 요청의 감시 작업은 새 요청의 상태를 읽지도 지우지도 기록하지도 않는다`() = runBlocking {
        val f = flow(Dev())
        val run1 = f.start(CleanupTrigger.UI)!!
        CleanupSession.cancel()
        val run2 = f.start(CleanupTrigger.UI)!!
        assertTrue(run2.id != run1.id)
        f.watch(run1)
        assertNull(result())
        assertTrue(CleanupSession.isCurrent(run2.id))
        assertTrue(CleanupSession.active(env.clock.nowMs()))
    }

    @Test
    fun `진행 중인 요청이 있으면 새 실행은 합쳐지고 포토를 다시 열지 않는다`() = runBlocking {
        val dev = Dev()
        val f = flow(dev)
        val a = f.start(CleanupTrigger.REMOTE)!!
        val b = f.start(CleanupTrigger.AUTO)!!
        assertFalse(a.merged); assertTrue(b.merged); assertEquals(a.id, b.id)
        assertEquals(1, dev.opened)
        CleanupSession.cancel()
        val c = f.start(CleanupTrigger.UI)!!
        assertFalse(c.merged); assertEquals(2, dev.opened)
    }

    // 7. performAction 실패
    @Test
    fun `누르기 동작이 실패하면 눌렀음이 아니라 CLICK_FAILED 를 기록한다`() = runBlocking {
        val f = flow(Dev()) { svcPress(env.clock.nowMs(), ok = false) }
        f.watch(f.start(CleanupTrigger.UI)!!)
        assertEquals(CleanupResult.CLICK_FAILED, result())
        assertTrue(com.pixelrelay.core.CleanupText.korean(CleanupResult.CLICK_FAILED).contains("실패"))
    }

    // 8. 수동 경로도 같은 열기·기록
    @Test
    fun `수동 열기도 후보를 기록하고 열기 방법을 저장한다`() = runBlocking {
        val sha = env.receiveWhole(randomBytes(300, 1500), "m.jpg")
        assertEquals(PhotosOpen.LAUNCHER, flow(Dev(via = PhotosOpen.LAUNCHER)).openManual())
        assertEquals("LAUNCHER", env.ledger.getString(CleanupKeys.OPEN_VIA))
        assertNotNull(env.ledger.get(sha)!!.candidateAt)
        assertNotNull(env.ledger.getLong(PhotosConfirmer.KEY_BUTTON_AT))
    }

    // 5. recordButton 은 유효한 후보를 지우지 않는다
    @Test
    fun `정리가 겹쳐 recordButton 이 다시 불려도 사라지는 중인 항목의 후보는 남아 정상 삭제로 확정된다`() = runBlocking {
        val sha = env.receiveWhole(randomBytes(700, 1600), "o.jpg")
        env.confirmer.recordButton()
        env.clock.advanceMin(5)
        val e = env.ledger.get(sha)!!
        File(e.path!!).delete(); env.index.removePath(e.path!!)
        env.confirmer.check()
        assertNotNull(env.ledger.get(sha)!!.missingSince)
        val cand = env.ledger.get(sha)!!.candidateAt
        env.confirmer.recordButton()
        assertEquals(cand, env.ledger.get(sha)!!.candidateAt)
        env.clock.advance(61_000)
        assertEquals(1, env.confirmer.check().confirmed)
        assertEquals(RecvState.PHOTOS_CONFIRMED, env.ledger.get(sha)!!.stateEnum)
    }

    @Test
    fun `이미 사라진 항목의 유효한 후보도 보존하고 창이 지난 후보는 보존하지 않는다`() = runBlocking {
        val sha = env.receiveWhole(randomBytes(700, 1601), "p.jpg")
        env.confirmer.recordButton()
        val first = env.ledger.get(sha)!!.candidateAt
        env.clock.advanceMin(10)
        val e = env.ledger.get(sha)!!
        File(e.path!!).delete(); env.index.removePath(e.path!!)
        env.confirmer.recordButton()
        assertEquals(first, env.ledger.get(sha)!!.candidateAt)
        env.clock.advanceMin(70)
        env.confirmer.recordButton()
        assertNull(env.ledger.get(sha)!!.candidateAt)
    }

    @Test
    fun `파일이 있는 항목은 다시 누르면 후보 시각이 새로 고쳐진다`() = runBlocking {
        val sha = env.receiveWhole(randomBytes(700, 1602), "q.jpg")
        env.confirmer.recordButton()
        val first = env.ledger.get(sha)!!.candidateAt!!
        env.clock.advanceMin(30)
        env.confirmer.recordButton()
        assertTrue(env.ledger.get(sha)!!.candidateAt!! > first)
    }

    // 6. 일괄 확정 결과에 확정한 sha 만
    @Test
    fun `일괄 확정 결과는 실제 확정한 sha 만 담고 보내기 장부는 그 항목만 동기화로 바뀐다`() = runBlocking {
        val vanished = (0 until 2).map { i ->
            val sha = env.receiveWhole(randomBytes(300 + i, 1700L + i), "v$i.jpg")
            env.ledger.transition(sha, RecvState.VANISHED_UNKNOWN); sha
        }
        val published = env.receiveWhole(randomBytes(250, 1799), "pub.jpg")
        val r = CommandHandler(env.ledger, ReceiverConfigStore(env.meta), env.confirmer, env.clock)
            .handle(RemoteCommand(RemoteCommand.CONFIRM_ALL_VANISHED, id = "all-r4"), remote = true)
        assertEquals(vanished.toSet(), r.shas.toSet())
        assertFalse(published in r.shas)

        val phone = SendEnv(TestDb.memory(), env.clock)
        try {
            val extra = "e".repeat(64)
            for (s in vanished + extra) phone.ledger.register(mkEntry(sha = s, state = "VANISHED_UNKNOWN"))
            phone.sync.applyChanges(r.shas.mapIndexed { i, s -> LedgerItem(s, "PHOTOS_CONFIRMED", 1, (i + 1).toLong()) })
            for (s in vanished) assertEquals("PHOTOS_CONFIRMED", phone.ledger.get(s)!!.state)
            assertEquals("VANISHED_UNKNOWN", phone.ledger.get(extra)!!.state)
        } finally { phone.db.close() }
    }
}
