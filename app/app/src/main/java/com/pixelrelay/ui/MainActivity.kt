package com.pixelrelay.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.pixelrelay.Graph
import com.pixelrelay.Notifs
import com.pixelrelay.drive.DriveAuthException
import com.pixelrelay.receiver.ReceiverService
import com.pixelrelay.sender.SenderScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Graph.init(this)
        Notifs.ensureChannels(this)
        setContent { MaterialTheme { Root() } }
    }
}

@Composable
private fun Root() {
    val role by remember { Graph.settings.roleFlow.map { it ?: "" } }.collectAsState(initial = "?")
    when (role) {
        "?" -> Text("불러오는 중...", Modifier.padding(24.dp))
        "" -> RolePicker()
        SettingsStore.ROLE_SENDER -> SenderApp()
        SettingsStore.ROLE_RECEIVER -> ReceiverApp()
        else -> RolePicker()
    }
}

@Composable
private fun RolePicker() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val canReceive = Build.VERSION.SDK_INT <= 29
    Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("픽셀중계", style = MaterialTheme.typography.headlineMedium)
        Text("이 기기의 역할을 고르세요.")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("보내기 (주력 폰)", style = MaterialTheme.typography.titleMedium)
                Text("찍은 사진·영상을 원본 그대로 픽셀로 보냅니다. 집 와이파이에서는 직접, 그 밖에서는 구글 드라이브를 거칩니다.")
                Button(onClick = {
                    scope.launch {
                        Graph.settings.setRole(SettingsStore.ROLE_SENDER)
                        SenderScheduler.ensure(ctx)
                    }
                }) { Text("이 기기를 보내기로 사용") }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("받기 (픽셀 1세대)", style = MaterialTheme.typography.titleMedium)
                Text("받은 파일을 Pictures/PixelRelay 에 두고, 구글 포토가 올리도록 합니다. 안드로이드 10 이하 기기에서만 고를 수 있습니다.")
                Button(enabled = canReceive, onClick = {
                    scope.launch {
                        Graph.settings.setRole(SettingsStore.ROLE_RECEIVER)
                        ReceiverService.start(ctx)
                    }
                }) { Text("이 기기를 받기로 사용") }
                if (!canReceive) Text("이 기기는 안드로이드 ${Build.VERSION.RELEASE} 라서 받기를 고를 수 없습니다(상시 서버 제한).", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// ---------------- 공용 도우미 ----------------

fun fmtBytes(b: Long): String = when {
    b >= 1L shl 30 -> String.format("%.1fGB", b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> String.format("%.1fMB", b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> String.format("%.0fKB", b / 1024.0)
    else -> "${b}B"
}

fun fmtAgo(thenMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (thenMs <= 0) return "없음"
    val s = (nowMs - thenMs) / 1000
    return when {
        s < 60 -> "${s}초 전"
        s < 3600 -> "${s / 60}분 전"
        s < 86400 -> "${s / 3600}시간 전"
        else -> "${s / 86400}일 전"
    }
}

@Composable
fun <T> poll(intervalMs: Long, block: suspend () -> T): State<T?> = produceState<T?>(initialValue = null) {
    while (true) {
        value = try { block() } catch (e: Exception) { value }
        delay(intervalMs)
    }
}

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

fun localIpv4(): String? = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address && it.isSiteLocalAddress }
        ?.hostAddress
}.getOrNull()

/** 권한 한 줄. 허용돼 있으면 표시만, 아니면 요청 버튼. */
@Composable
fun PermissionRow(label: String, perms: List<String>) {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val ok = remember(tick) { perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED } }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("$label: ${if (ok) "허용됨" else "필요"}", Modifier.weight(1f))
        if (!ok) OutlinedButton(onClick = { launcher.launch(perms.toTypedArray()) }) { Text("요청") }
    }
}

/** 구글 드라이브(appDataFolder) 연결. 동의 화면이 필요하면 시스템 승인 창을 연다. */
@Composable
fun AccountSection(allowSkip: Boolean = false) {
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    val authProblem by poll(3000) { (Graph.meta.getLong("driveAuthProblem") ?: 0L) == 1L }
    val skipped by poll(3000) { allowSkip && (Graph.meta.getLong(com.pixelrelay.core.DriveUse.KEY_SKIPPED) ?: 0L) == 1L }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        scope.launch {
            msg = try {
                Graph.tokens.token(false)
                Graph.meta.putLong("driveAuthProblem", 0)
                Graph.meta.putLong("googleLinked", 1)
                Graph.meta.putLong(com.pixelrelay.core.DriveUse.KEY_SKIPPED, 0)
                "연결됨"
            } catch (e: Exception) { "승인이 끝나지 않았습니다: ${e.message}" }
        }
    }
    Section("구글 계정(드라이브) 연결") {
        Text("집 밖 전송과 상태 확인에 씁니다. 두 폰 모두 같은 구글 계정으로 연결하세요. (앱 전용 숨은 폴더만 사용)")
        if (authProblem == true && skipped != true) Text("인증이 필요합니다. 아래 버튼으로 다시 승인하세요.", color = MaterialTheme.colorScheme.error)
        if (skipped == true) Text("연결 안 함으로 설정됨: 집 와이파이(LAN)에서만 보냅니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = {
            scope.launch {
                msg = try {
                    Graph.tokens.token(false)
                    Graph.meta.putLong("driveAuthProblem", 0)
                    Graph.meta.putLong("googleLinked", 1)
                    Graph.meta.putLong(com.pixelrelay.core.DriveUse.KEY_SKIPPED, 0)
                    "연결됨"
                } catch (e: DriveAuthException) {
                    val pi = e.pendingIntent
                    if (pi != null) { launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build()); "승인 창을 열었습니다" }
                    else com.pixelrelay.core.AccountText.failure(false, e.message)
                } catch (e: Exception) { "오류: ${e.message}" }
            }
        }) { Text("구글 계정 연결") }
        if (!allowSkip) {
        } else if (skipped == true) {
            OutlinedButton(onClick = { scope.launch { Graph.meta.putLong(com.pixelrelay.core.DriveUse.KEY_SKIPPED, 0); msg = "연결 안 함을 해제했습니다" } }) { Text("연결 안 함 해제") }
        } else {
            OutlinedButton(onClick = {
                scope.launch { Graph.meta.putLong(com.pixelrelay.core.DriveUse.KEY_SKIPPED, 1); msg = "연결 안 함으로 설정했습니다(집 와이파이 전용). 집 밖 전송은 쓰지 않습니다." }
            }) { Text("연결 안 함(집 와이파이에서만 보내기)") }
        }
        if (msg.isNotEmpty()) Text(msg)
    }
}

/** 배터리 최적화 제외 요청 버튼. */
@Composable
fun BatteryOptRow() {
    val ctx = LocalContext.current
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    val ignoring = pm.isIgnoringBatteryOptimizations(ctx.packageName)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("배터리 최적화 제외: ${if (ignoring) "됨" else "필요"}")
        if (!ignoring) OutlinedButton(onClick = {
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }) { Text("요청") }
    }
}

object PermLists {
    fun media(): List<String> =
        if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    fun notifications(): List<String> =
        if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
}
