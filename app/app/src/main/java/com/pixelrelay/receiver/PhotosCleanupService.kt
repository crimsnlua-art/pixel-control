package com.pixelrelay.receiver

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.pixelrelay.Graph
import com.pixelrelay.Notifs
import com.pixelrelay.core.AccessibilityCheck
import com.pixelrelay.core.CleanupButton
import com.pixelrelay.core.CleanupPlanner
import com.pixelrelay.core.ScreenSnapshot
import com.pixelrelay.core.SnapNode
import com.pixelrelay.core.CleanupSession
import com.pixelrelay.core.PhotosOpen
import com.pixelrelay.ui.PhotosLink
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * 받기 역할(픽셀)에서만 동작하는 접근성 서비스. 대상 패키지는 구글 포토 하나뿐이다(res/xml/photos_cleanup_service.xml).
 * 우리 앱이 2분 안에 공간 확보를 시작한 경우에만, 포토 화면에서 텍스트가 "여유 공간"으로 시작해 "확보"로 끝나는
 * 클릭 가능한 버튼 하나를 누른다. 다른 버튼(구매·삭제·휴지통 등)은 누르지 않는다. 같은 요청에서 두 번 누르지 않는다.
 */
class PhotosCleanupService : AccessibilityService() {
    companion object {
        const val CLASS_NAME = "com.pixelrelay.receiver.PhotosCleanupService"
        private const val MAX_NODES = 2000
        private const val MAX_DEPTH = 80
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Graph.init(applicationContext)
        // 받기 역할이 아니면 쓰지 않는다(보내기 폰에서 켜져 있어도 스스로 끈다)
        if (kotlinx.coroutines.runBlocking { Graph.settings.role() } != com.pixelrelay.ui.SettingsStore.ROLE_RECEIVER) disableSelf()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() != CleanupButton.PHOTOS_PACKAGE) return
        val now = Graph.clock.nowMs()
        val rid = CleanupSession.activeId(now)
        if (rid == 0L) return
        CleanupSession.noteSeen(rid, now)
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != CleanupButton.PHOTOS_PACKAGE) return
        press(rid, root)
    }

    private fun press(rid: Long, root: AccessibilityNodeInfo) {
        val infos = ArrayList<AccessibilityNodeInfo>()
        val nodes = ArrayList<SnapNode>()
        val complete = snapshot(root, -1, 0, infos, nodes)
        // 화면을 끝까지 못 봤거나 안전·후보 조건을 하나라도 못 채우면 아무것도 누르지 않는다(판정은 순수 함수 CleanupPlanner)
        when (val d = CleanupPlanner.decide(ScreenSnapshot(nodes, complete))) {
            is CleanupPlanner.Decision.Skip -> when (d.why) {
                CleanupPlanner.Why.PROBLEM -> CleanupSession.noteBlock(rid, d.detail ?: "")
                CleanupPlanner.Why.NO_BACKUP_TEXT -> CleanupSession.noteNoBackupText(rid)
                else -> {}
            }
            is CleanupPlanner.Decision.Press -> {
                CleanupSession.clearFlags(rid)
                // 요청 확인 -> 권한 획득 -> performAction -> 결과 보고를 시작·취소와 같은 직렬 경계 안에서 한다(취소 뒤에는 클릭하지 않음)
                val target = infos[d.nodeId]
                kotlinx.coroutines.runBlocking {
                    Graph.receiver.cleanup.performPress(rid) { target.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                }
            }
        }
    }

    /** 화면 전체를 사본으로 옮긴다. 노드 수·깊이 제한을 넘거나 자식을 못 가져오면 false(일부만 본 것). */
    private fun snapshot(n: AccessibilityNodeInfo, parent: Int, depth: Int, infos: MutableList<AccessibilityNodeInfo>, out: MutableList<SnapNode>): Boolean {
        if (out.size >= MAX_NODES || depth > MAX_DEPTH) return false
        val id = out.size
        infos += n
        out += SnapNode(id, parent, n.text?.toString(), n.contentDescription?.toString(), n.isClickable, n.isEnabled, n.isVisibleToUser)
        var complete = true
        for (i in 0 until n.childCount) {
            val c = n.getChild(i)
            if (c == null) { complete = false; continue }
            if (!snapshot(c, id, depth + 1, infos, out)) complete = false
        }
        return complete
    }
}

/** 안드로이드 기기 쪽 구현. */
class AndroidCleanupDevice(private val ctx: Context) : CleanupDevice {
    override fun accessibilityOn(): Boolean = isAccessibilityOn(ctx)

    override fun screenOn(): Boolean = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    override fun openPhotos(): PhotosOpen = PhotosLink.openFreeUp(ctx)

    override fun notifyManual() = Notifs.postCleanupNeeded(ctx)

    companion object {
        fun isAccessibilityOn(ctx: Context): Boolean {
            val on = Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
            if (!on) return false
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            return AccessibilityCheck.enabled(list, ctx.packageName, PhotosCleanupService.CLASS_NAME)
        }
    }
}

/**
 * "공간 확보 필요 — 누르면 실행" 알림을 누르면 열리는 투명 화면. 사람이 누른 것이라 안드로이드 10 에서도
 * 이 화면이 떠 있는 동안 포토를 바로 열 수 있다. 포토를 열고 곧 닫힌다.
 */
class CleanupTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Graph.init(applicationContext)
        val flow = Graph.receiver.cleanup
        // 이 화면이 떠 있는 동안 포토를 열어야 하므로 열기까지는 이 안에서 기다린 뒤 닫는다
        MainScope().launch {
            try {
                flow.start(CleanupTrigger.UI)?.let { flow.watchAsync(it) }
            } catch (e: Exception) { /* 결과는 기록돼 있다 */ }
            finish()
        }
    }
}
