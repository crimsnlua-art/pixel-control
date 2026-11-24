package com.pixelrelay

import com.pixelrelay.core.RecvState
import com.pixelrelay.receiver.CleanResult
import com.pixelrelay.receiver.PhotosConfirmer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 완료 기준 10: 포토 확인 (신호 A) */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosConfirmTest {
    private lateinit var env: RecvEnv

    @Before fun setUp() { env = RecvEnv() }
    @After fun tearDown() { assertTrue(env.drive.illegalDeletes().isEmpty()); env.close() }

    /** 파일 + 드라이브 사본 1개가 있는 PUBLISHED 항목 */
    private suspend fun published(seed: Long): String {
        val b = randomBytes(700, seed)
        val sha = env.receiveWhole(b, "p$seed.jpg")
        val f = env.drive.put(sha, "p$seed.jpg", b)
        env.ledger.attachCopy(sha, f.id, b.size.toLong())
        return sha
    }

    private suspend fun vanish(sha: String) {
        val e = env.ledger.get(sha)!!
        File(e.path!!).delete(); env.index.removePath(e.path!!)
    }

    @Test
    fun `파일·MediaStore 둘 다 없고 1분 뒤에도 없으며 버튼 후 1시간 안이면 포토 확인 + 드라이브 삭제`() = runBlocking {
        val sha = published(1)
        env.confirmer.recordButton()
        env.clock.advanceMin(20)
        vanish(sha)
        env.confirmer.check()
        // 처음 없음을 본 직후에는 아직 판정하지 않는다(1분 뒤 재확인)
        assertEquals(RecvState.PUBLISHED, env.ledger.get(sha)!!.stateEnum)
        assertEquals(0, env.drive.deletes.size)
        env.clock.advance(30_000)
        env.confirmer.check()
        assertEquals(RecvState.PUBLISHED, env.ledger.get(sha)!!.stateEnum)
        env.clock.advance(31_000)
        val r = env.confirmer.check()
        assertEquals(1, r.confirmed)
        assertEquals(RecvState.CLEANED, env.ledger.get(sha)!!.stateEnum)
        assertEquals(1, env.drive.deletes.size)
        assertTrue(env.drive.files.isEmpty())
    }

    @Test
    fun `버튼 기록이 없으면 원인 불명이고 드라이브 삭제는 0회`() = runBlocking {
        val sha = published(2)
        vanish(sha)
        env.confirmer.check()
        env.clock.advance(61_000)
        val r = env.confirmer.check()
        assertEquals(1, r.vanishedUnknown)
        assertEquals(RecvState.VANISHED_UNKNOWN, env.ledger.get(sha)!!.stateEnum)
        assertEquals(0, env.drive.deletes.size)
        assertEquals(1, env.ledger.copies(sha).size)
        assertEquals(1, env.drive.files.size)
    }

    @Test
    fun `버튼 후 1시간이 지나서 사라지면 원인 불명`() = runBlocking {
        val sha = published(3)
        env.confirmer.recordButton()
        env.clock.advanceMin(61)
        vanish(sha)
        env.confirmer.check(); env.clock.advance(61_000); env.confirmer.check()
        assertEquals(RecvState.VANISHED_UNKNOWN, env.ledger.get(sha)!!.stateEnum)
        assertEquals(0, env.drive.deletes.size)
    }

    @Test
    fun `버튼을 누르기 전에 사라진 것은 원인 불명`() = runBlocking {
        val sha = published(4)
        vanish(sha)
        env.confirmer.check()                       // 사라짐을 먼저 목격
        env.clock.advanceMin(5)
        env.confirmer.recordButton()                // 그 뒤에 버튼
        env.clock.advance(61_000)
        env.confirmer.check()
        assertEquals(RecvState.VANISHED_UNKNOWN, env.ledger.get(sha)!!.stateEnum)
        assertEquals(0, env.drive.deletes.size)
    }

    @Test
    fun `MediaStore id 만 바뀌고 파일이 있으면 사라짐이 아니고 새 id 로 갱신`() = runBlocking {
        val sha = published(5)
        val e = env.ledger.get(sha)!!
        val newId = env.index.changeId(e.path!!)
        assertTrue(newId != e.mediaId)
        env.confirmer.recordButton()
        env.confirmer.check(); env.clock.advance(61_000); env.confirmer.check()
        val after = env.ledger.get(sha)!!
        assertEquals(RecvState.PUBLISHED, after.stateEnum)
        assertEquals(newId, after.mediaId)
        assertNull(after.missingSince)
        assertEquals(0, env.drive.deletes.size)
    }

    @Test
    fun `파일은 있는데 MediaStore 에만 없으면 사라짐이 아니다`() = runBlocking {
        val sha = published(6)
        val e = env.ledger.get(sha)!!
        env.index.removePath(e.path!!)
        env.confirmer.recordButton()
        env.confirmer.check(); env.clock.advance(61_000); env.confirmer.check()
        assertEquals(RecvState.PUBLISHED, env.ledger.get(sha)!!.stateEnum)
    }

    @Test
    fun `1분 안에 다시 나타나면 사라짐이 취소된다`() = runBlocking {
        val sha = published(7)
        val e = env.ledger.get(sha)!!
        val bytes = File(e.path!!).readBytes()
        File(e.path!!).delete(); env.index.removePath(e.path!!)
        env.confirmer.check()
        assertNotNull(env.ledger.get(sha)!!.missingSince)
        File(e.path!!).writeBytes(bytes); env.index.scan(e.path!!, "image/jpeg")
        env.clock.advance(61_000)
        env.confirmer.check()
        assertEquals(RecvState.PUBLISHED, env.ledger.get(sha)!!.stateEnum)
        assertNull(env.ledger.get(sha)!!.missingSince)
    }

    @Test
    fun `우리 앱이 지운 파일은 확인 대상이 아니다`() = runBlocking {
        val sha = published(8)
        env.confirmer.recordButton()
        env.confirmer.markOwnDeleted(sha)
        vanish(sha)
        env.confirmer.check(); env.clock.advance(61_000)
        val r = env.confirmer.check()
        assertEquals(0, r.confirmed + r.vanishedUnknown)
        assertEquals(RecvState.PUBLISHED, env.ledger.get(sha)!!.stateEnum)
        assertEquals(0, env.drive.deletes.size)
    }

    @Test
    fun `원인 불명 항목은 사용자가 확정하면 포토 확인 + 드라이브 삭제`() = runBlocking {
        val sha = published(9)
        vanish(sha)
        env.confirmer.check(); env.clock.advance(61_000); env.confirmer.check()
        assertEquals(RecvState.VANISHED_UNKNOWN, env.ledger.get(sha)!!.stateEnum)
        assertTrue(env.confirmer.userConfirm(sha))
        assertEquals(RecvState.CLEANED, env.ledger.get(sha)!!.stateEnum)
        assertEquals(1, env.drive.deletes.size)
        assertFalse(env.confirmer.userConfirm(sha))      // 두 번째는 대상 아님
    }
}

