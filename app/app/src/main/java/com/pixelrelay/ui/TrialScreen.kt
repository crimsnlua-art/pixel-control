package com.pixelrelay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pixelrelay.Graph
import com.pixelrelay.core.FreeTrialMachine
import com.pixelrelay.core.Shortcut
import com.pixelrelay.core.TrialPhase
import com.pixelrelay.core.TrialText
import com.pixelrelay.sender.MediaItem
import com.pixelrelay.sender.SenderScheduler
import com.pixelrelay.sender.TrialStartOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 무료 업로드 확인 시험(주력 폰). 상태는 영속 저장되어 앱을 닫았다 열어도 이어진다. */
@Composable
fun FreeTrialScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by poll(4000) { Graph.trialRunner.step() }
    var msg by remember { mutableStateOf("") }
    var needAccount by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(setOf<Long>()) }
    val recent by produceState<List<MediaItem>>(initialValue = emptyList()) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                Graph.mediaSource.list(System.currentTimeMillis() / 1000 - 30L * 86400)
                    .filter { !it.isVideo }.sortedByDescending { it.dateAddedSec }.take(40)
            }.getOrDefault(emptyList())
        }
    }
    val s = state
    val running = s != null && s.phase !in setOf(TrialPhase.IDLE, TrialPhase.FINISHED, TrialPhase.FAILED)

    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("← 돌아가기") }
            Text("무료 업로드 확인 시험", style = MaterialTheme.typography.titleLarge)
        }
        Text("사진 3장을 일반 경로로 픽셀에 보내고, 구글 포토 백업이 끝난 뒤 계정 사용량이 늘지 않는지(원본 화질이 무료로 올라가는지) 확인합니다. 이어서 '공간 확보'로 포토 확인 방식도 시험합니다.")

        if (s != null && s.phase != TrialPhase.IDLE) {
            Section("진행 상황") {
                Text(TrialText.phase(s))
                if (s.files.isNotEmpty()) Text("시험 사진: " + s.files.joinToString(", ") { it.name } + " (합계 ${fmtBytes(s.totalBytes)})")
                s.startUsage?.let { Text("시작 사용량: ${fmtBytes(it)}") }
                TrialText.usage(s)?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                TrialText.space(s)?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                if (s.phase != TrialPhase.IDLE) Text(TrialText.DATE_LOCATION_GUIDE)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { Shortcuts.open(ctx, Shortcut.OPEN_PHOTOS) }) { Text("포토에서 이 사진 열어 확인") }
                    if (running) OutlinedButton(onClick = { scope.launch { Graph.trialRunner.reset(); msg = "시험을 중단했습니다." } }) { Text("중단") }
                }
            }
        }

        if (!running) {
            Section(if (s?.phase == TrialPhase.FINISHED || s?.phase == TrialPhase.FAILED) "다시 하기: 시험 사진 3장 고르기" else "시험 사진 3장 고르기") {
                Text("최근 30일 사진 중 아직 보내지 않은 사진 3장을 고르세요(그중 1장은 위치 정보가 있는 사진이면 좋습니다). ${picked.size}/${FreeTrialMachine.COUNT}장 선택")
                if (recent.isEmpty()) Text("보여줄 최근 사진이 없거나 사진 권한이 없습니다.")
                recent.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Checkbox(checked = m.id in picked, onCheckedChange = { on ->
                            picked = if (on && picked.size < FreeTrialMachine.COUNT) picked + m.id else picked - m.id
                        })
                        Text("${m.name} · ${fmtBytes(m.size)} · ${fmtAgo(m.dateAddedSec * 1000)}")
                    }
                }
                Button(enabled = picked.size == FreeTrialMachine.COUNT, onClick = {
                    scope.launch {
                        when (val r = Graph.trialRunner.start(picked.toList())) {
                            is TrialStartOutcome.Started -> { needAccount = false; msg = "시험을 시작했습니다. 사진을 픽셀로 보냅니다."; SenderScheduler.runNow(ctx, true) }
                            is TrialStartOutcome.AccountRequired -> { needAccount = true; msg = "구글 계정 연결 필요: 아래에서 먼저 연결한 뒤 다시 시작하세요." }
                            is TrialStartOutcome.Rejected -> msg = r.message
                        }
                    }
                }) { Text("시험 시작") }
            }
        }
        if (needAccount) AccountSection()
        if (msg.isNotEmpty()) Text(msg)
    }
}
