package com.pixelrelay

import com.pixelrelay.core.AccountText
import com.pixelrelay.core.FreeTrialMachine
import com.pixelrelay.core.GuideFile
import com.pixelrelay.core.PixelStatus
import com.pixelrelay.core.ReceiverConfig
import com.pixelrelay.core.RecvState
import com.pixelrelay.core.SendState
import com.pixelrelay.core.SpaceVerdict
import com.pixelrelay.core.StartResult
import com.pixelrelay.core.StatusCounts
import com.pixelrelay.core.StepKind
import com.pixelrelay.core.TrialFile
import com.pixelrelay.core.TrialPhase
import com.pixelrelay.core.TrialSnapshot
import com.pixelrelay.core.TrialState
import com.pixelrelay.core.TrialText
import com.pixelrelay.core.UsageVerdict
import com.pixelrelay.core.WizardIds
import com.pixelrelay.core.WizardProgress
import com.pixelrelay.core.WizardSteps
import com.pixelrelay.core.Hashing
import com.pixelrelay.drive.DriveApi
import com.pixelrelay.drive.DriveAuthException
import com.pixelrelay.drive.Quota
import com.pixelrelay.receiver.ReadyFolder
import com.pixelrelay.sender.FreeTrialRunner
import com.pixelrelay.sender.TrialStartOutcome
import com.pixelrelay.sender.TrialStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 1단계-b: 설정 마법사 · 무료 업로드 확인 시험(설계서 11-1) 순수 논리 시험 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrialLogicTest {
    private val files = listOf(
        TrialFile(1, 1000, "a.jpg", "a".repeat(64)),
        TrialFile(2, 1000, "b.jpg", "b".repeat(64)),
        TrialFile(3, 1000, "c.jpg", "c".repeat(64)),
    )
    private val shas = files.map { it.sha!! }
    private fun arrived(st: String = "RECEIVED") = shas.associateWith { st }
    private fun snap(now: Long, states: Map<String, String> = arrived(), done: Long = 0, cleanup: Long = 0, usage: Long? = null) =
        TrialSnapshot(now, states, done, cleanup, usage)
    private fun started(startUsage: Long = 10_000, baseDone: Long = 0, baseCleanup: Long = 0): TrialState =
        (FreeTrialMachine.start(startUsage, files, baseDone, baseCleanup, 1_000) as StartResult.Started).state

    @Test
    fun `사용량 판정 - 증가량이 합계의 50 퍼센트 이상이면 차감됨`() {
        val j = { s: Long?, a: Long?, t: Long -> FreeTrialMachine.judgeUsage(s, a, t) }
        assertEquals(UsageVerdict.CHARGED, j(1000, 2500, 3000))      // 정확히 50%
        assertEquals(UsageVerdict.CHARGED, j(1000, 5000, 3000))
        assertEquals(UsageVerdict.FREE, j(1000, 2499, 3000))         // 50% 미만
        assertEquals(UsageVerdict.FREE, j(1000, 1000, 3000))
        assertEquals(UsageVerdict.FREE, j(1000, 900, 3000))          // 줄어든 경우도 무료
        assertEquals(UsageVerdict.UNKNOWN, j(null, 2000, 3000))
        assertEquals(UsageVerdict.UNKNOWN, j(1000, null, 3000))
        assertEquals(UsageVerdict.UNKNOWN, j(1000, 2000, 0))
    }

    @Test
    fun `시작 조건 - 계정 미연결이면 시작 불가 그리고 사진은 정확히 3장`() {
        assertEquals(StartResult.AccountRequired, FreeTrialMachine.start(null, files, 0, 0, 1))
        assertEquals(StartResult.WrongCount, FreeTrialMachine.start(5, files.take(2), 0, 0, 1))
        assertEquals(StartResult.WrongCount, FreeTrialMachine.start(5, files.map { it.copy(mediaId = 1) }, 0, 0, 1))
        assertEquals(TrialPhase.SENDING, started().phase)
    }

    @Test
    fun `전체 흐름 - 전송 완료 신호 즉시 측정 10분 뒤 측정 무료 공간 확보 정상`() {
        var s = started()
        // 전송이 덜 끝났으면 그대로
        s = FreeTrialMachine.advance(s, snap(2_000, arrived().toMutableMap().also { it[shas[2]] = "SENDING_LAN" }))
        assertEquals(TrialPhase.SENDING, s.phase)
        s = FreeTrialMachine.advance(s, snap(3_000))
        assertEquals(TrialPhase.AWAIT_PIXEL_DONE, s.phase)
        // 완료 신호가 기준값 이하(옛 값)면 무시
        s = FreeTrialMachine.advance(s, snap(4_000, done = 0))
        assertEquals(TrialPhase.AWAIT_PIXEL_DONE, s.phase)
        s = FreeTrialMachine.advance(s, snap(10_000, done = 777))
        assertEquals(TrialPhase.MEASURE_NOW, s.phase)
        assertTrue(FreeTrialMachine.wantsUsage(s, 10_000))
        // 값을 못 쟀으면 그대로, 쟀고 작게 늘었으면 10분 대기
        assertEquals(TrialPhase.MEASURE_NOW, FreeTrialMachine.advance(s, snap(10_500, usage = null)).phase)
        s = FreeTrialMachine.advance(s, snap(11_000, usage = 10_200))
        assertEquals(TrialPhase.WAIT_10MIN, s.phase)
        // 10분 전에는 측정을 요청하지 않고, 10분이 되면 요청
        assertFalse(FreeTrialMachine.wantsUsage(s, 10_000 + FreeTrialMachine.MEASURE_DELAY_MS - 1))
        assertTrue(FreeTrialMachine.wantsUsage(s, 10_000 + FreeTrialMachine.MEASURE_DELAY_MS))
        s = FreeTrialMachine.advance(s, snap(10_000 + FreeTrialMachine.MEASURE_DELAY_MS, usage = 10_300))
        assertEquals(TrialPhase.AWAIT_CLEANUP, s.phase)
        assertEquals(UsageVerdict.FREE, s.usageVerdict)
        // 공간 확보 시험
        s = FreeTrialMachine.advance(s, snap(700_000, cleanup = 0))
        assertEquals(TrialPhase.AWAIT_CLEANUP, s.phase)
        s = FreeTrialMachine.advance(s, snap(800_000, cleanup = 5))
        assertEquals(TrialPhase.WATCH_CLEANUP, s.phase)
        s = FreeTrialMachine.advance(s, snap(900_000, states = mapOf(shas[0] to "PHOTOS_CONFIRMED", shas[1] to "PHOTOS_CONFIRMED", shas[2] to "RECEIVED")))
        assertEquals(TrialPhase.WATCH_CLEANUP, s.phase)
        assertEquals(2, s.confirmedCount)
        s = FreeTrialMachine.advance(s, snap(950_000, states = arrived("PHOTOS_CONFIRMED")))
        assertEquals(TrialPhase.FINISHED, s.phase)
        assertEquals(SpaceVerdict.OK, s.spaceVerdict)
        assertTrue(TrialText.space(s)!!.contains("포토 확인 방식 정상"))
    }

    @Test
    fun `완료 직후 이미 50 퍼센트 이상 늘었으면 바로 차감됨으로 결론`() {
        var s = started(startUsage = 10_000)
        s = FreeTrialMachine.advance(s, snap(2_000))
        s = FreeTrialMachine.advance(s, snap(3_000, done = 5))
        s = FreeTrialMachine.advance(s, snap(4_000, usage = 12_000))      // +2000 >= 1500
        assertEquals(UsageVerdict.CHARGED, s.usageVerdict)
        assertEquals(TrialPhase.AWAIT_CLEANUP, s.phase)
        assertTrue(TrialText.usage(s)!!.contains("용량 차감됨(무료 아님)"))
    }

    @Test
    fun `10분 뒤에 늘어난 것도 잡는다(직후엔 작았다가 반영 지연)`() {
        var s = started(startUsage = 10_000)
        s = FreeTrialMachine.advance(s, snap(2_000))
        s = FreeTrialMachine.advance(s, snap(3_000, done = 5))
        s = FreeTrialMachine.advance(s, snap(4_000, usage = 10_100))
        assertEquals(TrialPhase.WAIT_10MIN, s.phase)
        s = FreeTrialMachine.advance(s, snap(3_000 + FreeTrialMachine.MEASURE_DELAY_MS, usage = 13_000))
        assertEquals(UsageVerdict.CHARGED, s.usageVerdict)
    }

    @Test
    fun `공간 확보 1시간이 지나도 안 지워지면 운영 조정 필요 그리고 일부만이면 부분`() {
        fun toWatch(): TrialState {
            var s = started()
            s = FreeTrialMachine.advance(s, snap(2_000)); s = FreeTrialMachine.advance(s, snap(3_000, done = 5))
            s = FreeTrialMachine.advance(s, snap(4_000, usage = 10_000))
            s = FreeTrialMachine.advance(s, snap(3_000 + FreeTrialMachine.MEASURE_DELAY_MS, usage = 10_000))
            return FreeTrialMachine.advance(s, snap(1_000_000, cleanup = 9))
        }
        val w = toWatch()
        assertEquals(TrialPhase.WATCH_CLEANUP, w.phase)
        val justBefore = FreeTrialMachine.advance(w, snap(1_000_000 + FreeTrialMachine.CLEANUP_WINDOW_MS - 1))
        assertEquals(TrialPhase.WATCH_CLEANUP, justBefore.phase)
        val none = FreeTrialMachine.advance(w, snap(1_000_000 + FreeTrialMachine.CLEANUP_WINDOW_MS))
        assertEquals(TrialPhase.FINISHED, none.phase); assertEquals(SpaceVerdict.NOT_ERASED, none.spaceVerdict)
        assertTrue(TrialText.space(none)!!.contains("최근 항목은 안 지움"))
        val part = FreeTrialMachine.advance(w, snap(1_000_000 + FreeTrialMachine.CLEANUP_WINDOW_MS,
            states = mapOf(shas[0] to "PHOTOS_CONFIRMED", shas[1] to "RECEIVED", shas[2] to "RECEIVED")))
        assertEquals(SpaceVerdict.PARTIAL, part.spaceVerdict)
    }

    @Test
    fun `시험 파일이 건너뜀 또는 실패로 끝나면 시험 실패`() {
        val s = FreeTrialMachine.advance(started(), snap(2_000, states = arrived().toMutableMap().also { it[shas[1]] = "SKIPPED" }))
        assertEquals(TrialPhase.FAILED, s.phase)
        assertTrue(TrialText.phase(s).contains("전송"))
    }

    @Test
    fun `시험 상태는 JSON 으로 저장했다 그대로 읽힌다`() {
        var s = started()
        s = FreeTrialMachine.advance(s, snap(2_000)); s = FreeTrialMachine.advance(s, snap(3_000, done = 5))
        s = FreeTrialMachine.advance(s, snap(4_000, usage = 10_100))
        val back = TrialState.fromJson(JSONObject(s.toJson().toString()))
        assertEquals(s, back)
        assertEquals(TrialPhase.IDLE, TrialState.fromJson(JSONObject()).phase)
    }

    @Test
    fun `날짜 위치 안내 문구와 반영 지연 문구가 있다`() {
        assertTrue(TrialText.DATE_LOCATION_GUIDE.contains("포토 앱에서 시험 사진을 열어"))
        assertTrue(TrialText.phase(started().copy(phase = TrialPhase.WAIT_10MIN)).contains("늦을 수 있"))
    }

    @Test
    fun `픽셀 상태 JSON 에 시험 신호가 실리고 옛 JSON 은 0 으로 읽힌다`() {
        val st = PixelStatus(1, 2, true, 30.0, StatusCounts(0, 0, 0, 0), 0, ReceiverConfig(), emptyList(), 5, "t", 9, trialDoneAt = 111, cleanupOpenedAt = 222)
        val back = PixelStatus.fromJson(JSONObject(st.toJson().toString()))
        assertEquals(111L, back.trialDoneAt); assertEquals(222L, back.cleanupOpenedAt)
        val old = JSONObject(st.toJson().toString()).also { it.remove("trialDoneAt"); it.remove("cleanupOpenedAt") }
        assertEquals(0L, PixelStatus.fromJson(old).trialDoneAt)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WizardLogicTest {
    @Test
    fun `역할별 단계 목록 - 확인 가능한 것은 자동 불가능한 것은 사용자 체크`() {
        val r = WizardSteps.forRole("receiver"); val s = WizardSteps.forRole("sender")
        assertEquals(
            listOf(WizardIds.PERMISSIONS, WizardIds.BATTERY, WizardIds.LOCK_SCREEN, WizardIds.SMART_STORAGE, WizardIds.PHOTOS_BACKUP, WizardIds.GOOGLE, WizardIds.PAIRING),
            r.map { it.id },
        )
        assertEquals(listOf(WizardIds.PERMISSIONS, WizardIds.BATTERY, WizardIds.PHOTOS_OFF, WizardIds.GOOGLE, WizardIds.PAIRING), s.map { it.id })
        for (st in r + s) when (st.id) {
            WizardIds.PERMISSIONS, WizardIds.BATTERY, WizardIds.GOOGLE, WizardIds.PAIRING -> assertEquals(StepKind.AUTO, st.kind)
            else -> assertEquals(StepKind.MANUAL, st.kind)
        }
        // 바로가기가 있는 단계는 인텐트가 안 열릴 때를 위한 글 안내를 가진다
        for (st in r + s) if (st.shortcut != null) assertTrue(st.id, st.fallback.isNotEmpty())
        assertTrue(r.single { it.id == WizardIds.PHOTOS_BACKUP }.body.contains("PixelRelay"))
    }

    @Test
    fun `진행 판정 - 자동 단계는 자동 결과로 수동 단계는 체크로만 끝난다`() {
        val steps = WizardSteps.forRole("receiver")
        var p = WizardProgress(emptyMap(), emptySet())
        assertEquals(0, p.doneCount(steps)); assertEquals(WizardIds.PERMISSIONS, p.firstUndone(steps)!!.id)
        // 수동 단계 id 를 자동 맵에 넣어도 인정되지 않는다
        p = WizardProgress(mapOf(WizardIds.LOCK_SCREEN to true), emptySet())
        assertEquals(0, p.doneCount(steps))
        // 자동 단계는 사용자 체크로 대신할 수 없다
        p = WizardProgress(emptyMap(), setOf(WizardIds.PERMISSIONS))
        assertEquals(0, p.doneCount(steps))
        p = WizardProgress(
            mapOf(WizardIds.PERMISSIONS to true, WizardIds.BATTERY to true, WizardIds.GOOGLE to true, WizardIds.PAIRING to true),
            setOf(WizardIds.LOCK_SCREEN, WizardIds.SMART_STORAGE),
        )
        assertEquals(6, p.doneCount(steps)); assertFalse(p.allDone(steps))
        assertEquals(WizardIds.PHOTOS_BACKUP, p.firstUndone(steps)!!.id)
        p = WizardProgress(
            mapOf(WizardIds.PERMISSIONS to true, WizardIds.BATTERY to true, WizardIds.GOOGLE to true, WizardIds.PAIRING to true),
            setOf(WizardIds.LOCK_SCREEN, WizardIds.SMART_STORAGE, WizardIds.PHOTOS_BACKUP),
        )
        assertTrue(p.allDone(steps)); assertNull(p.firstUndone(steps))
    }

    @Test
    fun `구글 계정 연결 실패 문구 - 앱 미등록 안내`() {
        val t = AccountText.failure(false, "x")
        assertTrue(t.contains("앱 등록이 아직 안 됨")); assertTrue(t.contains("개발자 설정")); assertTrue(t.contains("LAN"))
        assertFalse(AccountText.failure(true, null).contains("앱 등록"))
    }

    @Test
    fun `안내 이미지 규칙 - 접두어로 구분한다`() {
        assertTrue(GuideFile.isGuide(GuideFile.NAME))
        assertFalse(GuideFile.isGuide("IMG_0001.jpg"))
    }
}

/** 픽셀 폴더 준비 + 시험 진행기(주력 폰) 통합 시험 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrialIntegrationTest {
    private val dbs = ArrayList<com.pixelrelay.core.db.AppDb>()
    private val recvs = ArrayList<RecvEnv>()
    @After fun tearDown() { dbs.forEach { runCatching { it.close() } }; recvs.forEach { runCatching { it.close() } } }

    @Test
    fun `픽셀 폴더 준비 - 안내 이미지는 한 번만 만들고 장부에 오르지 않으며 포토 확인 판정 대상이 아니다`() = runBlocking {
        val env = RecvEnv().also { recvs += it }
        val dir = File(env.dir, "PixelRelay")
        var made = 0
        val png = { made++; byteArrayOf(1, 2, 3) }
        val f = ReadyFolder.prepare(dir, png)
        assertTrue(dir.isDirectory); assertEquals(GuideFile.NAME, f.name)
        assertEquals(3L, f.length())
        ReadyFolder.prepare(dir, png)
        assertEquals(1, made)                                           // 있으면 다시 만들지 않는다
        // 받기 장부에 없고, 포토 확인 판정을 돌려도 아무 일도 없다
        assertTrue(env.ledger.all().isEmpty())
        env.confirmer.check(); env.clock.advance(61_000); env.confirmer.check()
        assertTrue(env.ledger.all().isEmpty())
        assertTrue(f.exists())
    }

    private fun senderEnv(): SendEnv = SendEnv(TestDb.memory().also { dbs += it })

    private fun status(trialDone: Long, cleanup: Long, reportedAt: Long) =
        PixelStatus(1, 80, false, 30.0, StatusCounts(0, 0, 0, 0), 0, ReceiverConfig(), emptyList(), 0, "t", reportedAt, trialDone, cleanup)

    @Test
    fun `시험 전체 - 시작 사용량 기록 일반 경로 전송 완료 신호 두 번 측정 공간 확보 정상까지 영속 진행`() = runBlocking {
        val e = senderEnv()
        val recv = RecvEnv(e.clock).also { recvs += it }
        e.lanApi = CoreBackedPixelApi(recv)
        val runner = FreeTrialRunner(TrialStore(e.meta), e.drive, e.source, e.scanner, e.ledger, e.statusStore, e.clock)
        val bytes = (1..3).map { randomBytes(4000 + it, 800L + it) }
        val items = bytes.mapIndexed { i, b -> e.source.add("t$i.jpg", b) }
        e.drive.quota = Quota(15_000_000_000L, 5_000_000L)

        val out = runner.start(items.map { it.id })
        assertTrue(out is TrialStartOutcome.Started)
        val st0 = runner.current()!!
        assertEquals(TrialPhase.SENDING, st0.phase)
        assertEquals(5_000_000L, st0.startUsage)
        // 장부에 시험 표시
        bytes.forEach { assertTrue(e.entryFor(it)!!.trial) }

        // 일반 전송 경로(엔진)로 픽셀까지
        e.engine.runOnce()
        bytes.forEach { assertEquals(SendState.RECEIVED, e.entryFor(it)!!.stateEnum) }
        assertEquals(TrialPhase.AWAIT_PIXEL_DONE, runner.step()!!.phase)

        // 픽셀 "포토 백업 완료" 신호(상태로 전달) -> 즉시 측정 -> 10분 뒤 측정
        e.statusStore.save(status(trialDone = 123, cleanup = 0, reportedAt = e.clock.nowMs() + 1000), "lan")
        assertEquals(TrialPhase.MEASURE_NOW, runner.step()!!.phase)
        e.drive.quota = Quota(15_000_000_000L, 5_000_300L)
        assertEquals(TrialPhase.WAIT_10MIN, runner.step()!!.phase)
        e.clock.advanceMin(11)
        val afterUsage = runner.step()!!
        assertEquals(TrialPhase.AWAIT_CLEANUP, afterUsage.phase)
        assertEquals(UsageVerdict.FREE, afterUsage.usageVerdict)

        // 공간 확보 시험: 픽셀이 버튼 기록 -> 시험 파일이 포토 확인됨 -> 정상
        e.statusStore.save(status(trialDone = 123, cleanup = 456, reportedAt = e.clock.nowMs() + 2000), "lan")
        assertEquals(TrialPhase.WATCH_CLEANUP, runner.step()!!.phase)
        bytes.forEach { recv.ledger.transition(Hashing.sha256Hex(it), RecvState.PHOTOS_CONFIRMED) }
        e.engine.runOnce()                                              // 동기화로 보내기 장부도 포토 확인됨
        val fin = runner.step()!!
        assertEquals(TrialPhase.FINISHED, fin.phase)
        assertEquals(SpaceVerdict.OK, fin.spaceVerdict)

        // 앱을 다시 열어도(새 진행기) 결과가 남아 있다 + 다시 할 수 있다
        val again = FreeTrialRunner(TrialStore(e.meta), e.drive, e.source, e.scanner, e.ledger, e.statusStore, e.clock)
        assertEquals(TrialPhase.FINISHED, again.current()!!.phase)
        again.reset()
        assertNull(again.current()?.takeIf { it.phase != TrialPhase.IDLE })
    }

    @Test
    fun `구글 계정 미연결이면 시험을 시작하지 못하고 사진도 등록하지 않는다`() = runBlocking {
        val e = senderEnv()
        val noAuth = object : DriveApi by e.drive { override suspend fun about(): Quota = throw DriveAuthException(null, "no client") }
        val runner = FreeTrialRunner(TrialStore(e.meta), noAuth, e.source, e.scanner, e.ledger, e.statusStore, e.clock)
        val items = (1..3).map { e.source.add("n$it.jpg", randomBytes(1000, 900L + it)) }
        val out = runner.start(items.map { it.id })
        assertTrue(out is TrialStartOutcome.AccountRequired)
        assertNull(runner.current())
        assertTrue(e.ledger.sendable().isEmpty())
    }

    @Test
    fun `이미 장부에 있는 사진 3장이 아닌 선택은 거절한다`() = runBlocking {
        val e = senderEnv()
        val runner = FreeTrialRunner(TrialStore(e.meta), e.drive, e.source, e.scanner, e.ledger, e.statusStore, e.clock)
        val b = randomBytes(1000, 950)
        val a = e.source.add("a.jpg", b); val c = e.source.add("c.jpg", randomBytes(1000, 951)); val d = e.source.add("d.jpg", randomBytes(1000, 952))
        e.registerNow()                                                  // a 가 이미 장부에 올라 있음
        val r1 = runner.start(listOf(a.id, c.id, d.id))
        assertTrue(r1 is TrialStartOutcome.Rejected)
        assertNull(runner.current())
        val r2 = runner.start(listOf(c.id, d.id))
        assertTrue(r2 is TrialStartOutcome.Rejected)
        assertNotNull(e.entryFor(b))
        assertFalse(e.entryFor(b)!!.trial)
    }
}
