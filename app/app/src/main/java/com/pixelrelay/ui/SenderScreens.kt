package com.pixelrelay.ui

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.Switch
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
import com.pixelrelay.core.RemoteCommand
import com.pixelrelay.core.SendState
import com.pixelrelay.core.SenderSettings
import com.pixelrelay.core.WarnCode
import com.pixelrelay.core.db.SendEntry
import com.pixelrelay.sender.NsdFinder
import com.pixelrelay.sender.PixelPairing
import com.pixelrelay.sender.HttpPixelApi
import com.pixelrelay.sender.SenderScheduler
import kotlinx.coroutines.launch

private fun stateLabel(s: String): String = when (s) {
    "WAITING" -> "대기"
    "SENDING_LAN" -> "LAN 전송 중"
    "UPLOADING_DRIVE" -> "드라이브 올리는 중"
    "IN_DRIVE" -> "드라이브 대기열"
    "RECEIVED" -> "픽셀 도착"
    "PHOTOS_CONFIRMED" -> "포토 확인됨"
    "VANISHED_UNKNOWN" -> "원인 불명(사라짐)"
    "RESEND_PENDING" -> "다시 보내기 요청 중(픽셀 확인 대기)"
    "FAILED" -> "실패"
    "SKIPPED" -> "건너뜀"
    else -> s
}

@Composable
fun SenderApp() {
    var tab by remember { mutableStateOf(0) }
    var overlay by remember { mutableStateOf<String?>(null) }
    val seen by poll(1000) { Graph.settings.wizardSeen(SettingsStore.ROLE_SENDER) }
    var autoShown by remember { mutableStateOf(false) }
    // 첫 실행: 아직 마법사를 본 적이 없으면 자동으로 연다
    LaunchedEffect(seen) { if (seen == false && !autoShown) { autoShown = true; overlay = "wizard" } }
    if (overlay != null) {
        Scaffold { pad ->
            Column(Modifier.padding(pad).padding(horizontal = 12.dp).fillMaxSize()) {
                if (overlay == "wizard") SetupWizard(SettingsStore.ROLE_SENDER) { overlay = null }
                else FreeTrialScreen { overlay = null }
            }
        }
        return
    }
    val titles = listOf("홈", "파일", "설정", "연결")
    Scaffold(bottomBar = {
        NavigationBar {
            titles.forEachIndexed { i, t ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(if (tab == i) "●" else "○") }, label = { Text(t) })
            }
        }
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 12.dp).fillMaxSize()) {
            when (tab) {
                0 -> SenderHome()
                1 -> SenderFiles()
                2 -> SenderSettingsScreen(onOpenWizard = { overlay = "wizard" }, onOpenTrial = { overlay = "trial" })
                else -> SenderConnect()
            }
        }
    }
}

