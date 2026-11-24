package com.pixelrelay

import com.pixelrelay.core.GB
import com.pixelrelay.core.Hashing
import com.pixelrelay.core.Hmac
import com.pixelrelay.core.InMemoryPairedKeys
import com.pixelrelay.core.PairingManager
import com.pixelrelay.core.PixelStatus
import com.pixelrelay.core.ReceiverConfig
import com.pixelrelay.core.RecvState
import com.pixelrelay.core.RemoteCommand
import com.pixelrelay.core.RequestVerifier
import com.pixelrelay.core.SendState
import com.pixelrelay.core.StatusCounts
import com.pixelrelay.receiver.CommandHandler
import com.pixelrelay.receiver.LanServer
import com.pixelrelay.receiver.ReceiverConfigStore
import com.pixelrelay.sender.ApiBegin
import com.pixelrelay.sender.HttpPixelApi
import com.pixelrelay.sender.LanSender
import com.pixelrelay.sender.PixelHttpException
import com.pixelrelay.sender.PixelPairing
import com.pixelrelay.sender.SendResult
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 완료 기준 16: LAN 서버 통합 (JVM 로컬 소켓 + 파일 시스템 MediaSink).
 * 짝짓기 -> begin -> 조각 2개 -> commit -> /ledger 에 PUBLISHED. 그리고 서명·409·400·423·507·503 을 HTTP 로 확인.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LanServerIntegrationTest {
    private lateinit var recv: RecvEnv
    private lateinit var server: LanServer
    private lateinit var keys: InMemoryPairedKeys
    private lateinit var pairing: PairingManager
    private val client = OkHttpClient()
    private var port = 0
    private var sendEnv: SendEnv? = null

    @Before
    fun setUp() {
        recv = RecvEnv()
        keys = InMemoryPairedKeys()
        pairing = PairingManager(recv.clock, keys)
        val verifier = RequestVerifier(recv.clock, { keys.all() })
        val handler = CommandHandler(recv.ledger, ReceiverConfigStore(recv.meta), recv.confirmer, recv.clock)
        server = LanServer(0, recv.core, recv.ledger, verifier, pairing, {
            PixelStatus(recv.free, 77, true, 31.5, StatusCounts(0, 0, 0, 0), 0, ReceiverConfig(), emptyList(), recv.ledger.maxSeq(), "test", recv.clock.nowMs())
        }, handler)
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        port = server.listeningPort
    }

    @After
    fun tearDown() {
        server.stop(); recv.close(); sendEnv?.db?.close()
    }

    private suspend fun pairKey(): ByteArray {
        val code = pairing.newCode()
        return java.util.Base64.getDecoder().decode(PixelPairing.pair(client, "127.0.0.1", port, code, "시험폰"))
    }

    /** signedBody: 서명에 쓰는 본문(기본 = 실제 본문). 다르게 주면 본문 바꿔치기를 흉내낸다. PUT 은 "-" 로 서명. */
    private fun raw(key: ByteArray, method: String, pathQuery: String, body: ByteArray? = null, time: Long = recv.clock.nowMs(), sigKey: ByteArray = key, signedBody: ByteArray? = body): Pair<Int, String> {
        val bodySha = if (method == "PUT") "-" else Hashing.sha256Hex(signedBody ?: ByteArray(0))
        val rb = body?.toRequestBody("application/octet-stream".toMediaType())
            ?: if (method == "POST" || method == "PUT") ByteArray(0).toRequestBody(null) else null
        val req = Request.Builder().url("http://127.0.0.1:$port$pathQuery")
            .header("X-PR-Time", time.toString())
            .header("X-PR-Sig", Hmac.sign(sigKey, method, pathQuery, time, bodySha))
            .method(method, rb).build()
        client.newCall(req).execute().use { return it.code to (it.body?.string().orEmpty()) }
    }

    @Test
    fun `짝짓기 - begin - 조각 2개 - commit - ledger 에 PUBLISHED`() = runBlocking {
        val key = pairKey()
        val api = HttpPixelApi("127.0.0.1", port, key, recv.clock, client)
        assertEquals(77, api.status().batteryPct)

        val env = SendEnv(TestDb.memory(), recv.clock, chunk = 4096).also { sendEnv = it }
        val bytes = randomBytes(6000, 200)       // 4096 + 1904 = 조각 2개
        val sha = Hashing.sha256Hex(bytes)
        env.source.add("IMG_0001.jpg", bytes)
        env.registerNow()
        val entry = env.ledger.get(sha)!!

        val sender = LanSender(env.source, env.ledger, env.clock, chunk = 4096)
        val r = sender.send(entry, api, deadlineMs = Long.MAX_VALUE)
        assertEquals(SendResult.Done(6000), r)

        val page = api.ledger(0, 500)
        val published = page.items.filter { it.sha == sha && it.state == "PUBLISHED" }
        assertEquals(1, published.size)
        assertFalse(page.hasMore)
        val re = recv.ledger.get(sha)!!
        assertEquals(RecvState.PUBLISHED, re.stateEnum)
        assertEquals(sha, Hashing.sha256Hex(File(re.path!!).readBytes()))
        assertTrue(Math.abs(File(re.path!!).lastModified() - 1_600_000_000_001L) <= 2000)   // takenAt 이 파일 시각으로
        // 한 번 더 보내도 already
        assertEquals(ApiBegin.Already, api.begin(sha, "IMG_0001.jpg", 6000, 0, "image/jpeg"))
    }

    @Test
    fun `짝짓기 실패와 서명 거절(401)`() = runBlocking {
        // 코드가 없을 때
        try { PixelPairing.pair(client, "127.0.0.1", port, "123456", "x"); fail() } catch (e: PixelHttpException) { assertEquals(401, e.code) }
        val key = pairKey()
        val other = randomBytes(32, 1)
        assertEquals(401, raw(key, "GET", "/status", sigKey = other).first)
        assertEquals(401, raw(key, "GET", "/status", time = recv.clock.nowMs() - 6 * 60_000L).first)
        assertEquals(200, raw(key, "GET", "/status").first)
        // 서명한 경로와 다른 경로로 보내면 거절(서명 재사용 방지)
        val t = recv.clock.nowMs()
        val req = Request.Builder().url("http://127.0.0.1:$port/ledger").header("X-PR-Time", t.toString())
            .header("X-PR-Sig", Hmac.sign(key, "GET", "/status", t, Hashing.sha256Hex(ByteArray(0)))).get().build()
        client.newCall(req).execute().use { assertEquals(401, it.code) }
        // 서명 없음
        client.newCall(Request.Builder().url("http://127.0.0.1:$port/status").get().build()).execute().use { assertEquals(401, it.code) }
    }

    @Test
    fun `5번 틀리면 짝짓기가 429 로 잠긴다`() = runBlocking {
        pairing.newCode()
        repeat(4) { try { PixelPairing.pair(client, "127.0.0.1", port, "000000", "x") } catch (e: PixelHttpException) { assertEquals(401, e.code) } }
        try { PixelPairing.pair(client, "127.0.0.1", port, "000000", "x"); fail() } catch (e: PixelHttpException) { assertEquals(429, e.code) }
    }

    @Test
    fun `잘못된 offset 은 409, 해시 불일치 commit 은 400 이고 부분 파일이 없다`() = runBlocking {
        val key = pairKey()
        val real = randomBytes(5000, 201)
        val sha = Hashing.sha256Hex(real)
        val begin = """{"name":"a.jpg","size":5000,"takenAt":1,"mime":"image/jpeg"}""".toByteArray()
        assertEquals(200, raw(key, "POST", "/files/$sha/begin", begin).first)
        assertEquals(200, raw(key, "PUT", "/files/$sha?offset=0", randomBytes(2000, 9)).first)
        val (c, body) = raw(key, "PUT", "/files/$sha?offset=0", randomBytes(100, 9))
        assertEquals(409, c); assertEquals(2000, JSONObject(body).getLong("offset"))
        assertEquals(409, raw(key, "PUT", "/files/$sha?offset=3000", randomBytes(100, 9)).first)
        // 모자란 상태로 commit -> 409(incomplete)
        assertEquals(409, raw(key, "POST", "/files/$sha/commit").first)
        // 나머지를 엉뚱한 내용으로 채우고 commit -> 400
        assertEquals(200, raw(key, "PUT", "/files/$sha?offset=2000", randomBytes(3000, 10)).first)
        assertEquals(400, raw(key, "POST", "/files/$sha/commit").first)
        assertFalse(File(recv.dir, ".partial/$sha.part").exists())
        assertEquals(0, recv.dir.listFiles()!!.count { it.isFile })
        // 부분 파일이 없으니 다시 begin 하면 처음부터(new)
        val (c2, b2) = raw(key, "POST", "/files/$sha/begin", begin)
        assertEquals(200, c2); assertEquals("new", JSONObject(b2).getString("state"))
    }

    @Test
    fun `공간 부족은 507, 고온 중지는 503, 이미 받는 중이면 423`() = runBlocking {
        val key = pairKey()
        val sha = "c".repeat(64)
        val big = """{"name":"v.mp4","size":${10 * GB},"takenAt":1,"mime":"video/mp4"}""".toByteArray()
        recv.free = 10 * GB + 4 * GB - 1
        val (c, b) = raw(key, "POST", "/files/$sha/begin", big)
        assertEquals(507, c); assertFalse(JSONObject(b).getBoolean("permanent"))
        recv.free = 10 * GB + 4 * GB
        assertEquals(200, raw(key, "POST", "/files/$sha/begin", big).first)

        recv.hot = true
        assertEquals(503, raw(key, "POST", "/files/${"d".repeat(64)}/begin", big).first)
        assertEquals(503, raw(key, "PUT", "/files/$sha?offset=0", randomBytes(10, 1)).first)
        recv.hot = false

        // 같은 sha 를 드라이브 경로가 받는 중 -> LAN 은 423
        val other = "e".repeat(64)
        val small = """{"name":"s.jpg","size":100,"takenAt":1,"mime":"image/jpeg"}""".toByteArray()
        recv.free = 100 * GB   // 앞의 10GB 수신이 예약으로 잡혀 있으므로 공간을 넉넉히
        recv.core.begin(other, "s.jpg", 100, 1, "image/jpeg", "drive")
        assertEquals(423, raw(key, "POST", "/files/$other/begin", small).first)
    }

    @Test
    fun `원격 명령 setConfig 는 되고 cleanupOpened 는 원격 불가(400)`() = runBlocking {
        val key = pairKey()
        val cfg = ReceiverConfig(com.pixelrelay.core.QueueMode.CAPACITY, 5 * GB, 2 * GB)
        val set = RemoteCommand(RemoteCommand.SET_CONFIG, config = cfg).toJson().toString().toByteArray()
        assertEquals(200, raw(key, "POST", "/command", set).first)
        assertEquals(cfg, ReceiverConfigStore(recv.meta).get())
        val clean = RemoteCommand(RemoteCommand.CLEANUP_OPENED).toJson().toString().toByteArray()
        assertEquals(400, raw(key, "POST", "/command", clean).first)
    }

    @Test
    fun `ledger 는 since 와 limit 로 페이지를 나눈다`() = runBlocking {
        val key = pairKey()
        repeat(3) { recv.receiveWhole(randomBytes(300, 300L + it), "f$it.jpg") }     // 변화 6개
        val api = HttpPixelApi("127.0.0.1", port, key, recv.clock, client)
        val p1 = api.ledger(0, 4)
        assertEquals(4, p1.items.size); assertTrue(p1.hasMore); assertEquals(4L, p1.nextSeq)
        val p2 = api.ledger(p1.nextSeq, 4)
        assertEquals(2, p2.items.size); assertFalse(p2.hasMore)
        assertEquals(listOf(5L, 6L), p2.items.map { it.seq })
    }

    @Test
    fun `confirmVanished 는 commandId 필수, 본문 바꿔치기는 401, 같은 commandId 재사용은 거절`() = runBlocking {
        val key = pairKey()
        val b1 = randomBytes(400, 410); val b2 = randomBytes(400, 411)
        val s1 = recv.receiveWhole(b1, "a.jpg"); val s2 = recv.receiveWhole(b2, "b.jpg")
        recv.ledger.transition(s1, RecvState.VANISHED_UNKNOWN); recv.ledger.transition(s2, RecvState.VANISHED_UNKNOWN)
        fun cmd(sha: String, id: String?) = RemoteCommand(RemoteCommand.CONFIRM_VANISHED, sha = sha, id = id).toJson().toString().toByteArray()

        // commandId 없음 -> 거절
        assertEquals(400, raw(key, "POST", "/command", cmd(s1, null)).first)
        assertEquals(RecvState.VANISHED_UNKNOWN, recv.ledger.get(s1)!!.stateEnum)
        // 서명한 본문(무해한 sendNow)과 실제 보낸 본문(confirmVanished)이 다르면 401
        val harmless = RemoteCommand(RemoteCommand.SEND_NOW).toJson().toString().toByteArray()
        assertEquals(401, raw(key, "POST", "/command", cmd(s1, "id-1"), signedBody = harmless).first)
        assertEquals(RecvState.VANISHED_UNKNOWN, recv.ledger.get(s1)!!.stateEnum)
        // 정상 1회
        assertEquals(200, raw(key, "POST", "/command", cmd(s1, "id-1")).first)
        assertEquals(RecvState.PHOTOS_CONFIRMED, recv.ledger.get(s1)!!.stateEnum)
        // 같은 commandId 로 다른 sha -> 재사용 거절(서명을 그대로 재생해도 마찬가지)
        assertEquals(400, raw(key, "POST", "/command", cmd(s2, "id-1")).first)
        assertEquals(RecvState.VANISHED_UNKNOWN, recv.ledger.get(s2)!!.stateEnum)
        assertEquals(200, raw(key, "POST", "/command", cmd(s2, "id-2")).first)
    }

    @Test
    fun `형식이 잘못된 sha 는 begin 이 400(Invalid)`() = runBlocking {
        val key = pairKey()
        val body = """{"name":"a.jpg","size":10,"takenAt":1,"mime":"image/jpeg"}""".toByteArray()
        // 서버는 경로의 sha 형식을 검사한다(소문자 64자 hex 가 아니면 not found)
        assertEquals(404, raw(key, "POST", "/files/${"A".repeat(64)}/begin", body).first)
        val neg = """{"name":"a.jpg","size":-5,"takenAt":1,"mime":"image/jpeg"}""".toByteArray()
        assertEquals(400, raw(key, "POST", "/files/${"a".repeat(64)}/begin", neg).first)
    }
}
