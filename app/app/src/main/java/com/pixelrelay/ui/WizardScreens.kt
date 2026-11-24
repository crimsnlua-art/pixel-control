package com.pixelrelay.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.pixelrelay.Graph
import com.pixelrelay.core.Shortcut
import com.pixelrelay.core.StepKind
import com.pixelrelay.core.WizardIds
import com.pixelrelay.core.WizardProgress
import com.pixelrelay.core.WizardStep
import com.pixelrelay.core.WizardSteps
import com.pixelrelay.receiver.ReadyFolder
import kotlinx.coroutines.launch

/** 시스템 설정 바로가기. 기기에서 안 열리면 false 를 돌려주고, 화면은 글 안내로 대체한다. */
object Shortcuts {
    fun open(ctx: Context, s: Shortcut): Boolean = runCatching {
        when (s) {
            Shortcut.BATTERY_OPT -> startFirst(
                ctx,
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
            Shortcut.LOCK_SCREEN -> startFirst(ctx, Intent(Settings.ACTION_SECURITY_SETTINGS))
            Shortcut.STORAGE_SETTINGS -> startFirst(ctx, Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
            Shortcut.OPEN_PHOTOS -> {
                val i = ctx.packageManager.getLaunchIntentForPackage("com.google.android.apps.photos")
                if (i == null) false else { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
            }
        }
    }.getOrDefault(false)

    private fun startFirst(ctx: Context, vararg intents: Intent): Boolean {
        for (i in intents) {
            if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return true
        }
        return false
    }
}

/** 앱이 확인할 수 있는 단계의 자동 판정. */
object WizardAutoChecks {
    private fun granted(ctx: Context, perms: List<String>) =
        perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    /** 기기에 로컬 네트워크 권한(Android 16+)이 있으면 필수에 넣는다: 없으면 LAN 짝짓기·전송이 막힌다. */
    fun senderPermissions(ctx: Context): List<String> =
        PermLists.media() + Manifest.permission.ACCESS_MEDIA_LOCATION + PermLists.notifications() +
            (if (LocalNetworkPermission.definedOnDevice(ctx)) listOf(LocalNetworkPermission.NAME) else emptyList())

    fun receiverPermissions(): List<String> =
        listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE) + PermLists.notifications()

    suspend fun evaluate(ctx: Context, role: String, steps: List<WizardStep>): Map<String, Boolean> {
        val out = HashMap<String, Boolean>()
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        for (s in steps) {
            if (s.kind != StepKind.AUTO) continue
            out[s.id] = when (s.id) {
                WizardIds.PERMISSIONS -> granted(ctx, if (role == SettingsStore.ROLE_RECEIVER) receiverPermissions() else senderPermissions(ctx))
                WizardIds.BATTERY -> pm.isIgnoringBatteryOptimizations(ctx.packageName)
                WizardIds.GOOGLE ->
                    ((Graph.meta.getLong("googleLinked") ?: 0L) == 1L && (Graph.meta.getLong("driveAuthProblem") ?: 0L) != 1L) ||
                        (role == SettingsStore.ROLE_SENDER && (Graph.meta.getLong(com.pixelrelay.core.DriveUse.KEY_SKIPPED) ?: 0L) == 1L)
                WizardIds.PAIRING ->
                    if (role == SettingsStore.ROLE_RECEIVER) Graph.settings.receiverKeys().isNotEmpty() else Graph.settings.pairing() != null
                else -> false
            }
        }
        return out
    }
}

/**
 * 역할별 설정 마법사(설계서 11-1). 끝난 단계는 자동/체크로 표시하고, 설정 화면에서 다시 열 수 있다.
 * [onClose] 는 "닫기"를 누르면 불린다(보았다는 표시는 이 안에서 저장).
 */
@Composable
fun SetupWizard(role: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val steps = remember(role) { WizardSteps.forRole(role) }
    val auto by poll(1500) { WizardAutoChecks.evaluate(ctx, role, steps) }
    val manual by poll(800) { Graph.settings.wizardManual(role) }
    val progress = WizardProgress(auto.orEmpty(), manual.orEmpty())
    var fallbacks by remember { mutableStateOf(setOf<String>()) }
    var info by remember { mutableStateOf("") }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("설정 마법사", style = MaterialTheme.typography.headlineSmall)
        Text("${progress.doneCount(steps)} / ${steps.size} 단계 완료" + if (progress.allDone(steps)) " — 모두 끝났습니다." else "")
        progress.firstUndone(steps)?.let { Text("다음 할 일: ${it.title}", color = MaterialTheme.colorScheme.primary) }

        steps.forEach { step ->
            val done = progress.isDone(step)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${if (done) "✔" else "□"} ${step.title}", style = MaterialTheme.typography.titleMedium)
                    Text(step.body)
                    when (step.id) {
                        WizardIds.PERMISSIONS ->
                            if (role == SettingsStore.ROLE_RECEIVER) {
                                PermissionRow("저장소(파일 쓰기)", listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE))
                                if (PermLists.notifications().isNotEmpty()) PermissionRow("알림", PermLists.notifications())
                            } else {
                                PermissionRow("사진·영상 읽기", PermLists.media())
                                PermissionRow("사진 위치 정보(원본 GPS)", listOf(Manifest.permission.ACCESS_MEDIA_LOCATION))
                                if (LocalNetworkPermission.definedOnDevice(ctx)) PermissionRow("로컬 네트워크(Android 16+, 픽셀 연결에 필요)", listOf(LocalNetworkPermission.NAME))
                                if (PermLists.notifications().isNotEmpty()) PermissionRow("알림", PermLists.notifications())
                            }
                        WizardIds.GOOGLE -> AccountSection(allowSkip = role == SettingsStore.ROLE_SENDER)
                        WizardIds.PHOTOS_BACKUP -> OutlinedButton(onClick = {
                            scope.launch {
                                info = runCatching { ReadyFolder.prepareAndScan(ctx); "Pictures/PixelRelay 폴더와 안내 이미지를 준비했습니다." }
                                    .getOrElse { "폴더를 준비하지 못했습니다: ${it.message}" }
                            }
                        }) { Text("폴더·안내 이미지 준비") }
                        else -> {}
                    }
                    step.shortcut?.let { sc ->
                        OutlinedButton(onClick = {
                            if (!Shortcuts.open(ctx, sc)) fallbacks = fallbacks + step.id
                        }) { Text(shortcutLabel(sc)) }
                    }
                    if (step.id in fallbacks) Text("바로가기를 열 수 없습니다. ${step.fallback}", color = MaterialTheme.colorScheme.error)
                    if (step.kind == StepKind.MANUAL) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(checked = step.id in manual.orEmpty(), onCheckedChange = { on ->
                                scope.launch { Graph.settings.setWizardManual(role, step.id, on) }
                            })
                            Text("했어요(앱이 확인할 수 없는 단계라 직접 체크)")
                        }
                    }
                }
            }
        }
        if (info.isNotEmpty()) Text(info)
        if (role == SettingsStore.ROLE_SENDER) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("기존 사진도 보내고 싶다면", style = MaterialTheme.typography.titleMedium)
                    Text("기본은 새로 찍은 사진·영상만 보냅니다. 폴더에 이미 있는 사진도 보내려면 '설정' 탭의 '기존 사진도 보내기'에서 전부 또는 날짜를 고르세요. 보낼 폴더도 같은 화면에서 체크로 고릅니다.")
                }
            }
        }
        Button(onClick = {
            scope.launch { Graph.settings.setWizardSeen(role); onClose() }
        }) { Text(if (progress.allDone(steps)) "마치기" else "나중에 하기(설정에서 다시 열 수 있어요)") }
    }
}

private fun shortcutLabel(s: Shortcut) = when (s) {
    Shortcut.BATTERY_OPT -> "배터리 최적화 설정 열기"
    Shortcut.LOCK_SCREEN -> "보안/화면 잠금 설정 열기"
    Shortcut.STORAGE_SETTINGS -> "저장용량 설정 열기"
    Shortcut.OPEN_PHOTOS -> "구글 포토 열기"
}
