package com.pixelrelay.ui

import android.Manifest
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pixelrelay.Graph
import com.pixelrelay.core.GB
import com.pixelrelay.core.QueueMode
import com.pixelrelay.core.ReceiverConfig
import com.pixelrelay.core.RecvState
import com.pixelrelay.core.RemoteCommand
import com.pixelrelay.core.WarnCode
import kotlinx.coroutines.launch

@Composable
fun ReceiverApp() {
    var tab by remember { mutableStateOf(0) }
    val seen by poll(1000) { Graph.settings.wizardSeen(com.pixelrelay.ui.SettingsStore.ROLE_RECEIVER) }
    var autoShown by remember { mutableStateOf(false) }
    // 첫 실행: 아직 마법사를 본 적이 없으면 마법사 탭으로
    LaunchedEffect(seen) { if (seen == false && !autoShown) { autoShown = true; tab = 4 } }
    val titles = listOf("상태", "짝짓기", "설정", "원인 불명", "마법사")
    Scaffold(bottomBar = {
        NavigationBar {
            titles.forEachIndexed { i, t ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(if (tab == i) "●" else "○") }, label = { Text(t, maxLines = 1) })
            }
        }
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 12.dp).fillMaxSize()) {
            when (tab) {
                0 -> ReceiverStatus()
                1 -> ReceiverPairing()
                2 -> ReceiverSettingsScreen()
                3 -> ReceiverUnknown()
                else -> SetupWizard(SettingsStore.ROLE_RECEIVER) { tab = 0 }
            }
        }
    }
}

@Composable
private fun ReceiverStatus() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by poll(3000) { Graph.receiver.statusBuilder.build() }
    val counts by poll(3000) { Graph.recvLedger.counts() }
    var msg by remember { mutableStateOf("") }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("픽셀중계 · 받기", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 8.dp))
        val s = st
        if (s != null && s.warnings.isNotEmpty()) {
            Section("경고") { s.warnings.forEach { Text("• ${WarnCode.korean(it.code)}", color = MaterialTheme.colorScheme.error) } }
        }
        Section("서버") {
            Text("서버: ${if (Graph.receiver.serverRunning) "동작 중(포트 ${com.pixelrelay.core.Proto.PORT})" else "멈춤"}")
            Text("주소: ${localIpv4() ?: "와이파이 없음"}")
            if (Graph.receiver.driveAuthProblem) Text("구글 드라이브 인증이 필요합니다(마법사 탭).", color = MaterialTheme.colorScheme.error)
        }
        Section("받은 파일") {
            val c = counts
            Text("받는 중 ${c?.receiving ?: 0} · 포토 확인 대기(받음) ${c?.published ?: 0}")
            Text("포토 확인됨 ${c?.confirmed ?: 0} · 원인 불명 ${c?.vanished ?: 0} · 실패 ${c?.failed ?: 0}")
        }
        if (s != null) {
            Section("기기") {
                Text("남은 공간 ${fmtBytes(s.freeBytes)}")
                Text("배터리 ${s.batteryPct}%${if (s.charging) "(충전 중)" else ""} · 온도 ${s.tempC}℃")
                Text("마지막 포토 신호: ${fmtAgo(s.lastPhotosActivityAt)}")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            Button(onClick = {
                scope.launch {
                    Graph.receiver.commands.handle(RemoteCommand(RemoteCommand.CLEANUP_OPENED), remote = false)
                    val i = ctx.packageManager.getLaunchIntentForPackage("com.google.android.apps.photos")
                    msg = if (i != null) { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "열었습니다. 포토 앱에서 '기기 공간 확보'를 실행하세요. 1시간 안에 사라진 파일만 포토 확인으로 인정됩니다." }
                    else "구글 포토 앱을 찾지 못했습니다(기록은 남겼습니다)."
                }
            }) { Text("포토 공간 확보 열기") }
            OutlinedButton(onClick = {
                scope.launch {
                    Graph.receiver.commands.handle(RemoteCommand(RemoteCommand.SEND_NOW), remote = false)
                    msg = "지금 받기를 시작합니다"
                }
            }) { Text("지금 받기") }
        }
        if (msg.isNotEmpty()) Text(msg)
        Section("무료 확인 시험 도우미") {
            Text("주력 폰에서 '무료 업로드 확인 시험'을 시작했을 때만 씁니다. 시험 사진이 도착하면 포토를 열어 백업이 끝나길 기다린 뒤 아래 버튼을 누르세요.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    if (!Shortcuts.open(ctx, com.pixelrelay.core.Shortcut.OPEN_PHOTOS)) msg = "구글 포토 앱을 찾지 못했습니다."
                }) { Text("포토 열기") }
                Button(onClick = {
                    scope.launch {
                        Graph.recvLedger.putLong(com.pixelrelay.core.TrialSignals.KEY_PHOTOS_DONE, System.currentTimeMillis())
                        msg = "기록했습니다. 주력 폰에 곧 전달됩니다. 이어서 '포토 공간 확보 열기'를 눌러 공간 확보 시험을 하세요."
                    }
                }) { Text("포토 백업 완료됐어요") }
            }
        }
    }
}

