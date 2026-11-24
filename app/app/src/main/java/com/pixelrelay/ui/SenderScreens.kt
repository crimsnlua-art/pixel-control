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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberDatePickerState
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
import com.pixelrelay.core.BackfillDates
import com.pixelrelay.core.BackfillMode
import com.pixelrelay.core.DriveUse
import com.pixelrelay.core.FolderRow
import com.pixelrelay.core.FolderSelection
import com.pixelrelay.core.GB
import com.pixelrelay.core.SendProgress
import com.pixelrelay.core.QueueMode
import com.pixelrelay.core.ReceiverConfig
import com.pixelrelay.core.RemoteCommand
import com.pixelrelay.core.SendState
import com.pixelrelay.core.SenderSettings
import com.pixelrelay.core.WarnCode
import com.pixelrelay.core.db.SendEntry
import com.pixelrelay.sender.FailureText
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
    val driveUsed by poll(3000) { Graph.driveUsed() }
    val scanPending by poll(3000) { (Graph.meta.getLong(com.pixelrelay.sender.SenderEngine.KEY_SCAN_PENDING) ?: 0L).toInt() }
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
            catch (e: Exception) { "픽셀에 명령을 보내지 못했습니다: ${FailureText.korean(e, LocalNetworkPermission.granted(ctx))}" }
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("픽셀중계", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 8.dp))

        // 경고
        val warns = ArrayList<String>()
        if (!Graph.mediaSource.hasFullMediaAccess()) warns += "사진·영상 접근이 전체 허용이 아닙니다. 자동 감지가 멈춰 있습니다."
        if (!Graph.mediaSource.hasLocationAccess()) warns += "위치 정보(미디어 위치) 권한이 없어 파일 전송이 '권한필요'로 대기합니다."
        DriveUse.homeAuthWarning(driveUsed != false, driveAuth == true)?.let { warns += it }
        val latest = listOfNotNull(lan, drv).maxByOrNull { it.at }
        latest?.status?.let { st -> DriveUse.visibleWarnings(st.warnings, driveUsed != false) }?.forEach { warns += "픽셀: " + WarnCode.korean(it.code) }
        if (warns.isNotEmpty()) Section("경고") { warns.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) } }
        DriveUse.skippedNote(driveUsed != false)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }

        val progress = SendProgress.of(entries.orEmpty(), scanPending ?: 0, driveUsed != false)
        if (progress.lines().isNotEmpty()) Section("보내기 진행") { progress.lines().forEach { Text(it) } }

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
                        Text("${fmtBytes(e.size)} · ${stateLabel(e.state)}${e.reason?.let { " (${FailureText.displayReason(it)})" } ?: ""}${if (e.attempts > 0) " · 시도 ${e.attempts}회" else ""}")
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SenderSettingsScreen(onOpenWizard: () -> Unit, onOpenTrial: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cur by poll(5000) { Graph.settings.sender() }
    var loaded by remember { mutableStateOf(false) }
    var photoMobile by remember { mutableStateOf(true) }
    var videoMobile by remember { mutableStateOf(false) }
    var dailyMb by remember { mutableStateOf("2048") }
    var mode by remember { mutableStateOf(QueueMode.IMMEDIATE) }
    var thrGb by remember { mutableStateOf("10") }
    var resGb by remember { mutableStateOf("3") }
    var msg by remember { mutableStateOf("") }

    // 폴더 체크 목록: 사진·영상이 있는 폴더를 MediaStore 에서 모아 파일 수와 함께 보여 준다
    var folderRows by remember { mutableStateOf<List<FolderRow>>(emptyList()) }
    var folderListed by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 기존 사진도 보내기
    var backfill by remember { mutableStateOf(BackfillMode.NONE) }
    var sinceSec by remember { mutableStateOf(0L) }
    var dateDialog by remember { mutableStateOf<com.pixelrelay.core.DateDialogState?>(null) }

    LaunchedEffect(cur) {
        val c = cur
        if (c != null && !loaded) {
            loaded = true
            photoMobile = c.photoOverMobile; videoMobile = c.videoOverMobile; dailyMb = (c.dailyLimitBytes / (1024 * 1024)).toString()
            backfill = c.backfillMode
            // 기본 날짜(30일 전)도 기기 시간대의 그 날 자정으로 맞춘다(날짜 선택기·저장값과 같은 기준)
            sinceSec = if (c.backfillMode == BackfillMode.SINCE) c.sendFromSec ?: 0L
            else BackfillDates.startOfDaySec(BackfillDates.toPickerMs(System.currentTimeMillis() / 1000 - 30L * 86400))
            val counts = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Graph.mediaSource.folderCounts() }
            } catch (e: Exception) { emptyMap() }
            folderListed = counts.isNotEmpty()
            val rows = FolderSelection.rows(counts, c.watchFolders, c.excludeFolders)
            folderRows = rows
            checked = rows.filter { it.checked }.map { it.path }.toSet()
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        UpdateCard(isSender = true)
        Section("설정 도우미") {
            Text("권한·배터리·구글 계정·짝짓기를 단계별로 확인합니다.")
            OutlinedButton(onClick = onOpenWizard) { Text("설정 마법사 다시 열기") }
            OutlinedButton(onClick = onOpenTrial) { Text("무료 업로드 확인 시험") }
        }
        Section("보낼 폴더") {
            Text("체크한 폴더의 사진·영상을 보냅니다. 기본은 카메라 폴더(DCIM/Camera)입니다. 체크한 폴더 안의 하위 폴더도 함께 보내며, 체크하지 않은 하위 폴더는 뺍니다.")
            if (!loaded) Text("불러오는 중...")
            else if (folderRows.isEmpty()) Text("사진·영상이 있는 폴더를 찾지 못했습니다. 사진 읽기 권한을 확인하세요.")
            folderRows.forEach { r ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = r.path in checked, onCheckedChange = { on -> checked = if (on) checked + r.path else checked - r.path })
                    Text("${r.path}  (${r.count}개)", Modifier.weight(1f))
                }
            }
        }
        Section("기존 사진도 보내기") {
            Text("기본은 이 앱을 쓰기 시작한 뒤 새로 찍은 것만 보냅니다. 폴더에 이미 있던 사진·영상도 보내려면 고르세요. 이미 보낸 파일은 다시 보내지 않고, 최근 것부터 보냅니다. 양이 많으면 시간이 오래 걸리고 픽셀 공간이 모자라면 기다렸다가 이어 보냅니다.")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(backfill == BackfillMode.NONE, { backfill = BackfillMode.NONE }, { Text("안 보냄(새로 찍은 것만)") })
                FilterChip(backfill == BackfillMode.ALL, { backfill = BackfillMode.ALL }, { Text("전부") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                FilterChip(backfill == BackfillMode.SINCE, { dateDialog = com.pixelrelay.core.DateDialogState.open(backfill); backfill = BackfillMode.SINCE }, { Text("이 날짜 이후") })
                if (backfill == BackfillMode.SINCE) OutlinedButton(onClick = { dateDialog = com.pixelrelay.core.DateDialogState.open(backfill) }) { Text(BackfillDates.label(sinceSec)) }
            }
            if (backfill == BackfillMode.ALL) {
                val n = folderRows.filter { it.path in checked }.sumOf { it.count }
                Text("지금 체크한 폴더에는 사진·영상이 ${n}개 있습니다.")
            }
        }
        Section("모바일 데이터") {
            Row { Text("사진도 모바일 데이터로 올리기", Modifier.weight(1f)); Switch(photoMobile, { photoMobile = it }) }
            Row { Text("영상도 모바일 데이터로 올리기", Modifier.weight(1f)); Switch(videoMobile, { videoMobile = it }) }
            OutlinedTextField(dailyMb, { dailyMb = it.filter(Char::isDigit) }, label = { Text("하루 데이터 한도(MB)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        }
        Button(onClick = {
            scope.launch {
                val base = Graph.settings.sender()
                // 폴더 목록을 못 불러왔으면 기존 폴더 규칙을 그대로 둔다
                val (watch, exclude) =
                    if (folderListed) FolderSelection.toRules(folderRows.map { it.path }, checked, base.excludeFolders, base.watchFolders) else base.watchFolders to base.excludeFolders
                Graph.settings.saveSender(
                    base.copy(
                        watchFolders = watch, excludeFolders = exclude,
                        photoOverMobile = photoMobile, videoOverMobile = videoMobile,
                        dailyLimitBytes = (dailyMb.toLongOrNull() ?: 2048L) * 1024 * 1024,
                    ),
                )
                Graph.settings.setSendFrom(SenderSettings.sendFromFor(backfill, sinceSec))
                SenderScheduler.runNow(ctx, false)   // 새 기준으로 바로 한 번 훑는다
                msg = "저장했습니다"
            }
        }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) { Text("저장") }
        dateDialog?.let { dlg ->
            val st = rememberDatePickerState(initialSelectedDateMillis = BackfillDates.toPickerMs(sinceSec))
            DatePickerDialog(
                onDismissRequest = { backfill = dlg.cancel(); dateDialog = null },
                confirmButton = {
                    TextButton(onClick = {
                        val ms = st.selectedDateMillis
                        if (ms != null) { sinceSec = BackfillDates.startOfDaySec(ms); backfill = dlg.confirm() } else backfill = dlg.cancel()
                        dateDialog = null
                    }) { Text("확인") }
                },
                dismissButton = { TextButton(onClick = { backfill = dlg.cancel(); dateDialog = null }) { Text("취소") } },
            ) { DatePicker(state = st) }
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
                    } catch (e: Exception) { "짝짓기 실패: ${FailureText.pairing(e, LocalNetworkPermission.granted(ctx))}" }
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
        AccountSection(allowSkip = true)
    }
}
