package com.pixelrelay.receiver

import com.pixelrelay.core.CleanupGate
import com.pixelrelay.core.CleanupKeys
import com.pixelrelay.core.CleanupResult
import com.pixelrelay.core.CleanupSession
import com.pixelrelay.core.PhotosOpen
import com.pixelrelay.core.Clock
import com.pixelrelay.core.ReceiverLedger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/** 기기 쪽 일(안드로이드 구현 / 테스트 가짜). */
interface CleanupDevice {
    /** 우리 접근성 서비스가 켜져 있는가 */
    fun accessibilityOn(): Boolean

    /** 화면이 켜져 있는가(꺼져 있으면 포토를 열어도 눌러 줄 수 없다) */
    fun screenOn(): Boolean

    /** 포토의 공간 확보 화면을 연다(직접 링크, 실패하면 일반 실행 화면). 못 열면 FAILED */
    fun openPhotos(): PhotosOpen

    /** "공간 확보 필요 — 누르면 실행" 알림을 띄운다 */
    fun notifyManual()
}

enum class CleanupTrigger {
    /** 주력 폰의 FREE_UP_SPACE 명령 */
    REMOTE,

    /** 남은 공간·대기 조건에 따른 자동 실행 */
    AUTO,

    /** 픽셀 화면의 버튼 또는 알림을 사람이 누름(우리 앱 화면이 떠 있어 포토를 바로 열 수 있다) */
    UI,
}

/** 포토를 연 뒤 지켜볼 요청 한 건. id 는 CleanupSession 의 요청 식별자. merged = 이미 진행 중인 요청에 합쳐진 것(새로 열지 않았음). */
data class CleanupRun(val id: Long, val startedAt: Long, val trigger: CleanupTrigger, val merged: Boolean = false)

/**
 * 픽셀 자동 공간 확보 절차:
 * 1) 접근성이 꺼져 있으면 "권한 없음"을 기록하고(자동·원격이면 알림) 끝.
 * 2) 화면이 꺼져 있거나(자동·원격) 바로 열 수 없으면 "누르면 실행" 알림으로 대신한다. 알림으로 바꿀 때는 활성 요청을 무효화한다.
 * 3) 이미 진행 중인 요청이 있으면 새로 열지 않고 합친다(실행 직렬화).
 * 4) recordButton()(CLEANUP_OPENED 와 같은 기록) -> 요청 시작 -> 포토를 연다.
 * 5) 접근성 서비스가 2분 안에 버튼을 누르면 "눌렀음"(누르기 실패면 CLICK_FAILED), 못 찾으면 "버튼 못 찾음"을 기록한다.
 * 모든 읽기·초기화·기록은 요청 식별자로 한정해, 이전 요청을 지켜보던 작업이 새 요청을 건드리지 못한다.
 * 안드로이드 10 은 백그라운드에서 화면을 여는 것을 막으므로, 사람이 누른 경우가 아니면 포토 창이 15초 안에 안 보일 때 알림으로 바꾼다.
 */
