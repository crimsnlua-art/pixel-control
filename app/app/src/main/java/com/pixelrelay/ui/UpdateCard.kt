package com.pixelrelay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pixelrelay.Graph
import com.pixelrelay.core.UpdateException
import com.pixelrelay.update.UpdateManager
import kotlinx.coroutines.launch

/**
 * 앱 업데이트 카드(보내기·받기 공통). 열 때 한 번 확인하고 "지금 확인" 버튼이 있다.
 * 보내기 폰이 모바일 데이터면 내려받기 전에 확인 창을 띄운다. 자동 다운로드·자동 설치는 없다.
 */
@Composable
fun UpdateCard(isSender: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val avail by poll(2000) { UpdateManager.available(ctx) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmMobile by remember { mutableStateOf(false) }

    fun checkNow() {
        scope.launch {
            busy = true
            msg = try {
                val i = UpdateManager.check(ctx, notify = false)
                if (i == null) "최신 버전입니다." else "새 버전 ${i.versionName} 이 있습니다."
            } catch (e: UpdateException) { e.message ?: "확인하지 못했습니다." }
            busy = false
        }
    }

    fun doUpdate() {
        val info = avail ?: return
        if (!UpdateManager.canInstall(ctx)) {
            msg = "먼저 '이 출처의 앱 설치 허용'을 켜 주세요. 설정 화면을 엽니다. 켠 뒤 다시 '업데이트'를 누르세요."
            UpdateManager.openInstallPermissionSettings(ctx)
            return
        }
        scope.launch {
            busy = true
            msg = try {
                msg = "내려받는 중..."
                val f = UpdateManager.download(ctx, info)
                UpdateManager.install(ctx, f)
                "설치 확인 화면에서 '설치'를 눌러 주세요."
            } catch (e: UpdateException) { e.message ?: "업데이트에 실패했습니다." }
            catch (e: Exception) { "설치 화면을 열지 못했습니다." }
            busy = false
        }
    }

    // 설정 화면을 열 때 한 번 확인(알림은 하루 1회 작업이 담당)
    LaunchedEffect(Unit) { runCatching { UpdateManager.check(ctx, notify = false) } }

    Section("앱 업데이트") {
        Text("현재 버전 ${UpdateManager.currentName(ctx)} (${UpdateManager.currentCode(ctx)})")
        val a = avail
        if (a != null) {
            Text("새 버전 ${a.versionName} 이 있습니다.", style = MaterialTheme.typography.titleSmall)
            if (a.notes.isNotBlank()) Text(a.notes)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = {
                    if (isSender && !Graph.net.snapshot().unmeteredWifi) confirmMobile = true else doUpdate()
                }) { Text("업데이트") }
            }
        } else {
            Text("최신 버전입니다.")
        }
        OutlinedButton(enabled = !busy, onClick = { checkNow() }) { Text("지금 확인") }
        if (msg.isNotEmpty()) Text(msg)
    }

    if (confirmMobile) {
        AlertDialog(
            onDismissRequest = { confirmMobile = false },
            title = { Text("모바일 데이터로 내려받기") },
            text = { Text("지금은 와이파이가 아니라 모바일 데이터입니다. 업데이트 파일을 내려받을까요?") },
            confirmButton = { TextButton(onClick = { confirmMobile = false; doUpdate() }) { Text("내려받기") } },
            dismissButton = { TextButton(onClick = { confirmMobile = false }) { Text("취소") } },
        )
    }
}
