package com.pixelrelay

import com.pixelrelay.charge.ChargePolicy
import com.pixelrelay.charge.PlugAction
import com.pixelrelay.core.GB
import com.pixelrelay.core.Hashing
import com.pixelrelay.core.LedgerItem
import com.pixelrelay.core.NetSnapshot
import com.pixelrelay.core.Reason
import com.pixelrelay.core.RecvState
import com.pixelrelay.core.SendState
import com.pixelrelay.core.SenderSettings
import com.pixelrelay.core.WarnCode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 완료 기준 13: 기록 중 파일 / 크기 초과 / 폴더 / 권한 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScannerTest {
    private val dbs = ArrayList<com.pixelrelay.core.db.AppDb>()
    @After fun tearDown() { dbs.forEach { runCatching { it.close() } } }
    private fun env() = SendEnv(TestDb.memory().also { dbs += it })

    @Test
    fun `IS_PENDING 파일은 해시도 등록도 하지 않는다`() = runBlocking {
        val e = env()
        val b = randomBytes(2000, 70)
        e.source.add("rec.mp4", b, pending = true, video = true)
        e.scanner.scan(); e.clock.advance(120_000); val r = e.scanner.scan()
        assertTrue(e.db.send().all().isEmpty())
        assertTrue(r.recheckSoon)
    }

    @Test
    fun `60초 동안 크기·수정 시각이 그대로여야 해시한다(바뀌면 처음부터)`() = runBlocking {
        val e = env()
        val b = randomBytes(2000, 71)
        val recent = e.clock.nowMs() / 1000   // 수정 시각이 방금(최근)이어야 60초 안정 조건을 기다린다
        e.source.add("a.jpg", b, modifiedSec = recent)
        e.scanner.scan()
        e.clock.advance(59_000)
        assertEquals(0, e.scanner.scan().registered)               // 59초: 아직
        // 수정 시각이 바뀌면 기준 시각이 다시 시작
        e.source.replace(0) { it.copy(dateModifiedSec = recent + 1) }
        e.scanner.scan()
        e.clock.advance(59_000)
        assertEquals(0, e.scanner.scan().registered)
        // 크기가 바뀌어도 마찬가지
        e.source.replace(0) { it.copy(size = it.size + 10) }
        e.scanner.scan()
        e.clock.advance(59_000)
        assertEquals(0, e.scanner.scan().registered)
        assertTrue(e.db.send().all().isEmpty())
        e.clock.advance(2_000)
        val r = e.scanner.scan()
        assertEquals(1, r.registered)
        assertEquals(SendState.WAITING, e.db.send().all().single().stateEnum)
    }

    @Test
    fun `크기 초과는 SKIPPED(크기초과)`() = runBlocking {
        val e = env()
        e.source.add("big.jpg", randomBytes(100, 72), sizeOverride = 201L * 1024 * 1024)
        e.source.add("huge.mp4", randomBytes(100, 73), video = true, sizeOverride = 11L * GB)
        e.source.add("ok.mp4", randomBytes(100, 74), video = true, sizeOverride = 9L * GB)    // 영상은 10GB 까지 허용
        e.scanner.scan()
        val all = e.db.send().all()
        val skipped = all.filter { it.stateEnum == SendState.SKIPPED }
        assertEquals(2, skipped.size)
        assertTrue(skipped.all { it.reason == Reason.TOO_LARGE })
        // 큰 파일은 해시·전송하지 않는다
        e.clock.advance(61_000); e.scanner.scan()
        assertEquals(1, e.ledger.sendable().size)
    }

    @Test
    fun `제외 폴더는 SKIPPED 이고 감시 폴더가 아니면 무시`() = runBlocking {
        val e = env()
        e.settings = SenderSettings(watchFolders = listOf("DCIM", "Pictures"), excludeFolders = listOf("Screenshots"))
        e.source.add("s.png", randomBytes(100, 75), rel = "Pictures/Screenshots/")
        e.source.add("elsewhere.jpg", randomBytes(100, 76), rel = "Download/")
        e.source.add("cam.jpg", randomBytes(100, 77), rel = "DCIM/Camera/")
        e.registerNow()
        val all = e.db.send().all()
        assertEquals(2, all.size)
        assertEquals(Reason.EXCLUDED, all.single { it.name == "s.png" }.reason)
        assertEquals(SendState.WAITING, all.single { it.name == "cam.jpg" }.stateEnum)
    }

    @Test
    fun `역할을 정한 시각 이전에 추가된 파일은 보내지 않는다`() = runBlocking {
        val e = env()
        e.settings = SenderSettings(roleSetAtSec = e.clock.nowMs() / 1000 + 10)
        e.source.add("old.jpg", randomBytes(100, 78))
        e.registerNow()
        assertTrue(e.db.send().all().isEmpty())
    }

    @Test
    fun `부분 허용이면 자동 감지를 멈춘다`() = runBlocking {
        val e = env()
        e.source.full = false
        e.source.add("a.jpg", randomBytes(100, 79))
        e.scanner.scan(); e.clock.advance(61_000); val r = e.scanner.scan()
        assertTrue(r.partialAccess)
        assertTrue(e.db.send().all().isEmpty())
        assertTrue(e.db.send().all().isEmpty() && e.db.send().seen(1) == null)
    }

    @Test
    fun `위치 권한이 없으면 해시하지 않고 WAITING(권한필요), 허용되면 같은 파일이 sha 로 등록`() = runBlocking {
        val e = env()
        e.source.location = false
        val b = randomBytes(300, 80)
        e.source.add("a.jpg", b)
        e.registerNow()
        val ph = e.db.send().all().single()
        assertEquals(SendState.WAITING, ph.stateEnum)
        assertEquals(Reason.PERMISSION, ph.reason)
        assertTrue(ph.sha.startsWith("m"))
        assertTrue(e.engine.runOnce().sentFiles == 0)           // 자리표시 항목은 전송하지 않는다

        e.source.location = true
        e.scanner.scan()
        val after = e.db.send().all().single()
        assertEquals(Hashing.sha256Hex(b), after.sha)
        assertEquals(SendState.WAITING, after.stateEnum)
    }

    @Test
    fun `같은 내용의 두 파일은 장부에 한 번만 오른다`() = runBlocking {
        val e = env()
        val b = randomBytes(400, 81)
        e.source.add("a.jpg", b); e.source.add("copy_of_a.jpg", b)
        e.registerNow()
        assertEquals(1, e.db.send().all().size)
    }
}