@Composable
private fun SenderHome() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries by poll(2000) { Graph.db.send().all() }
    val lan by poll(3000) { Graph.remoteStatus.load("lan") }
    val drv by poll(3000) { Graph.remoteStatus.load("drive") }
    val driveAuth by poll(3000) { (Graph.meta.getLong("driveAuthProblem") ?: 0L) == 1L }
    var msg by remember { mutableStateOf("") }
    var confirmVideo by remember { mutableStateOf(false) }

    fun doSend() {
        // 이번 배치(아직 픽셀에 도착하지 않은 파일)의 sha 를 알려, 업로드가 끝나기 전에 픽셀이 소진되지 않게 한다
        val batchShas = entries.orEmpty()
            .filter { it.state in setOf("WAITING", "SENDING_LAN", "UPLOADING_DRIVE", "IN_DRIVE") && !it.sha.startsWith("m") }
            .map { it.sha }
        SenderScheduler.runNow(ctx, true)
        scope.launch {
            msg = try { "지금 보내기 요청(주력 폰 대기 파일 + 픽셀에 sendNow: ${Graph.senderCommands.send(RemoteCommand(RemoteCommand.SEND_NOW, expectShas = batchShas))})" }
            catch (e: Exception) { "픽셀에 명령을 보내지 못했습니다: ${e.message}" }
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("픽셀중계", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 8.dp))

        // 경고
        val warns = ArrayList<String>()
        if (!Graph.mediaSource.hasFullMediaAccess()) warns += "사진·영상 접근이 전체 허용이 아닙니다. 자동 감지가 멈춰 있습니다."
        if (!Graph.mediaSource.hasLocationAccess()) warns += "위치 정보(미디어 위치) 권한이 없어 파일 전송이 '권한필요'로 대기합니다."
        if (driveAuth == true) warns += "구글 드라이브 연결이 필요합니다(집 밖 전송 중지)."
        val latest = listOfNotNull(lan, drv).maxByOrNull { it.at }
        latest?.status?.warnings?.forEach { warns += "픽셀: " + WarnCode.korean(it.code) }
        if (warns.isNotEmpty()) Section("경고") { warns.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) } }

        Section("상태별 개수") {
            val c = entries.orEmpty().groupingBy { it.state }.eachCount()
            SendState.values().forEach { s -> Text("${stateLabel(s.name)}: ${c[s.name] ?: 0}") }
        }

        Section("픽셀 상태") {
            @Composable fun line(name: String, s: com.pixelrelay.sender.RemoteStatusStore.Saved?) {
                if (s == null) { Text("$name: 소식 없음"); return }
                val st = s.status
                Text("$name: ${fmtAgo(s.at)} · 남은 ${fmtBytes(st.freeBytes)} · 배터리 ${st.batteryPct}%${if (st.charging) "(충전)" else ""} · ${st.tempC}℃")
                Text("   포토 확인 ${st.counts.confirmed} · 대기 ${st.counts.published} · 원인불명 ${st.counts.vanished} · 실패 ${st.counts.failed}")
            }
            line("LAN", lan)
            line("드라이브", drv)
            if (latest != null) Text("마지막 소식 경로: ${if (latest.via == "lan") "LAN" else "드라이브"}")
        }

        Button(onClick = {
            val net = Graph.net.snapshot()
            val videoWaiting = entries.orEmpty().any { it.isVideo && it.state == SendState.WAITING.name }
            if (videoWaiting && net.metered) confirmVideo = true else doSend()
        }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) { Text("지금 보내기") }
        if (msg.isNotEmpty()) Text(msg)
    }

    if (confirmVideo) {
        AlertDialog(
            onDismissRequest = { confirmVideo = false },
            title = { Text("모바일 데이터로 영상 보내기") },
            text = { Text("대기 중인 영상이 있고 지금은 모바일 데이터입니다. 데이터 조건을 무시하고 보낼까요?") },
            confirmButton = { TextButton(onClick = { confirmVideo = false; doSend() }) { Text("보내기") } },
            dismissButton = { TextButton(onClick = { confirmVideo = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun SenderFiles() {
    val scope = rememberCoroutineScope()
    val entries by poll(2000) { Graph.db.send().all() }
    val filters = listOf(
        "전체" to null,
        "대기" to setOf("WAITING", "SENDING_LAN", "UPLOADING_DRIVE"),
        "드라이브" to setOf("IN_DRIVE"),
        "도착" to setOf("RECEIVED"),
        "포토 확인" to setOf("PHOTOS_CONFIRMED"),
        "원인 불명" to setOf("VANISHED_UNKNOWN", "RESEND_PENDING"),
        "실패·건너뜀" to setOf("FAILED", "SKIPPED"),
    )
    var sel by remember { mutableStateOf(0) }
    var confirmSha by remember { mutableStateOf<String?>(null) }
    val shown = entries.orEmpty().filter { e -> filters[sel].second?.contains(e.state) ?: true }.sortedByDescending { it.stateAt }.take(300)

    Column {
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            items(filters.size) { i -> FilterChip(selected = sel == i, onClick = { sel = i }, label = { Text(filters[i].first) }) }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(shown, key = { it.sha }) { e ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text(e.name + if (e.trial) " [시험]" else "", style = MaterialTheme.typography.titleSmall)
                        Text("${fmtBytes(e.size)} · ${stateLabel(e.state)}${e.reason?.let { " ($it)" } ?: ""}${if (e.attempts > 0) " · 시도 ${e.attempts}회" else ""}")
                        if (e.state == "VANISHED_UNKNOWN") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { confirmSha = e.sha }) { Text("포토 확인됨으로 확정") }
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        // 영속 기록(RESEND_PENDING + commandId)만 하고, 명령은 엔진이 보낸다.
                                        // 픽셀이 RESEND_REQUESTED 로 확인해 준 뒤에만 WAITING 이 되어 다시 보낸다.
                                        Graph.senderLedger.requestResend(e.sha)
                                        SenderScheduler.runNow(Graph.app, false)
                                    }
                                }) { Text("다시 보내기") }
                            }
                        }
                        if (e.state == "FAILED") {
                            OutlinedButton(onClick = { scope.launch { Graph.senderLedger.retryFailed(e.sha); SenderScheduler.runNow(Graph.app, false) } }) { Text("다시 시도") }
                        }
                    }
                }
            }
        }
    }

    confirmSha?.let { sha ->
        AlertDialog(
            onDismissRequest = { confirmSha = null },
            title = { Text("포토 확인됨으로 확정") },
            text = { Text("먼저 포토 웹(photos.google.com)에서 이 파일이 원본 화질로 올라가 있는지 확인하세요. 확정하면 드라이브 사본이 영구 삭제됩니다.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSha = null
                    scope.launch {
                        Graph.senderLedger.tryTransition(sha, SendState.PHOTOS_CONFIRMED)
                        runCatching { Graph.senderCommands.send(RemoteCommand(RemoteCommand.CONFIRM_VANISHED, sha = sha)) }
                    }
                }) { Text("확정") }
            },
            dismissButton = { TextButton(onClick = { confirmSha = null }) { Text("취소") } },
        )
    }
}

