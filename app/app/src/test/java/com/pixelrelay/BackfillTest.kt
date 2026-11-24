package com.pixelrelay

import com.pixelrelay.core.BackfillMode
import com.pixelrelay.core.Hashing
import com.pixelrelay.core.Reason
import com.pixelrelay.core.SendProgress
import com.pixelrelay.core.SendState
import com.pixelrelay.core.SenderSettings
import com.pixelrelay.sender.MediaItem
import com.pixelrelay.sender.SenderEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 기존 사진 보내기: 시작 시점 · 최근 것 먼저 · 해시 예산 이어 하기 · 오래된 파일 안정 · 픽셀 공간 부족 대기 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackfillTest {
    private val dbs = ArrayList<com.pixelrelay.core.db.AppDb>()
    private val recvs = ArrayList<RecvEnv>()
    @After fun tearDown() { dbs.forEach { runCatching { it.close() } }; recvs.forEach { runCatching { it.close() } } }

    private fun env(maxHash: Int = 400, hashBudgetMs: Long = 90_000L) =
        SendEnv(TestDb.memory().also { dbs += it }, maxHash = maxHash, hashBudgetMs = hashBudgetMs)

    /** 수정 시각이 아주 오래된(바로 안정) 파일을 추가하고 추가·촬영 시각을 addedSec 로 맞춘다. */
    private fun SendEnv.addOld(name: String, seed: Long, addedSec: Long, rel: String = "DCIM/Camera/", bytes: Int = 1000, video: Boolean = false): MediaItem {
        val m = source.add(name, randomBytes(bytes, seed), rel = rel, modifiedSec = 1000, video = video)
        val i = source.items.indexOfFirst { it.id == m.id }
        source.replace(i) { it.copy(dateAddedSec = addedSec, takenAtMs = addedSec * 1000) }
        return source.items[i]
    }

    private fun SendEnv.names(): Set<String> =
        runBlocking { db.send().all().filter { !it.sha.startsWith("m") }.map { it.name }.toSet() }

    private fun SendEnv.clearLedger() = runBlocking {
        db.send().all().forEach { db.send().delete(it.sha) }
        source.items.forEach { db.send().deleteSeen(it.id) }
    }

    // ---- 시작 시점 선택 ----

    @Test
    fun `안 보냄이면 역할 시각 이후 것만, 전부면 전부, 날짜면 그 날짜 이후만 스캔한다`() = runBlocking {
        val e = env()
        e.addOld("old.jpg", 1, 100); e.addOld("mid.jpg", 2, 5_000); e.addOld("new.jpg", 3, 9_000)

        e.settings = SenderSettings(roleSetAtSec = 3_000, sendFromSec = null)
        e.scanner.scan()
        assertEquals(setOf("mid.jpg", "new.jpg"), e.names())

        e.clearLedger()
        e.settings = SenderSettings(roleSetAtSec = 3_000, sendFromSec = 0L)
        e.scanner.scan()
        assertEquals(setOf("old.jpg", "mid.jpg", "new.jpg"), e.names())

        e.clearLedger()
        e.settings = SenderSettings(roleSetAtSec = 3_000, sendFromSec = 6_000L)
        e.scanner.scan()
        assertEquals(setOf("new.jpg"), e.names())
    }

    @Test
    fun `기준을 바꿔도 이미 장부에 있는 파일은 다시 보내지 않는다`() = runBlocking {
        val e = env()
        e.addOld("a.jpg", 11, 5_000); e.addOld("b.jpg", 12, 100); e.addOld("c.jpg", 13, 200)
        e.settings = SenderSettings(roleSetAtSec = 3_000)
        e.scanner.scan()
        val shaA = Hashing.sha256Hex(randomBytes(1000, 11))
        assertTrue(e.ledger.tryTransition(shaA, SendState.RECEIVED))          // 이미 보낸 파일

        e.settings = SenderSettings(roleSetAtSec = 3_000, sendFromSec = 0L)    // 전부로 바꿈
        val r = e.scanner.scan()
        assertEquals(2, r.registered)                                          // 새로 올라온 건 b, c 뿐
        assertEquals(SendState.RECEIVED, e.ledger.get(shaA)!!.stateEnum)       // a 는 그대로(다시 대기로 안 돌아감)
        assertEquals(3, e.names().size)
        assertEquals(0, e.scanner.scan().registered)                           // 또 돌려도 중복 없음
    }

    @Test
    fun `설정값 - 기준 시각과 선택지 변환`() {
        assertEquals(500L, SenderSettings(roleSetAtSec = 500).scanFromSec)
        assertEquals(BackfillMode.NONE, SenderSettings(roleSetAtSec = 500).backfillMode)
        assertEquals(0L, SenderSettings(roleSetAtSec = 500, sendFromSec = 0L).scanFromSec)
        assertEquals(BackfillMode.ALL, SenderSettings(sendFromSec = 0L).backfillMode)
        assertEquals(900L, SenderSettings(roleSetAtSec = 500, sendFromSec = 900L).scanFromSec)
        assertEquals(BackfillMode.SINCE, SenderSettings(sendFromSec = 900L).backfillMode)
        assertNull(SenderSettings.sendFromFor(BackfillMode.NONE, 123))
        assertEquals(0L, SenderSettings.sendFromFor(BackfillMode.ALL, 123))
        assertEquals(123L, SenderSettings.sendFromFor(BackfillMode.SINCE, 123))
    }

    // ---- 오래된 파일 안정 처리 ----

    @Test
    fun `수정 시각이 충분히 과거인 파일은 첫 스캔에서 바로 등록, 최근 파일·기록 중 파일은 기다린다`() = runBlocking {
        val e = env()
        val nowSec = e.clock.nowMs() / 1000
        e.source.add("old.jpg", randomBytes(500, 21), modifiedSec = nowSec - 700)
        e.source.add("fresh.jpg", randomBytes(500, 22), modifiedSec = nowSec - 30)
        e.source.add("oldpending.mp4", randomBytes(500, 23), modifiedSec = nowSec - 5_000, pending = true, video = true)
        val r = e.scanner.scan()
        assertEquals(1, r.registered)
        assertEquals(setOf("old.jpg"), e.names())
        assertTrue(r.recheckSoon)                     // 최근·기록 중 파일은 다시 봐야 함
        e.clock.advance(61_000)
        val r2 = e.scanner.scan()
        assertEquals(1, r2.registered)                // fresh 는 60초 뒤 등록
        assertEquals(setOf("old.jpg", "fresh.jpg"), e.names())   // IS_PENDING 파일은 계속 제외
    }

    @Test
    fun `오래됐어도 전송 직전 재검사에서 수정 시각이 바뀌었으면 보내지 않는다`() = runBlocking {
        val recv = RecvEnv().also { recvs += it }
        val e = env()
        e.addOld("a.jpg", 24, 5_000)
        e.settings = SenderSettings(roleSetAtSec = 0)
        e.scanner.scan()
        e.source.replace(0) { it.copy(dateModifiedSec = it.dateModifiedSec + 1) }
        e.lanApi = CoreBackedPixelApi(recv)
        e.engine.runOnce()
        val x = e.db.send().all().single { !it.sha.startsWith("m") }
        assertEquals(SendState.SKIPPED, x.stateEnum)
        assertEquals(Reason.ORIGINAL_CHANGED, x.reason)
    }

    // ---- 새 사진 우선 순서 ----

    @Test
    fun `해시도 전송도 최근 것부터 한다`() = runBlocking {
        val recv = RecvEnv().also { recvs += it }
        val e = env(maxHash = 1)
        e.settings = SenderSettings(sendFromSec = 0L)
        e.addOld("d1.jpg", 31, 1_000); e.addOld("d2.jpg", 32, 2_000); e.addOld("d3.jpg", 33, 3_000)
        // 한 번에 하나만 해시하므로 가장 최근 것부터 장부에 오른다
        e.scanner.scan(); assertEquals(setOf("d3.jpg"), e.names())
        e.scanner.scan(); assertEquals(setOf("d3.jpg", "d2.jpg"), e.names())
        e.scanner.scan(); assertEquals(setOf("d3.jpg", "d2.jpg", "d1.jpg"), e.names())
        // 전송 순서도 최근 것 먼저
        assertEquals(listOf("d3.jpg", "d2.jpg", "d1.jpg"), e.ledger.sendable().map { it.name })
        // 밀린 옛 사진이 있는 중에 방금 찍은 사진이 오면 그것이 가장 앞
        e.addOld("fresh.jpg", 34, 9_000)
        e.scanner.scan()
        assertEquals("fresh.jpg", e.ledger.sendable().first().name)
        val api = CoreBackedPixelApi(recv); e.lanApi = api
        e.engine.runOnce()
        assertEquals(Hashing.sha256Hex(randomBytes(1000, 34)), api.beginShas.first())
    }

    // ---- 해시 예산 이어 하기 ----

    @Test
    fun `개수 예산이 다하면 나머지는 다음 실행에서 이어 하고 중복 없이 모두 등록된다`() = runBlocking {
        val e = env(maxHash = 2)
        e.settings = SenderSettings(sendFromSec = 0L)
        for (i in 1..5) e.addOld("f$i.jpg", 40L + i, 1_000L * i)
        val r1 = e.scanner.scan(); assertEquals(2, r1.registered); assertEquals(3, r1.pendingHash)
        val r2 = e.scanner.scan(); assertEquals(2, r2.registered); assertEquals(1, r2.pendingHash)
        val r3 = e.scanner.scan(); assertEquals(1, r3.registered); assertEquals(0, r3.pendingHash)
        assertEquals(5, e.names().size)
        val r4 = e.scanner.scan(); assertEquals(0, r4.registered); assertEquals(0, r4.pendingHash)
    }

    @Test
    fun `시간 예산이 다해도 같은 방식으로 이어 한다`() = runBlocking {
        val e = env(hashBudgetMs = 100)
        e.settings = SenderSettings(sendFromSec = 0L)
        for (i in 1..5) e.addOld("t$i.jpg", 50L + i, 1_000L * i)
        e.source.onOpen = { e.clock.advance(60) }          // 파일 하나 여는 데 60ms
        // 첫 파일은 끝내고, 둘째는 읽는 도중 예산(100ms)이 끊겨 처음부터 다시 해야 한다(해시 도중에도 시간을 본다)
        val r1 = e.scanner.scan()
        assertEquals(1, r1.registered); assertEquals(4, r1.pendingHash)
        repeat(4) { e.scanner.scan() }            // 끊긴 파일은 기억돼 건너뛰고 나머지를 먼저 처리
        e.scanner.scan(allowLong = true, deadlineMs = e.clock.nowMs() + 300_000L)   // 남은 미룬 파일은 한가한 실행에서
        assertEquals(5, e.names().size)
    }

    @Test
    fun `엔진은 해시가 남으면 바로 다음 워커를 부르고 남은 개수를 기록하며 드라이브 안 쓰면 올리지 않는다`() = runBlocking {
        val e = env(maxHash = 2)
        e.driveOn = false
        e.net.snap = com.pixelrelay.core.NetSnapshot(true, false, false)   // 집 밖(과금망): 원래라면 드라이브 경로
        e.settings = SenderSettings(sendFromSec = 0L)
        for (i in 1..3) e.addOld("g$i.jpg", 60L + i, 1_000L * i)
        val r1 = e.engine.runOnce()
        assertTrue(r1.moreWork)
        assertEquals(1L, e.meta.getLong(SenderEngine.KEY_SCAN_PENDING))
        val r2 = e.engine.runOnce()
        assertFalse(r2.moreWork)
        assertEquals(0L, e.meta.getLong(SenderEngine.KEY_SCAN_PENDING))
        assertEquals(3, e.names().size)
        assertTrue(e.drive.startedUploads.isEmpty())        // 드라이브 연결 안 함: 드라이브로 올리지 않는다
    }

    // ---- 픽셀 공간 부족 ----

    @Test
    fun `픽셀이 공간 부족으로 거절하면 영구 실패 없이 대기로 두고 한 번만 묻고 나중에 이어 보낸다`() = runBlocking {
        val recv = RecvEnv().also { recvs += it }
        val e = env()
        e.settings = SenderSettings(sendFromSec = 0L)
        for (i in 1..3) e.addOld("s$i.jpg", 70L + i, 1_000L * i)
        e.scanner.scan()
        val api = CoreBackedPixelApi(recv); e.lanApi = api
        recv.free = 3L * 1024 * 1024 * 1024                 // 크기 + 4GB 보다 작음 -> 507

        e.engine.runOnce()
        val rows = e.db.send().all().filter { !it.sha.startsWith("m") }
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.stateEnum == SendState.WAITING && it.reason == Reason.PIXEL_SPACE && it.attempts == 0 })
        assertEquals(1, api.beginCalls)                      // 첫 거절 뒤 같은 크기 이상은 묻지 않음
        assertTrue(e.ledger.sendable().isEmpty())            // 10분은 쉰다
        val p = SendProgress.of(rows, 0)
        assertEquals(3, p.pixelSpaceWaiting)
        assertTrue(p.lines().any { it.contains("픽셀 공간 부족으로 대기 중 3개") })

        recv.free = 100L * 1024 * 1024 * 1024               // 픽셀이 포토 공간을 비움
        e.clock.advanceMin(11)
        e.engine.runOnce()
        val after = e.db.send().all().filter { !it.sha.startsWith("m") }
        assertTrue(after.all { it.stateEnum == SendState.RECEIVED })
    }

    @Test
    fun `진행 요약 - 남은 개수와 확인 중 개수`() {
        fun en(sha: String, st: SendState, reason: String? = null) = mkEntry(sha = sha, state = st.name).copy(reason = reason)
        val list = listOf(
            en("a".repeat(64), SendState.WAITING), en("b".repeat(64), SendState.WAITING, Reason.PIXEL_SPACE),
            en("c".repeat(64), SendState.IN_DRIVE), en("d".repeat(64), SendState.RECEIVED), en("m5", SendState.WAITING, Reason.PERMISSION),
        )
        val p = SendProgress.of(list, 120)
        assertEquals(3, p.remaining); assertEquals(1, p.pixelSpaceWaiting); assertEquals(120, p.scanPending)
        val lines = p.lines()
        assertTrue(lines.any { it.contains("기존 사진 확인 중: 120개") })
        assertTrue(lines.any { it.contains("보낼 파일 남음: 3개") })
        assertTrue(SendProgress.of(emptyList(), 0).lines().isEmpty())
    }

    // ---- 큰 파일 해시 정책 ----

    @Test
    fun `큰 파일 하나가 예산보다 오래 걸려도 도중에 멈추고, 한가한 실행에서 끝까지 등록된다`() = runBlocking {
        val e = env(hashBudgetMs = 100)
        e.settings = SenderSettings(sendFromSec = 0L)
        e.addOld("big.mp4", 80, 5_000, bytes = 1_000_000, video = true)
        e.source.onRead = { e.clock.advance(40) }          // 256KB 조각 하나 읽을 때마다 40ms -> 약 160ms 걸림(예산 100ms 초과)
        val r1 = e.scanner.scan()
        assertEquals(0, r1.registered)                       // 도중에 끊김: 등록 안 됨
        assertEquals(1, r1.pendingHash); assertEquals(1, r1.deferredLarge)
        // 한가한 실행(allowLong)은 마감을 길게 받아 끝까지 해시한다 -> 무한 반복 없이 결국 등록
        val r2 = e.scanner.scan(allowLong = true, deadlineMs = e.clock.nowMs() + 5 * 60_000L)
        assertEquals(1, r2.registered); assertEquals(0, r2.pendingHash)
        assertEquals(setOf("big.mp4"), e.names())
    }

    @Test
    fun `남은 시간에 못 끝날 것으로 어림한 큰 파일은 미루고 작은 파일은 먼저 처리한다`() = runBlocking {
        val e = env(hashBudgetMs = 10_000)
        e.settings = SenderSettings(sendFromSec = 0L)
        val big = e.addOld("huge.mp4", 81, 9_000, video = true)
        e.source.replace(e.source.items.indexOfFirst { it.id == big.id }) { it.copy(size = 9L * 1024 * 1024 * 1024) }   // 약 60초 -> 10초 예산 초과
        e.addOld("small.jpg", 82, 1_000)
        val r1 = e.scanner.scan()
        assertEquals(setOf("small.jpg"), e.names())
        assertEquals(1, r1.deferredLarge)
        val r2 = e.scanner.scan(allowLong = true, deadlineMs = e.clock.nowMs() + 5 * 60_000L)
        assertEquals(setOf("small.jpg", "huge.mp4"), e.names())
        assertEquals(0, r2.pendingHash)
    }

    @Test
    fun `엔진 - 미룬 큰 파일은 할 일이 없는 실행에서 끝까지 해시하고, 보낼 것이 있으면 전송을 먼저 한다`() = runBlocking {
        val recv = RecvEnv().also { recvs += it }
        val e = env(hashBudgetMs = 10_000)
        e.settings = SenderSettings(sendFromSec = 0L)
        val big = e.addOld("huge.mp4", 83, 9_000, video = true)
        e.source.replace(e.source.items.indexOfFirst { it.id == big.id }) { it.copy(size = 9L * 1024 * 1024 * 1024) }
        e.addOld("small.jpg", 84, 1_000)
        val api = CoreBackedPixelApi(recv); e.lanApi = api
        // 첫 실행: 작은 파일은 등록·전송되고, 큰 파일은 미뤄진다(전송이 해시에 막히지 않음)
        val r1 = e.engine.runOnce()
        assertEquals(1, r1.sentFiles)
        assertEquals(setOf("small.jpg"), e.names())
        // 둘째 실행: 보낼 것이 없으니 한가한 실행으로 큰 파일을 끝까지 해시해 등록하고 다음 워커를 부른다
        val r2 = e.engine.runOnce()
        assertEquals(setOf("small.jpg", "huge.mp4"), e.names())
        assertTrue(r2.moreWork)
    }

    // ---- 드라이브 연결 안 함 전환 ----

    @Test
    fun `연결 안 함으로 바뀌면 끝나지 않은 드라이브 업로드는 대기로 되돌려 LAN 으로 보내고 IN_DRIVE 는 그대로 둔다`() = runBlocking {
        val recv = RecvEnv().also { recvs += it }
        val e = env()
        e.net.snap = com.pixelrelay.core.NetSnapshot(true, false, false)    // 집 밖
        val bUp = randomBytes(30_000, 90); val bIn = randomBytes(2_000, 91)
        e.source.add("up.jpg", bUp); e.source.add("in.jpg", bIn)
        e.registerNow()
        e.drive.failAfterTotalBytes = 9_000
        e.engine.runOnce()
        val shaUp = Hashing.sha256Hex(bUp); val shaIn = Hashing.sha256Hex(bIn)
        val states = setOf(e.ledger.get(shaUp)!!.stateEnum, e.ledger.get(shaIn)!!.stateEnum)
        assertTrue(SendState.UPLOADING_DRIVE in states)
        // IN_DRIVE 하나를 만들기 위해 하나는 강제로 IN_DRIVE
        val upState = e.ledger.get(shaUp)!!.stateEnum
        val inDriveSha = if (upState == SendState.UPLOADING_DRIVE) shaIn else shaUp
        val upSha = if (upState == SendState.UPLOADING_DRIVE) shaUp else shaIn
        e.ledger.tryTransition(inDriveSha, SendState.IN_DRIVE)
        assertEquals(SendState.UPLOADING_DRIVE, e.ledger.get(upSha)!!.stateEnum)

        e.driveOn = false
        e.net.snap = com.pixelrelay.core.NetSnapshot(true, true, false)     // 집 와이파이
        e.lanApi = CoreBackedPixelApi(recv)
        e.clock.advance(120_000)
        e.engine.runOnce()
        assertEquals(SendState.RECEIVED, e.ledger.get(upSha)!!.stateEnum)    // LAN 으로 보냄
        assertNull(e.ledger.get(upSha)!!.driveSessionUri)
        assertEquals(SendState.IN_DRIVE, e.ledger.get(inDriveSha)!!.stateEnum)   // 중복 전송 방지
        assertNull(recv.ledger.get(inDriveSha))
        val p = SendProgress.of(e.db.send().all(), 0, driveUsed = false)
        assertTrue(p.lines().any { it.contains("드라이브에 남은 1개(드라이브 연결 필요)") })
        assertTrue(SendProgress.of(e.db.send().all(), 0, driveUsed = true).lines().none { it.contains("드라이브에 남은") })
    }

    @Test
    fun `연결 안 함이면 LAN 이 안 될 때 명령을 드라이브 파일로 돌리지 않고 실패로 알린다`() = runBlocking {
        val e = env()
        e.driveOn = false
        e.lanApi = null
        val c = com.pixelrelay.sender.SenderCommands(e.lan, e.drive, e.net) { e.driveOn }
        val r = runCatching { c.send(com.pixelrelay.core.RemoteCommand(com.pixelrelay.core.RemoteCommand.SEND_NOW)) }
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("연결 안 함"))
        assertTrue(e.drive.commands.isEmpty())
        e.driveOn = true
        assertEquals("드라이브", c.send(com.pixelrelay.core.RemoteCommand(com.pixelrelay.core.RemoteCommand.SEND_NOW)))
    }

    @Test
    fun `예산 초과로 끊긴 큰 파일은 기억해 두고 작은 파일을 먼저 등록하며 한가한 실행에서 끝나고 무한 반복하지 않는다`() = runBlocking {
        val e = env(hashBudgetMs = 100)
        e.driveOn = false
        e.settings = SenderSettings(sendFromSec = 0L)
        e.addOld("big.mp4", 90, 9_000, bytes = 1_000_000, video = true)      // 가장 최근 = 가장 먼저 시도됨
        for (i in 1..3) e.addOld("small$i.jpg", 90L + i, 1_000L * i, bytes = 100)
        val bigId = e.source.items.first { it.name == "big.mp4" }.id
        e.source.onRead = { if (e.source.items.isNotEmpty()) e.clock.advance(40) }   // 큰 파일(4조각)만 예산을 넘기고 작은 파일(1조각)은 안 넘김
        var runs = 0
        var more = true
        while (more && runs < 6) { more = e.engine.runOnce().moreWork; runs++ }
        // 상한(6회) 안에서 반복이 끝나야 하고, 작은 파일 3개는 모두 등록되어 있어야 한다
        assertTrue("runs=$runs", runs < 6)
        assertTrue(e.names().containsAll(setOf("small1.jpg", "small2.jpg", "small3.jpg")))
        // 한가한 실행(엔진이 보낼 것이 없을 때)에서 큰 파일도 끝까지 해시되어 모두 등록되고, 끊김 표시는 지워진다
        assertEquals(setOf("big.mp4", "small1.jpg", "small2.jpg", "small3.jpg"), e.names())
        val big = e.source.items.first { it.id == bigId }
        assertFalse(e.ledger.isHashDeferred(big.id, big.size, big.dateModifiedSec))
    }

    @Test
    fun `짧은 스캔에서 시간 초과로 끊긴 파일은 기억되어 다음 짧은 스캔이 건너뛰고 뒤의 파일을 먼저 처리한다`() = runBlocking {
        val e = env(hashBudgetMs = 100)
        e.settings = SenderSettings(sendFromSec = 0L)
        e.addOld("big.mp4", 95, 9_000, bytes = 1_000_000, video = true)
        for (i in 1..3) e.addOld("small$i.jpg", 95L + i, 1_000L * i, bytes = 100)
        val big = e.source.items.first { it.name == "big.mp4" }
        e.source.onRead = { e.clock.advance(40) }
        val r1 = e.scanner.scan()
        assertTrue(e.ledger.isHashDeferred(big.id, big.size, big.dateModifiedSec))      // 끊긴 파일 기억
        assertEquals(1, r1.deferredLarge)
        e.source.onRead = null                                                        // 이후 스캔은 시간이 흐르지 않음
        // 둘째 짧은 스캔: 큰 파일은 건너뛰고 작은 파일 3개가 등록된다. 남은 것은 미룬 파일뿐
        val r2 = e.scanner.scan()
        assertEquals(setOf("small1.jpg", "small2.jpg", "small3.jpg"), e.names())
        assertEquals(1, r2.pendingHash); assertEquals(1, r2.deferredLarge)               // 미룬 것만 남음 -> 엔진은 즉시 재예약하지 않음
        // 한가한 실행에서 끝까지
        e.scanner.scan(allowLong = true, deadlineMs = e.clock.nowMs() + 300_000L)
        assertTrue(e.names().contains("big.mp4"))
        assertFalse(e.ledger.isHashDeferred(big.id, big.size, big.dateModifiedSec))
    }
}