/** 완료 기준 12: 드라이브 업로드 (+ 경로 혼선 방지) */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriveUploadTest {
    private val dbs = ArrayList<com.pixelrelay.core.db.AppDb>()
    @After fun tearDown() { dbs.forEach { runCatching { it.close() } } }
    private fun env() = SendEnv(TestDb.memory().also { dbs += it }).also { it.net.snap = NetSnapshot(true, false, false) }

    @Test
    fun `새 파일은 queue 로 올라가고 sha256Checksum 이 맞아야 IN_DRIVE`() = runBlocking {
        val e = env()
        val b = randomBytes(20_000, 90)
        e.source.add("IMG_1.jpg", b)
        e.registerNow()
        e.engine.runOnce()
        val sha = Hashing.sha256Hex(b)
        val en = e.ledger.get(sha)!!
        assertEquals(SendState.IN_DRIVE, en.stateEnum)
        assertEquals(1, e.drive.files.size)
        val f = e.drive.files.values.single()
        assertEquals("queue/$sha.jpg", f.name)
        assertEquals(sha, f.props["sha"]); assertEquals("IMG_1.jpg", f.props["name"]); assertEquals("20000", f.props["size"])
        assertEquals(sha, f.actualSha)
        assertEquals(f.id, en.driveFileId)
        assertTrue(e.drive.illegalDeletes().isEmpty())
        assertEquals(0, e.drive.deletes.size)
    }

    @Test
    fun `같은 sha 가 이미 queue 에 있으면 업로드 없이 IN_DRIVE`() = runBlocking {
        val e = env()
        val b = randomBytes(5000, 91)
        val sha = Hashing.sha256Hex(b)
        val existing = e.drive.put(sha, "IMG_1.jpg", b)
        e.source.add("IMG_1.jpg", b)
        e.registerNow()
        e.engine.runOnce()
        assertEquals(SendState.IN_DRIVE, e.ledger.get(sha)!!.stateEnum)
        assertEquals(existing.id, e.ledger.get(sha)!!.driveFileId)
        assertTrue(e.drive.startedUploads.isEmpty())
        assertEquals(1, e.drive.files.size)
    }

    @Test
    fun `sha256Checksum 이 다르면 그 사본을 삭제하고 다시 시도해 결국 올린다`() = runBlocking {
        val e = env()
        val b = randomBytes(9_000, 92)
        val sha = Hashing.sha256Hex(b)
        e.source.add("a.jpg", b)
        e.registerNow()
        e.drive.corruptChecksum = true
        e.engine.runOnce()
        assertEquals(1, e.drive.deletes.size)                    // 방금 만든 불량 사본만 삭제
        assertTrue(e.drive.deletes[0].allowed)
        assertTrue(e.drive.files.isEmpty())
        val mid = e.ledger.get(sha)!!
        assertTrue(mid.stateEnum != SendState.IN_DRIVE)
        assertEquals(1, mid.attempts)

        e.drive.corruptChecksum = false
        e.clock.advanceMin(2)
        e.engine.runOnce()
        assertEquals(SendState.IN_DRIVE, e.ledger.get(sha)!!.stateEnum)
        assertEquals(1, e.drive.files.size)
        assertTrue(e.drive.illegalDeletes().isEmpty())
    }

    @Test
    fun `끊겼다 다시 붙으면 같은 세션으로 이어서 올린다`() = runBlocking {
        val e = env()
        val b = randomBytes(30_000, 93)
        val sha = Hashing.sha256Hex(b)
        e.source.add("a.jpg", b)
        e.registerNow()
        e.drive.failAfterTotalBytes = 9_000                      // 두 조각(8192) 뒤 끊김
        e.engine.runOnce()
        val mid = e.ledger.get(sha)!!
        assertEquals(SendState.UPLOADING_DRIVE, mid.stateEnum)
        assertNotNull(mid.driveSessionUri)
        assertEquals(1, e.drive.startedUploads.size)
        e.clock.advance(60_000)
        e.engine.runOnce()
        assertEquals(SendState.IN_DRIVE, e.ledger.get(sha)!!.stateEnum)
        assertEquals(1, e.drive.startedUploads.size)             // 새 세션을 만들지 않았다
        assertEquals(sha, e.drive.files.values.single().actualSha)
    }

    @Test
    fun `IN_DRIVE 가 된 파일은 집에 와도 LAN 으로 다시 보내지 않는다`() = runBlocking {
        val recv = RecvEnv()
        try {
            val e = env()
            val b = randomBytes(6_000, 94)
            val sha = Hashing.sha256Hex(b)
            e.source.add("a.jpg", b)
            e.registerNow()
            e.engine.runOnce()                                   // 모바일 -> 드라이브
            assertEquals(SendState.IN_DRIVE, e.ledger.get(sha)!!.stateEnum)

            e.net.snap = NetSnapshot(true, true, false)          // 집 와이파이
            e.lanApi = CoreBackedPixelApi(recv)
            e.clock.advanceMin(5)
            e.engine.runOnce()
            assertEquals(SendState.IN_DRIVE, e.ledger.get(sha)!!.stateEnum)
            assertNull(recv.ledger.get(sha))                     // 픽셀에 LAN 으로 안 갔다
        } finally { recv.close() }
    }

    @Test
    fun `드라이브 업로드 중 집에 오면 업로드를 마저 끝낸다(LAN 으로 갈아타지 않는다)`() = runBlocking {
        val recv = RecvEnv()
        try {
            val e = env()
            val b = randomBytes(30_000, 95)
            val sha = Hashing.sha256Hex(b)
            e.source.add("a.jpg", b)
            e.registerNow()
            e.drive.failAfterTotalBytes = 9_000
            e.engine.runOnce()
            assertEquals(SendState.UPLOADING_DRIVE, e.ledger.get(sha)!!.stateEnum)

            e.net.snap = NetSnapshot(true, true, false)
            e.lanApi = CoreBackedPixelApi(recv)
            e.clock.advance(60_000)
            e.engine.runOnce()
            assertEquals(SendState.IN_DRIVE, e.ledger.get(sha)!!.stateEnum)
            assertNull(recv.ledger.get(sha))
        } finally { recv.close() }
    }

    @Test
    fun `영상 + 과금망 + 설정 꺼짐이면 업로드하지 않고 대기, 지금 보내기는 보낸다`() = runBlocking {
        val e = env()
        val b = randomBytes(5_000, 96)
        e.source.add("v.mp4", b, video = true)
        e.registerNow()
        e.engine.runOnce()
        assertEquals(SendState.WAITING, e.entryFor(b)!!.stateEnum)
        assertTrue(e.drive.files.isEmpty())
        e.engine.runOnce(forceNow = true)
        assertEquals(SendState.IN_DRIVE, e.entryFor(b)!!.stateEnum)
    }

    @Test
    fun `하루 데이터 한도를 넘기면 대기, 모바일로 올린 만큼 사용량이 쌓인다`() = runBlocking {
        val e = env()
        e.settings = SenderSettings(dailyLimitBytes = 12_000)
        val b1 = randomBytes(8_000, 97); val b2 = randomBytes(8_000, 98)
        e.source.add("a.jpg", b1); e.source.add("b.jpg", b2)
        e.registerNow()
        e.engine.runOnce()
        val states = listOf(e.entryFor(b1)!!.stateEnum, e.entryFor(b2)!!.stateEnum)
        assertEquals(1, states.count { it == SendState.IN_DRIVE })
        assertEquals(1, states.count { it == SendState.WAITING })
        assertEquals(Reason.DAILY_LIMIT, listOf(e.entryFor(b1)!!, e.entryFor(b2)!!).single { it.stateEnum == SendState.WAITING }.reason)
        assertEquals(8_000L, e.usage.today())
    }
}

