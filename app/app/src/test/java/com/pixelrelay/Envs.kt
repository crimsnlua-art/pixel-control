package com.pixelrelay

import com.pixelrelay.core.GB
import com.pixelrelay.core.Hashing
import com.pixelrelay.core.MetaStore
import com.pixelrelay.core.ReceiverConfig
import com.pixelrelay.core.ReceiverLedger
import com.pixelrelay.core.RecvState
import com.pixelrelay.receiver.BeginResult
import com.pixelrelay.receiver.Cleaner
import com.pixelrelay.receiver.CommitOutcome
import com.pixelrelay.receiver.DriveReceiver
import com.pixelrelay.receiver.FileMediaSink
import com.pixelrelay.receiver.PhotosConfirmer
import com.pixelrelay.receiver.ReceiverCore
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

/** 받기 쪽 시험 환경: 메모리 DB + 임시 폴더 저장소 + 가짜 드라이브. */
class RecvEnv(val clock: FakeClock = FakeClock()) {
    val db = TestDb.memory()
    val ledger = ReceiverLedger(db, clock)
    val meta = MetaStore(db)
    val dir: File = Files.createTempDirectory("pr_recv").toFile()
    var free = 100L * GB
    var total = 200L * GB
    val index = FakeIndex()
    val sink = FileMediaSink(dir, { free }, { total }, index)
    var hot = false
    val core = ReceiverCore(ledger, sink, clock, { hot })
    val drive = FakeDriveApi().also { d ->
        d.confirmed = { sha ->
            val st = ledger.get(sha)?.stateEnum
            st == RecvState.PHOTOS_CONFIRMED || st == RecvState.CLEANED
        }
    }
    val cleaner = Cleaner(ledger, drive)
    val confirmer = PhotosConfirmer(ledger, sink, clock, cleaner)
    var cfg = ReceiverConfig()
    val driveRx = DriveReceiver(drive, core, ledger, cleaner, config = { cfg }, clock = clock)

    /** LAN 처럼 한 파일을 통째로 받아 공개까지 */
    suspend fun receiveWhole(bytes: ByteArray, name: String, owner: String = "lan"): String {
        val sha = Hashing.sha256Hex(bytes)
        val b = core.begin(sha, name, bytes.size.toLong(), 1000, "image/jpeg", owner)
        check(b is BeginResult.Ok) { "begin: $b" }
        val off = b.offset.toInt()
        core.put(sha, owner, b.offset, ByteArrayInputStream(bytes, off, bytes.size - off), (bytes.size - off).toLong())
        check(core.commit(sha, owner) == CommitOutcome.Published)
        return sha
    }

    fun close() { db.close(); dir.deleteRecursively() }
}