class CleanupFlow(
    private val ledger: ReceiverLedger,
    private val confirmer: PhotosConfirmer,
    private val clock: Clock,
    private val device: CleanupDevice,
    private val scope: CoroutineScope,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    companion object {
        const val SEEN_WAIT_MS = 15_000L
        const val POLL_MS = 1_000L
        const val STALE_GRACE_MS = 30_000L
    }

    /** 시작·취소·종료·결과 저장·알림·activeRun 정리·서비스의 클릭을 한 줄로 세우는 직렬 경계 */
    private val gate get() = CleanupSession.gate
    @Volatile private var activeRun: CleanupRun? = null

    /** 결과 코드와 시각을 한 트랜잭션으로 저장한다. */
    private suspend fun saveResult(code: String) {
        val now = clock.nowMs()
        ledger.inTransaction {
            ledger.putString(CleanupKeys.RESULT, code)
            ledger.putLong(CleanupKeys.RESULT_AT, now)
        }
    }

    /** 포토를 열고 어떤 방법으로 열렸는지(직접 링크 / 일반 실행 화면으로 대신 / 실패)를 기록한다. */
    private suspend fun openAndNote(): PhotosOpen {
        val r = device.openPhotos()
        ledger.putString(CleanupKeys.OPEN_VIA, r.name)
        return r
    }

    /** 픽셀 화면의 수동 "포토 공간 확보 열기": 후보 기록 + 같은 열기·기록 경로(누르는 것은 사람이 한다). */
    suspend fun openManual(): PhotosOpen = gate.withLock {
        confirmer.recordButton()
        openAndNote()
    }

    /** 바로 돌려주고 일은 뒤에서 한다(명령 처리·자동 실행용). */
    fun startAsync(trigger: CleanupTrigger) {
        scope.launch {
            try { start(trigger)?.let { if (!it.merged) watch(it) } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { /* 다음에 */ }
        }
    }

    /** 이미 열린 요청을 뒤에서 지켜본다(알림 화면처럼 열기까지만 앞에서 하는 경우). 합쳐진 요청은 이미 누가 지켜보고 있다. */
    fun watchAsync(run: CleanupRun) {
        if (run.merged) return
        scope.launch {
            try { watch(run) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { /* 다음에 */ }
        }
    }

    /** 준비 + 포토 열기까지. 지켜볼 요청이 없으면(이미 결과를 기록했으면) null. 동시에 둘이 열지 않는다. */
    suspend fun start(trigger: CleanupTrigger): CleanupRun? = gate.withLock { startLocked(trigger) }

    private suspend fun startLocked(trigger: CleanupTrigger): CleanupRun? {
        if (!device.accessibilityOn()) {
            CleanupSession.cancel(); activeRun = null
            saveResult(CleanupResult.NO_PERMISSION)
            if (trigger == CleanupTrigger.UI) {
                // 사람이 직접 눌렀으면 눌러 줄 수는 없어도 기존 버튼처럼 기록하고 포토를 열어 준다(사용자가 직접 실행)
                confirmer.recordButton()
                openAndNote()
            } else device.notifyManual()
            return null
        }
        if (trigger != CleanupTrigger.UI && !device.screenOn()) {
            CleanupSession.cancel(); activeRun = null   // 알림으로 바꾸므로 진행 중이던 요청도 더는 누르지 않는다
            saveResult(CleanupResult.NOTIFIED)
            device.notifyManual()
            return null
        }
        val cur = activeRun
        if (cur != null) {
            // 결과 기록이 끝나(settle) activeRun 이 지워질 때까지는 진행 중으로 보고 합친다. 지켜보는 쪽이 사라진 요청만 오래되면 버린다.
            if (CleanupSession.isCurrent(cur.id) && clock.nowMs() - cur.startedAt <= CleanupGate.WINDOW_MS + STALE_GRACE_MS) return cur.copy(merged = true)
            CleanupSession.cancel(); activeRun = null
        }
        confirmer.recordButton()
        val now = clock.nowMs()
        val id = CleanupSession.begin(now)
        ledger.putLong(CleanupKeys.REQUEST_AT, now)
        if (openAndNote() == PhotosOpen.FAILED) {
            CleanupSession.cancelIf(id)
            saveResult(CleanupResult.OPEN_FAILED)
            return null
        }
        return CleanupRun(id, now, trigger).also { activeRun = it }
    }

    /**
     * 서비스가 부른다. 직렬 경계 안에서 (요청이 지금도 현재인지 확인 -> 권한 획득 -> performAction -> 결과 보고)를 한 덩어리로 한다.
     * 취소·종료가 끝난 요청으로는 클릭 함수가 불리지 않는다. 눌렀으면 true.
     */
    suspend fun performPress(rid: Long, click: () -> Boolean): Boolean = gate.withLock {
        if (!CleanupSession.claimPress(rid, clock.nowMs())) return@withLock false
        val ok = try { click() } catch (e: Exception) { false }
        CleanupSession.reportClick(rid, ok)
        true
    }

    private enum class Settle { DONE, NOTIFY }

    /**
     * 요청 종료: 직렬 경계 안에서 요청 식별자를 확인한 뒤 요청 무효화·결과 저장(코드+시각 한 트랜잭션)·알림·activeRun 정리를 한다.
     * 이미 대체되거나 취소된 오래된 요청은 아무것도 기록하지도 알리지도 않는다.
     */
    private suspend fun settle(run: CleanupRun, mode: Settle) = gate.withLock {
        try {
            val s = CleanupSession.snapshot(run.id) ?: return@withLock
            val reason = s.blockReason
            var notify = false
            val code = when {
                s.pressed -> if (s.clickResult > 0) CleanupResult.PRESSED else CleanupResult.CLICK_FAILED   // 누른 뒤에는 알림으로 바꾸지 않는다
                mode == Settle.NOTIFY -> { notify = true; CleanupResult.NOTIFIED }
                reason != null -> CleanupResult.problem(reason)
                s.noBackupText -> CleanupResult.NO_BACKUP_TEXT
                else -> CleanupResult.NO_BUTTON
            }
            if (!CleanupSession.cancelIf(run.id)) return@withLock
            saveResult(code)
            if (notify) device.notifyManual()
        } finally {
            if (activeRun?.id == run.id) activeRun = null
        }
    }

    /** 최대 2분 지켜보며 결과를 기록한다. */
    suspend fun watch(run: CleanupRun) {
        while (clock.nowMs() - run.startedAt <= CleanupGate.WINDOW_MS) {
            val s = CleanupSession.snapshot(run.id)
            if (s == null) { settle(run, Settle.DONE); return }              // 취소됐거나 새 요청이 대신함(기록 없음)
            if (s.pressed) { settle(run, Settle.DONE); return }
            if (!s.seen && run.trigger != CleanupTrigger.UI && clock.nowMs() - run.startedAt >= SEEN_WAIT_MS) {
                // 백그라운드에서 포토가 안 열렸다: 알림으로 대신(요청을 무효화해 사람이 알림을 눌러 새로 시작할 때까지 아무것도 누르지 않는다)
                settle(run, Settle.NOTIFY); return
            }
            sleep(POLL_MS)
        }
        settle(run, Settle.DONE)
    }
}