/** 완료 기준 14: 충전 정책 */
class ChargePolicyTest {
    private val clock = FakeClock()

    @Test fun `80 이상은 끄기, 60 이하는 켜기, 사이는 유지`() {
        val p = ChargePolicy(clock)
        assertEquals(PlugAction.TURN_OFF, p.evaluate(80, true, 30.0).plugAction)
        assertEquals(PlugAction.TURN_OFF, p.evaluate(95, true, 30.0).plugAction)
        assertEquals(PlugAction.KEEP, p.evaluate(79, true, 30.0).plugAction)
        assertEquals(PlugAction.KEEP, p.evaluate(61, false, 30.0).plugAction)
        assertEquals(PlugAction.TURN_ON, p.evaluate(60, false, 30.0).plugAction)
        assertEquals(PlugAction.TURN_ON, p.evaluate(10, false, 30.0).plugAction)
    }

    @Test fun `플러그가 없으면 경고만 - 충전 중 80 이상 UNPLUG, 미충전 60 이하 PLUG`() {
        val p = ChargePolicy(clock)
        assertTrue(WarnCode.UNPLUG_SUGGESTED in p.evaluate(85, true, 30.0).active)
        assertFalse(WarnCode.UNPLUG_SUGGESTED in p.evaluate(85, false, 30.0).active)
        assertTrue(WarnCode.PLUG_SUGGESTED in p.evaluate(55, false, 30.0).active)
        assertFalse(WarnCode.PLUG_SUGGESTED in p.evaluate(55, true, 30.0).active)
        assertTrue(WarnCode.LOW_BATTERY in p.evaluate(20, false, 30.0).active)
        assertFalse(WarnCode.LOW_BATTERY in p.evaluate(21, false, 30.0).active)
    }

