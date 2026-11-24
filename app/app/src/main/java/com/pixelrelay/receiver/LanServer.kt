package com.pixelrelay.receiver

import com.pixelrelay.core.Hashing
import com.pixelrelay.core.LedgerPage
import com.pixelrelay.core.LedgerItem
import com.pixelrelay.core.PairResult
import com.pixelrelay.core.PairingManager
import com.pixelrelay.core.PixelStatus
import com.pixelrelay.core.Proto
import com.pixelrelay.core.ReceiverLedger
import com.pixelrelay.core.RemoteCommand
import com.pixelrelay.core.RequestVerifier
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

private class Code(private val c: Int, private val d: String) : NanoHTTPD.Response.IStatus {
    override fun getRequestStatus(): Int = c
    override fun getDescription(): String = "$c $d"
}

/** 픽셀의 LAN 서버(설계서 5절, 포트 47321, HTTP). 요청 서명은 [RequestVerifier] 로 검증한다. */
class LanServer(
    port: Int,
    private val core: ReceiverCore,
    private val ledger: ReceiverLedger,
    private val verifier: RequestVerifier,
    private val pairing: PairingManager,
    private val status: suspend () -> PixelStatus,
    private val commands: CommandHandler,
) : NanoHTTPD(port) {

    companion object {
        const val OWNER = "lan"
        private val OK = Code(200, "OK")
        private val BAD = Code(400, "Bad Request")
        private val UNAUTH = Code(401, "Unauthorized")
        private val NOT_FOUND = Code(404, "Not Found")
        private val CONFLICT = Code(409, "Conflict")
        private val LOCKED = Code(Proto.HTTP_BUSY, "Locked")
        private val TOO_MANY = Code(429, "Too Many Requests")
        private val NO_SPACE = Code(Proto.HTTP_NO_SPACE, "Insufficient Storage")
        private val HOT = Code(503, "Service Unavailable")
        private val SHA = Regex("^[0-9a-f]{64}$")
    }

    private fun json(st: NanoHTTPD.Response.IStatus, o: JSONObject): Response =
        newFixedLengthResponse(st, "application/json; charset=utf-8", o.toString()).also { it.closeConnection(true) }

    private fun readBody(session: IHTTPSession): String = String(readBodyBytes(session) ?: ByteArray(0), Charsets.UTF_8)

    /** JSON 등 작은 본문(최대 1MB). 너무 크면 null. 본문이 없으면 빈 배열. */
    private fun readBodyBytes(session: IHTTPSession): ByteArray? {
        val len = session.headers["content-length"]?.toLongOrNull() ?: 0L
        if (len <= 0) return ByteArray(0)
        if (len > 1_000_000) return null
        val buf = ByteArray(len.toInt())
        var off = 0
        val ins = session.inputStream
        while (off < buf.size) {
            val n = ins.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return buf.copyOf(off)
    }

    override fun serve(session: IHTTPSession): Response = try {
        route(session)
    } catch (e: Exception) {
        json(Code(500, "Server Error"), JSONObject().put("error", e.message ?: "error"))
    }

    private fun route(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method.name
        val query = session.queryParameterString
        val pathQuery = if (query.isNullOrEmpty()) uri else "$uri?$query"

        if (method == "POST" && uri == "/pair") {
            val o = runCatching { JSONObject(readBody(session)) }.getOrNull() ?: return json(BAD, JSONObject().put("error", "bad json"))
            return when (val r = pairing.redeem(o.optString("code"), o.optString("deviceName", "기기"))) {
                is PairResult.Ok -> json(OK, JSONObject().put("key", r.keyBase64))
                PairResult.Invalid -> json(UNAUTH, JSONObject().put("error", "invalid"))
                is PairResult.Locked -> json(TOO_MANY, JSONObject().put("error", "locked").put("until", r.untilMs))
            }
        }

        val h = session.headers
        // 서명은 본문 SHA-256 까지 덮는다. PUT 조각만 "-" (무결성은 commit 의 전체 해시)
        val bodyBytes: ByteArray? = if (method == "PUT") null else readBodyBytes(session)
        if (method != "PUT" && bodyBytes == null) {
            drain(session)
            return json(BAD, JSONObject().put("error", "body too large"))
        }
        val bodySha = if (bodyBytes == null) "-" else Hashing.sha256Hex(bodyBytes)
        if (!verifier.verify(method, pathQuery, h["x-pr-time"], h["x-pr-sig"], bodySha)) {
            drain(session)
            return json(UNAUTH, JSONObject().put("error", "unauthorized"))
        }
        val body = String(bodyBytes ?: ByteArray(0), Charsets.UTF_8)

        if (method == "GET" && uri == "/status") {
            return json(OK, runBlocking { status() }.toJson())
        }
        if (method == "GET" && uri == "/ledger") {
            val since = session.parameters["since"]?.firstOrNull()?.toLongOrNull() ?: 0L
            val limit = (session.parameters["limit"]?.firstOrNull()?.toIntOrNull() ?: 500).coerceIn(1, 500)
            val page = runBlocking {
                val rows = ledger.changesSince(since, limit + 1)
                val more = rows.size > limit
                val items = rows.take(limit).map { LedgerItem(it.sha, it.state, it.at, it.seq) }
                LedgerPage(items, items.lastOrNull()?.seq ?: since, more)
            }
            return json(OK, page.toJson())
        }
        if (method == "POST" && uri == "/command") {
            val cmd = runCatching { RemoteCommand.fromJson(JSONObject(body)) }.getOrNull()
                ?: return json(BAD, JSONObject().put("error", "bad json"))
            val r = runBlocking { commands.handle(cmd, remote = true).also { commands.recordResult(cmd, it) } }
            return json(if (r.ok) OK else BAD, JSONObject().put("ok", r.ok).put("message", r.message))
        }

        val parts = uri.trim('/').split('/')
        if (parts.size >= 2 && parts[0] == "files" && SHA.matches(parts[1])) {
            val sha = parts[1]
            if (method == "POST" && parts.size == 3 && parts[2] == "begin") return begin(body, sha)
            if (method == "PUT" && parts.size == 2) return put(session, sha)
            if (method == "POST" && parts.size == 3 && parts[2] == "commit") return commit(sha)
        }
        drain(session)
        return json(NOT_FOUND, JSONObject().put("error", "not found"))
    }

    /** 응답을 보내기 전에 안 읽은 요청 본문을 비운다(안 비우면 연결이 리셋돼 클라이언트가 응답을 못 읽을 수 있다). */
    private fun drain(session: IHTTPSession) {
        var left = session.headers["content-length"]?.toLongOrNull() ?: return
        val buf = ByteArray(64 * 1024)
        val ins = session.inputStream
        try {
            while (left > 0) {
                val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                left -= n
            }
        } catch (e: java.io.IOException) { /* 클라이언트가 끊음 */ }
    }

    private fun begin(body: String, sha: String): Response {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return json(BAD, JSONObject().put("error", "bad json"))
        val name = o.optString("name")
        val size = o.optLong("size", -1)
        if (name.isEmpty() || size < 0) return json(BAD, JSONObject().put("error", "name/size"))
        val r = runBlocking { core.begin(sha, name, size, o.optLong("takenAt"), o.optString("mime", "application/octet-stream"), OWNER) }
        return when (r) {
            is BeginResult.Ok -> json(OK, JSONObject().put("state", if (r.resume) "resume" else "new").put("offset", r.offset))
            BeginResult.Already -> json(OK, JSONObject().put("state", "already"))
            BeginResult.Busy -> json(LOCKED, JSONObject().put("error", "busy"))
            is BeginResult.NoSpace -> json(NO_SPACE, JSONObject().put("error", "no_space").put("freeBytes", r.freeBytes).put("permanent", r.permanent))
            BeginResult.Hot -> json(HOT, JSONObject().put("error", "hot"))
            BeginResult.Invalid -> json(BAD, JSONObject().put("error", "invalid"))
            BeginResult.Mismatch -> json(CONFLICT, JSONObject().put("error", "size_mismatch"))
        }
    }

    private fun put(session: IHTTPSession, sha: String): Response {
        val offset = session.parameters["offset"]?.firstOrNull()?.toLongOrNull() ?: return json(BAD, JSONObject().put("error", "offset"))
        val len = session.headers["content-length"]?.toLongOrNull() ?: 0L
        val r = runBlocking { core.put(sha, OWNER, offset, session.inputStream, len) }
        if (r !is PutResult.Ok) drain(session)
        return when (r) {
            is PutResult.Ok -> json(OK, JSONObject().put("offset", r.offset))
            is PutResult.Conflict -> json(CONFLICT, JSONObject().put("offset", r.offset))
            PutResult.NotBegun -> json(NOT_FOUND, JSONObject().put("error", "not begun"))
            PutResult.Hot -> json(HOT, JSONObject().put("error", "hot"))
        }
    }

    private fun commit(sha: String): Response {
        return when (val r = runBlocking { core.commit(sha, OWNER) }) {
            CommitOutcome.Published -> json(OK, JSONObject().put("state", "published"))
            CommitOutcome.Mismatch -> json(BAD, JSONObject().put("error", "hash mismatch"))
            is CommitOutcome.Incomplete -> json(CONFLICT, JSONObject().put("error", "incomplete").put("offset", r.offset))
            CommitOutcome.NotBegun -> json(NOT_FOUND, JSONObject().put("error", "not begun"))
        }
    }
}