/** 완료 기준 11: 드라이브 delete 는 PHOTOS_CONFIRMED 항목의 사본에만 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeleteInvariantTest {
    private lateinit var env: RecvEnv
    @Before fun setUp() { env = RecvEnv() }
    @After fun tearDown() { env.close() }

    @Test
    fun `포토 확인 전 상태의 항목은 Cleaner 가 삭제하지 않는다`() = runBlocking {
        val b = randomBytes(500, 60)
        val sha = env.receiveWhole(b, "a.jpg")
        val f = env.drive.put(sha, "a.jpg", b)
        env.ledger.attachCopy(sha, f.id, 500)
        assertEquals(CleanResult.NOT_CONFIRMED, env.cleaner.clean(sha))           // PUBLISHED
        env.ledger.transition(sha, RecvState.VANISHED_UNKNOWN)
        assertEquals(CleanResult.NOT_CONFIRMED, env.cleaner.clean(sha))           // VANISHED_UNKNOWN
        assertEquals(CleanResult.NOT_FOUND, env.cleaner.clean("f".repeat(64)))
        env.cleaner.cleanAllConfirmed()
        assertEquals(0, env.drive.deletes.size)
        assertEquals(1, env.drive.files.size)
    }

    @Test
    fun `확정된 항목만 지우고 같은 sha 가 아닌 다른 사본은 건드리지 않는다`() = runBlocking {
        val b1 = randomBytes(500, 61); val b2 = randomBytes(500, 62)
        val s1 = env.receiveWhole(b1, "a.jpg"); val s2 = env.receiveWhole(b2, "b.jpg")
        val f1 = env.drive.put(s1, "a.jpg", b1); val f2 = env.drive.put(s2, "b.jpg", b2)
        env.ledger.attachCopy(s1, f1.id, 500); env.ledger.attachCopy(s2, f2.id, 500)
        env.ledger.transition(s1, RecvState.PHOTOS_CONFIRMED)
        assertEquals(CleanResult.CLEANED, env.cleaner.clean(s1))
        assertEquals(1, env.drive.deletes.size)
        assertEquals(f1.id, env.drive.deletes[0].fileId)
        assertTrue(env.drive.files.containsKey(f2.id))
        assertTrue(env.drive.illegalDeletes().isEmpty())
    }

    @Test
    fun `일부 삭제가 실패하면 CLEANED 로 가지 않고 다음에 다시 시도한다`() = runBlocking {
        val b = randomBytes(500, 63)
        val sha = env.receiveWhole(b, "a.jpg")
        val f1 = env.drive.put(sha, "a.jpg", b); val f2 = env.drive.put(sha, "a.jpg", b)
        env.ledger.attachCopy(sha, f1.id, 500); env.ledger.attachCopy(sha, f2.id, 500)
        env.ledger.transition(sha, RecvState.PHOTOS_CONFIRMED)
        val flaky = object : com.pixelrelay.drive.DriveApi by env.drive {
            var first = true
            override suspend fun deleteFile(fileId: String) {
                if (first) { first = false; throw java.io.IOException("끊김") }
                env.drive.deleteFile(fileId)
            }
        }
        val c = com.pixelrelay.receiver.Cleaner(env.ledger, flaky)
        assertEquals(CleanResult.PARTIAL, c.clean(sha))
        assertEquals(RecvState.PHOTOS_CONFIRMED, env.ledger.get(sha)!!.stateEnum)
        assertEquals(CleanResult.CLEANED, c.clean(sha))
        assertEquals(RecvState.CLEANED, env.ledger.get(sha)!!.stateEnum)
        assertTrue(env.drive.illegalDeletes().isEmpty())
    }

    /** 소스 검사: 큐 파일 삭제 호출은 Cleaner.kt 하나에만 있어야 한다. */
    @Test
    fun `main 소스에서 deleteFile 호출은 Cleaner 한 곳뿐`() {
        val root = File("src/main/java")
        assertTrue("소스 폴더를 못 찾음: ${root.absolutePath}", root.isDirectory)
        val callers = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f ->
                f.readLines().any { line ->
                    val t = line.trim()
                    !t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/*") &&
                        Regex("""\.deleteFile\(""").containsMatchIn(t)
                }
            }.map { it.name }.toSet()
        assertEquals(setOf("Cleaner.kt"), callers)
        // 정의(구현)는 DriveApi/RealDriveApi 에만
        val defs = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readLines().any { Regex("""fun deleteFile\(""").containsMatchIn(it) } }.map { it.name }.toSet()
        assertEquals(setOf("DriveApi.kt", "RealDriveApi.kt"), defs)
    }
}