@Composable
private fun ReceiverPairing() {
    val code by poll(1000) { Graph.pairing.currentCode() }
    var made by remember { mutableStateOf(0) }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Section("짝짓기 코드") {
            Text("주력 폰의 '연결' 탭에서 이 코드를 입력하세요. 5분 동안만 유효하고 한 번만 쓸 수 있습니다. 5번 틀리면 10분간 잠깁니다.")
            Text(code ?: "(코드 없음)", style = MaterialTheme.typography.displaySmall)
            Text("이 폰 주소: ${localIpv4() ?: "와이파이 없음"}")
            val locked = remember(made, code) { Graph.pairing.lockedUntilMs() }
            if (locked > 0) Text("잠금 중입니다(틀린 코드 5회).", color = MaterialTheme.colorScheme.error)
            Button(onClick = { Graph.pairing.newCode(); made++ }) { Text("새 코드 만들기") }
        }
    }
}

@Composable
private fun ReceiverSettingsScreen() {
    val scope = rememberCoroutineScope()
    val cur by poll(5000) { Graph.receiver.configStore.get() }
    var loaded by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(QueueMode.IMMEDIATE) }
    var thr by remember { mutableStateOf("10") }
    var res by remember { mutableStateOf("3") }
    var msg by remember { mutableStateOf("") }
    LaunchedEffect(cur) {
        val c = cur
        if (c != null && !loaded) { loaded = true; mode = c.queueMode; thr = (c.thresholdBytes / GB).toString(); res = (c.reserveBytes / GB).toString() }
    }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Section("드라이브에서 받는 때") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(mode == QueueMode.IMMEDIATE, { mode = QueueMode.IMMEDIATE }, { Text("바로(2분마다)") })
                FilterChip(mode == QueueMode.CAPACITY, { mode = QueueMode.CAPACITY }, { Text("용량(10분마다)") })
            }
            OutlinedTextField(thr, { thr = it.filter(Char::isDigit) }, label = { Text("용량 문턱(GB)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(res, { res = it.filter(Char::isDigit) }, label = { Text("드라이브 여유(GB)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                scope.launch {
                    Graph.receiver.configStore.set(ReceiverConfig(mode, (thr.toLongOrNull() ?: 10L) * GB, (res.toLongOrNull() ?: 3L) * GB))
                    msg = "저장했습니다"
                }
            }) { Text("저장") }
            if (msg.isNotEmpty()) Text(msg)
        }
    }
}

@Composable
private fun ReceiverUnknown() {
    val scope = rememberCoroutineScope()
    val rows by poll(3000) { Graph.recvLedger.all().filter { it.state == RecvState.VANISHED_UNKNOWN.name } }
    var confirm by remember { mutableStateOf<String?>(null) }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("원인 불명(사라짐) 항목", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
        Text("포토 공간 확보 기록 없이 파일이 사라진 항목입니다. 드라이브 사본은 그대로 유지됩니다. 다시 보내기는 주력 폰에서 합니다.")
        rows.orEmpty().forEach { e ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(10.dp)) {
                    Text(e.name); Text(fmtBytes(e.size))
                    OutlinedButton(onClick = { confirm = e.sha }) { Text("포토 확인됨으로 확정") }
                }
            }
        }
        if (rows.orEmpty().isEmpty()) Text("없습니다.")
    }
    confirm?.let { sha ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("포토 확인됨으로 확정") },
            text = { Text("먼저 포토 웹(photos.google.com)에서 이 파일이 원본 화질로 있는지 확인하세요. 확정하면 드라이브 사본이 영구 삭제됩니다.") },
            confirmButton = { TextButton(onClick = { confirm = null; scope.launch { Graph.receiver.confirmer.userConfirm(sha) } }) { Text("확정") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("취소") } },
        )
    }
}