    @Test fun `온도 40 초과면 중지, 38 미만이 돼야 재개(사이는 유지)`() {
        val p = ChargePolicy(clock)
        assertFalse(p.evaluate(70, true, 40.0).receiveStopped)      // 40.0 은 초과가 아님
        assertTrue(p.evaluate(70, true, 40.1).receiveStopped)
        assertTrue(WarnCode.HIGH_TEMP in p.evaluate(70, true, 40.1).active)
        assertTrue(p.evaluate(70, true, 39.0).receiveStopped)       // 38~40 사이는 유지
        assertTrue(p.evaluate(70, true, 38.0).receiveStopped)
        assertFalse(p.evaluate(70, true, 37.9).receiveStopped)
        assertFalse(p.evaluate(70, true, 39.0).receiveStopped)      // 재개 뒤 39 는 그대로 재개 상태
    }

    @Test fun `같은 경고는 30분 간격으로만 다시 알린다`() {
        val p = ChargePolicy(clock)
        assertTrue(WarnCode.UNPLUG_SUGGESTED in p.evaluate(85, true, 30.0).emit)
        clock.advanceMin(10)
        assertFalse(WarnCode.UNPLUG_SUGGESTED in p.evaluate(85, true, 30.0).emit)
        clock.advanceMin(19)
        assertFalse(WarnCode.UNPLUG_SUGGESTED in p.evaluate(85, true, 30.0).emit)    // 29분
        clock.advanceMin(1)
        assertTrue(WarnCode.UNPLUG_SUGGESTED in p.evaluate(85, true, 30.0).emit)     // 30분
        // 다른 경고는 독립
        assertTrue(WarnCode.LOW_BATTERY in p.evaluate(15, false, 30.0).emit)
    }
}

