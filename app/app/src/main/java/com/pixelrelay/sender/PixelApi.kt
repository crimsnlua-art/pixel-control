package com.pixelrelay.sender

import com.pixelrelay.core.Clock
import com.pixelrelay.core.Hashing
import com.pixelrelay.core.Hmac
import com.pixelrelay.core.LedgerPage
import com.pixelrelay.core.PixelStatus
import com.pixelrelay.core.Proto
import com.pixelrelay.core.RemoteCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class ApiBegin {
    data class Ok(val resume: Boolean, val offset: Long) : ApiBegin()
    object Already : ApiBegin()
    object Busy : ApiBegin()
    data class NoSpace(val permanent: Boolean) : ApiBegin()
    object Hot : ApiBegin()
}

sealed class ApiPut {
    data class Ok(val offset: Long) : ApiPut()
    data class Conflict(val offset: Long) : ApiPut()
    object Hot : ApiPut()
    object NotBegun : ApiPut()
}

sealed class ApiCommit {
    object Published : ApiCommit()
    object Mismatch : ApiCommit()
    data class Incomplete(val offset: Long) : ApiCommit()
    object NotBegun : ApiCommit()
}

class PixelHttpException(val code: Int, msg: String) : IOException("픽셀 응답 $code: $msg")

/** 픽셀 LAN 서버 호출 경계(설계서 5절). 네트워크 오류는 IOException. */
interface PixelApi {
    suspend fun status(): PixelStatus
    suspend fun begin(sha: String, name: String, size: Long, takenAt: Long, mime: String): ApiBegin
    suspend fun put(sha: String, offset: Long, data: ByteArray, len: Int): ApiPut
    suspend fun commit(sha: String): ApiCommit
    suspend fun ledger(since: Long, limit: Int): LedgerPage
    suspend fun command(cmd: RemoteCommand): Boolean
}

/** 짝짓기 호출(서명 전). 성공하면 base64 키. */
object PixelPairing {
    suspend fun pair(client: OkHttpClient, host: String, port: Int, code: String, deviceName: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject().put("code", code).put("deviceName", deviceName).toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("http://$host:$port/pair").post(body).build()
        client.newCall(req).execute().use {
            val s = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw PixelHttpException(it.code, s)
            JSONObject(s).getString("key")
        }
    }
}

class HttpPixelApi(
    private val host: String,
    private val port: Int,
    private val key: ByteArray,
    private val clock: Clock,
    private val client: OkHttpClient = defaultClient(),
) : PixelApi {
    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val OCTET = "application/octet-stream".toMediaType()
    }

    /** body: 본문 바이트(없으면 빈 배열). PUT 조각은 bodySha 자리에 "-" (무결성은 commit 의 전체 해시). */
    private fun builder(method: String, pathQuery: String, body: ByteArray? = ByteArray(0)): Request.Builder {
        val t = clock.nowMs()
        val bodySha = if (body == null) "-" else Hashing.sha256Hex(body)
        return Request.Builder().url("http://$host:$port$pathQuery")
            .header(Proto.HDR_TIME, t.toString())
            .header(Proto.HDR_SIG, Hmac.sign(key, method, pathQuery, t, bodySha))
    }

    private suspend fun call(req: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
        client.newCall(req).execute().use { it.code to it.body?.string().orEmpty() }
    }

    override suspend fun status(): PixelStatus {
        val (c, s) = call(builder("GET", "/status").get().build())
        if (c != 200) throw PixelHttpException(c, s)
        return PixelStatus.fromJson(JSONObject(s))
    }

    override suspend fun begin(sha: String, name: String, size: Long, takenAt: Long, mime: String): ApiBegin {
        val body = JSONObject().put("name", name).put("size", size).put("takenAt", takenAt).put("mime", mime).toString().toByteArray()
        val (c, s) = call(builder("POST", "/files/$sha/begin", body).post(body.toRequestBody(JSON)).build())
        val o = runCatching { JSONObject(s) }.getOrDefault(JSONObject())
        return when (c) {
            200 -> if (o.optString("state") == "already") ApiBegin.Already
            else ApiBegin.Ok(o.optString("state") == "resume", o.optLong("offset"))
            Proto.HTTP_BUSY -> ApiBegin.Busy
            Proto.HTTP_NO_SPACE -> ApiBegin.NoSpace(o.optBoolean("permanent"))
            503 -> ApiBegin.Hot
            else -> throw PixelHttpException(c, s)
        }
    }

    override suspend fun put(sha: String, offset: Long, data: ByteArray, len: Int): ApiPut {
        val pq = "/files/$sha?offset=$offset"
        val (c, s) = call(builder("PUT", pq, null).put(data.toRequestBody(OCTET, 0, len)).build())
        val o = runCatching { JSONObject(s) }.getOrDefault(JSONObject())
        return when (c) {
            200 -> ApiPut.Ok(o.optLong("offset"))
            409 -> ApiPut.Conflict(o.optLong("offset"))
            503 -> ApiPut.Hot
            404 -> ApiPut.NotBegun
            else -> throw PixelHttpException(c, s)
        }
    }

    override suspend fun commit(sha: String): ApiCommit {
        val (c, s) = call(builder("POST", "/files/$sha/commit").post(ByteArray(0).toRequestBody(JSON)).build())
        val o = runCatching { JSONObject(s) }.getOrDefault(JSONObject())
        return when (c) {
            200 -> ApiCommit.Published
            400 -> ApiCommit.Mismatch
            409 -> ApiCommit.Incomplete(o.optLong("offset"))
            404 -> ApiCommit.NotBegun
            else -> throw PixelHttpException(c, s)
        }
    }

    override suspend fun ledger(since: Long, limit: Int): LedgerPage {
        val (c, s) = call(builder("GET", "/ledger?since=$since&limit=$limit").get().build())
        if (c != 200) throw PixelHttpException(c, s)
        return LedgerPage.fromJson(JSONObject(s))
    }

    override suspend fun command(cmd: RemoteCommand): Boolean {
        val body = cmd.toJson().toString().toByteArray()
        val (c, _) = call(builder("POST", "/command", body).post(body.toRequestBody(JSON)).build())
        return c == 200
    }
}