@Composable
private fun SenderSettingsScreen(onOpenWizard: () -> Unit, onOpenTrial: () -> Unit) {
    val scope = rememberCoroutineScope()
    val cur by poll(5000) { Graph.settings.sender() }
    var loaded by remember { mutableStateOf(false) }
    var watch by remember { mutableStateOf("") }
    var exclude by remember { mutableStateOf("") }
    var photoMobile by remember { mutableStateOf(true) }
    var videoMobile by remember { mutableStateOf(false) }
    var dailyMb by remember { mutableStateOf("2048") }
    var mode by remember { mutableStateOf(QueueMode.IMMEDIATE) }
    var thrGb by remember { mutableStateOf("10") }
    var resGb by remember { mutableStateOf("3") }
    var msg by remember { mutableStateOf("") }

    LaunchedEffect(cur) {
        val c = cur
        if (c != null && !loaded) {
            loaded = true
            watch = c.watchFolders.joinToString("\n"); exclude = c.excludeFolders.joinToString("\n")
            photoMobile = c.photoOverMobile; videoMobile = c.videoOverMobile; dailyMb = (c.dailyLimitBytes / (1024 * 1024)).toString()
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Section("설정 도우미") {
            Text("권한·배터리·구글 계정·짝짓기를 단계별로 확인합니다.")
            OutlinedButton(onClick = onOpenWizard) { Text("설정 마법사 다시 열기") }
            OutlinedButton(onClick = onOpenTrial) { Text("무료 업로드 확인 시험") }
        }
        Section("감시 폴더") {
            OutlinedTextField(watch, { watch = it }, label = { Text("감시 폴더(한 줄에 하나)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(exclude, { exclude = it }, label = { Text("제외 폴더(한 줄에 하나)") }, modifier = Modifier.fillMaxWidth())
        }
        Section("모바일 데이터") {
            Row { Text("사진도 모바일 데이터로 올리기", Modifier.weight(1f)); Switch(photoMobile, { photoMobile = it }) }
            Row { Text("영상도 모바일 데이터로 올리기", Modifier.weight(1f)); Switch(videoMobile, { videoMobile = it }) }
            OutlinedTextField(dailyMb, { dailyMb = it.filter(Char::isDigit) }, label = { Text("하루 데이터 한도(MB)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                scope.launch {
                    val base = Graph.settings.sender()
                    Graph.settings.saveSender(
                        base.copy(
                            watchFolders = watch.split('\n').map { it.trim() }.filter { it.isNotEmpty() },
                            excludeFolders = exclude.split('\n').map { it.trim() }.filter { it.isNotEmpty() },
                            photoOverMobile = photoMobile, videoOverMobile = videoMobile,
                            dailyLimitBytes = (dailyMb.toLongOrNull() ?: 2048L) * 1024 * 1024,
                        ),
                    )
                    msg = "저장했습니다"
                }
            }) { Text("저장") }
        }
        Section("픽셀 받기 방식(픽셀에 적용)") {
            Row { FilterChip(mode == QueueMode.IMMEDIATE, { mode = QueueMode.IMMEDIATE }, { Text("바로") }); Text("  "); FilterChip(mode == QueueMode.CAPACITY, { mode = QueueMode.CAPACITY }, { Text("용량") }) }
            OutlinedTextField(thrGb, { thrGb = it.filter(Char::isDigit) }, label = { Text("용량 문턱(GB)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(resGb, { resGb = it.filter(Char::isDigit) }, label = { Text("드라이브 여유(GB)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                scope.launch {
                    val cfg = ReceiverConfig(mode, (thrGb.toLongOrNull() ?: 10L) * GB, (resGb.toLongOrNull() ?: 3L) * GB)
                    msg = try { "픽셀에 설정 전달(${Graph.senderCommands.send(RemoteCommand(RemoteCommand.SET_CONFIG, config = cfg))})" } catch (e: Exception) { "전달 실패: ${e.message}" }
                }
            }) { Text("픽셀에 적용") }
        }
        if (msg.isNotEmpty()) Text(msg)
    }
}

@Composable
private fun SenderConnect() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pairing by poll(3000) { Graph.settings.pairing() }
    var host by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    val localNet = remember { LocalNetworkPermission.definedOnDevice(ctx) }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Section("픽셀 짝짓기") {
            Text(if (pairing != null) "짝지어짐: ${pairing?.host?.ifEmpty { "(주소 자동 탐색)" }}" else "아직 짝짓지 않았습니다.")
            Text("픽셀 앱의 '짝짓기' 화면에 보이는 6자리 코드를 입력하세요. 두 폰이 같은 와이파이에 있어야 합니다.")
            OutlinedTextField(host, { host = it.trim() }, label = { Text("픽셀 IP 주소(비우면 자동 찾기)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("6자리 코드") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                scope.launch {
                    msg = try {
                        val h = host.ifEmpty { NsdFinder(ctx).find() ?: error("픽셀을 찾지 못했습니다. IP 를 직접 입력하세요") }
                        val key = PixelPairing.pair(HttpPixelApi.defaultClient(), h, com.pixelrelay.core.Proto.PORT, code, android.os.Build.MODEL)
                        Graph.settings.savePairing(h, key)
                        "짝짓기 성공($h)"
                    } catch (e: Exception) { "짝짓기 실패: ${e.message}" }
                }
            }) { Text("짝짓기") }
            if (msg.isNotEmpty()) Text(msg)
        }

        Section("권한") {
            Text("사진·영상은 '전체 허용'이어야 합니다(부분 허용이면 자동 감지가 멈춥니다).")
            PermissionRow("사진·영상 읽기", PermLists.media())
            PermissionRow("사진 위치 정보(원본 GPS)", listOf(Manifest.permission.ACCESS_MEDIA_LOCATION))
            if (PermLists.notifications().isNotEmpty()) PermissionRow("알림", PermLists.notifications())
            if (localNet) PermissionRow("로컬 네트워크(Android 16+)", listOf(LocalNetworkPermission.NAME))
        }
        AccountSection()
    }
}