/** 완료 기준 15: ledger seq 빈틈 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LedgerGapTest {
    private val dbs = ArrayList<com.pixelrelay.core.db.AppDb>()
    @After fun tearDown() { dbs.forEach { runCatching { it.close() } } }

    @Test
    fun `seq 가 건너뛰면 표시했다가 다음 LAN 연결 때 ledger 로 전체를 맞춘다`() = runBlocking {
        val recv = RecvEnv()
        try {
            val e = SendEnv(TestDb.memory().also { dbs += it })
            // 픽셀이 5개를 받아 공개(각각 RECEIVING, PUBLISHED = seq 2개씩)
            val shas = (1..5).map { i ->
                val bytes = randomBytes(500, 100L + i)
                val sha = Hashing.sha256Hex(bytes)
                e.source.add("p$i.jpg", bytes)
                sha to bytes
            }
            e.registerNow()
            for ((sha, bytes) in shas) { recv.receiveWhole(bytes, "x.jpg"); assertNotNull(e.ledger.get(sha)) }
            assertEquals(10L, recv.ledger.maxSeq())

            // 드라이브 status.json 경유로 일부만(빈틈 있게) 도착: seq 2 와 8 만 받음
            val changes = recv.ledger.changesSince(0, 100).filter { it.seq == 2L || it.seq == 8L }
                .map { LedgerItem(it.sha, it.state, it.at, it.seq) }
            val gap = e.sync.applyChanges(changes)
            assertTrue(gap)
            assertTrue(e.sync.needsFull())
            assertEquals(0L, e.sync.lastSeq())                       // 첫 항목(2)부터 빈틈이라 진전 없음
            // 일부는 이미 반영됨, 나머지는 아직
            val before = shas.count { (s, _) -> e.ledger.get(s)!!.stateEnum == SendState.RECEIVED }
            assertTrue(before in 1..4)

            // 다음 LAN 연결: /ledger?since= 로 전부 맞춤
            val api = CoreBackedPixelApi(recv)
            e.sync.syncViaLan(api)
            assertTrue(api.ledgerCalls >= 1)
            assertEquals(10L, e.sync.lastSeq())
            assertFalse(e.sync.needsFull())
            for ((sha, _) in shas) assertEquals(SendState.RECEIVED, e.ledger.get(sha)!!.stateEnum)
        } finally { recv.close() }
    }

    @Test
    fun `빈틈 없이 이어지면 표시하지 않고, 포토 확인·원인 불명도 반영한다`() = runBlocking {
        val recv = RecvEnv()
        try {
            val e = SendEnv(TestDb.memory().also { dbs += it })
            val b1 = randomBytes(500, 120); val b2 = randomBytes(500, 121)
            e.source.add("a.jpg", b1); e.source.add("b.jpg", b2)
            e.registerNow()
            val s1 = recv.receiveWhole(b1, "a.jpg"); val s2 = recv.receiveWhole(b2, "b.jpg")
            recv.ledger.transition(s1, RecvState.PHOTOS_CONFIRMED)
            recv.ledger.transition(s2, RecvState.VANISHED_UNKNOWN)
            val items = recv.ledger.changesSince(0, 100).map { LedgerItem(it.sha, it.state, it.at, it.seq) }
            assertFalse(e.sync.applyChanges(items))
            assertFalse(e.sync.needsFull())
            assertEquals(recv.ledger.maxSeq(), e.sync.lastSeq())
            assertEquals(SendState.PHOTOS_CONFIRMED, e.ledger.get(s1)!!.stateEnum)
            assertEquals(SendState.VANISHED_UNKNOWN, e.ledger.get(s2)!!.stateEnum)
        } finally { recv.close() }
    }
}
