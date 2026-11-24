package com.pixelrelay.drive

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** 접근 토큰 공급자. 401 이면 forceRefresh=true 로 한 번 더 부른다. */
interface TokenProvider {
    suspend fun token(forceRefresh: Boolean): String
}

/** Google Identity AuthorizationClient 로 drive.appdata 범위 토큰을 받는다(클라이언트 ID 문자열 불필요: 패키지+서명으로 매칭). */
class GoogleTokenProvider(private val ctx: Context) : TokenProvider {
    private var last: String? = null

    override suspend fun token(forceRefresh: Boolean): String {
        val client = Identity.getAuthorizationClient(ctx)
        if (forceRefresh) {
            last?.let { runCatching { client.clearToken(ClearTokenRequest.builder().setToken(it).build()).await() } }
            last = null
        }
        val req = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SCOPE)))
            .build()
        val res = try {
            client.authorize(req).await()
        } catch (e: Exception) {
            throw DriveAuthException(null, "구글 인증 실패: ${e.message}")
        }
        if (res.hasResolution()) throw DriveAuthException(res.pendingIntent)
        val t = res.accessToken ?: throw DriveAuthException(null, "접근 토큰 없음")
        last = t
        return t
    }

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    }
}

class RealDriveApi(
    private val tokens: TokenProvider,
    private val http: OkHttpClient = defaultClient(),
) : DriveApi {
    companion object {
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val FIELDS = "id,name,size,sha256Checksum,appProperties"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private var statusFileId: String? = null

    /** 토큰을 붙여 실행. 401 이면 토큰을 새로 받아 1회 재시도(설계서 7절). */
    private suspend fun exec(build: (String) -> Request): Response = withContext(Dispatchers.IO) {
        var res = http.newCall(build(tokens.token(false))).execute()
        if (res.code == 401) {
            res.close()
            res = http.newCall(build(tokens.token(true))).execute()
        }
        res
    }

    private fun Response.bodyOrThrow(): String {
        use {
            val s = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw DriveHttpException(it.code, s)
            return s
        }
    }

    private fun parseFile(o: JSONObject): DriveFile {
        val p = o.optJSONObject("appProperties")
        val props = HashMap<String, String>()
        p?.keys()?.forEach { props[it] = p.getString(it) }
        return DriveFile(o.getString("id"), o.optString("name"), o.optString("size", "0").toLongOrNull() ?: 0L,
            o.optString("sha256Checksum").ifEmpty { null }?.lowercase(), props)
    }

    private suspend fun list(q: String): List<DriveFile> {
        val out = ArrayList<DriveFile>()
        var page: String? = null
        do {
            val url = StringBuilder("$API/files?spaces=appDataFolder&pageSize=1000&fields=nextPageToken,files($FIELDS)")
                .append("&q=").append(java.net.URLEncoder.encode(q, "UTF-8"))
            if (page != null) url.append("&pageToken=").append(page)
            val body = exec { t -> Request.Builder().url(url.toString()).header("Authorization", "Bearer $t").build() }.bodyOrThrow()
            val o = JSONObject(body)
            val a = o.optJSONArray("files") ?: JSONArray()
            for (i in 0 until a.length()) out += parseFile(a.getJSONObject(i))
            page = o.optString("nextPageToken").ifEmpty { null }
        } while (page != null)
        return out
    }

    override suspend fun about(): Quota {
        val body = exec { t ->
            Request.Builder().url("$API/about?fields=storageQuota").header("Authorization", "Bearer $t").build()
        }.bodyOrThrow()
        val q = JSONObject(body).getJSONObject("storageQuota")
        val limit = q.optString("limit").ifEmpty { null }?.toLongOrNull()
        return Quota(limit, q.optString("usage", "0").toLongOrNull() ?: 0L)
    }

    override suspend fun listQueue(): List<DriveFile> =
        list("appProperties has { key='kind' and value='${DriveNames.KIND_QUEUE}' }")

    override suspend fun findBySha(sha: String): List<DriveFile> =
        list("appProperties has { key='sha' and value='$sha' } and appProperties has { key='kind' and value='${DriveNames.KIND_QUEUE}' }")

    override suspend fun startUpload(name: String, size: Long, mime: String, props: Map<String, String>): String {
        val meta = JSONObject()
            .put("name", name)
            .put("parents", JSONArray().put("appDataFolder"))
            .put("appProperties", JSONObject(props))
        val res = exec { t ->
            Request.Builder()
                .url("$UPLOAD/files?uploadType=resumable&fields=$FIELDS")
                .header("Authorization", "Bearer $t")
                .header("X-Upload-Content-Type", mime)
                .header("X-Upload-Content-Length", size.toString())
                .post(meta.toString().toRequestBody(JSON))
                .build()
        }
        res.use {
            if (!it.isSuccessful) throw DriveHttpException(it.code, it.body?.string().orEmpty())
            return it.header("Location") ?: throw DriveHttpException(it.code, "업로드 세션 주소 없음")
        }
    }

    override suspend fun uploadChunk(session: String, offset: Long, data: ByteArray, len: Int, total: Long): ChunkResult {
        val end = offset + len - 1
        val res = withContext(Dispatchers.IO) {
            val body = data.toRequestBody("application/octet-stream".toMediaType(), 0, len)
            val req = Request.Builder().url(session)
                .header("Content-Range", "bytes $offset-$end/$total")
                .put(body).build()
            http.newCall(req).execute()
        }
        res.use {
            return when (it.code) {
                308 -> ChunkResult.More(parseRange(it.header("Range"), offset + len))
                200, 201 -> ChunkResult.Done(parseFile(JSONObject(it.body?.string().orEmpty())))
                else -> throw DriveHttpException(it.code, it.body?.string().orEmpty())
            }
        }
    }

    private fun parseRange(h: String?, fallback: Long): Long {
        // "bytes=0-12345" -> 다음 offset = 12346
        val last = h?.substringAfter('-', "")?.toLongOrNull() ?: return fallback
        return last + 1
    }

    override suspend fun queryOffset(session: String, total: Long): Long? {
        val res = withContext(Dispatchers.IO) {
            val req = Request.Builder().url(session)
                .header("Content-Range", "bytes */$total")
                .put(ByteArray(0).toRequestBody(null))
                .build()
            http.newCall(req).execute()
        }
        res.use {
            return when (it.code) {
                308 -> it.header("Range")?.let { r -> parseRange(r, 0) } ?: 0L
                200, 201 -> total
                404, 410 -> null
                else -> throw DriveHttpException(it.code, it.body?.string().orEmpty())
            }
        }
    }

    override suspend fun openDownload(fileId: String, from: Long): InputStream {
        val res = exec { t ->
            val b = Request.Builder().url("$API/files/$fileId?alt=media").header("Authorization", "Bearer $t")
            if (from > 0) b.header("Range", "bytes=$from-")
            b.build()
        }
        if (res.code == 416) { res.close(); return ByteArrayInputStream(ByteArray(0)) }
        if (!res.isSuccessful) {
            val s = res.body?.string().orEmpty(); val c = res.code; res.close()
            throw DriveHttpException(c, s)
        }
        return res.body!!.byteStream()
    }

    override suspend fun deleteFile(fileId: String) {
        exec { t -> Request.Builder().url("$API/files/$fileId").header("Authorization", "Bearer $t").delete().build() }
            .use {
                if (!it.isSuccessful && it.code != 404) throw DriveHttpException(it.code, it.body?.string().orEmpty())
            }
    }

    private suspend fun createSmall(name: String, kind: String, content: String): DriveFile {
        val boundary = "pr${System.nanoTime()}"
        val meta = JSONObject().put("name", name).put("parents", JSONArray().put("appDataFolder"))
            .put("appProperties", JSONObject().put("kind", kind)).toString()
        val sb = StringBuilder()
            .append("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n").append(meta).append("\r\n")
            .append("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n").append(content).append("\r\n")
            .append("--$boundary--")
        val body = sb.toString().toRequestBody("multipart/related; boundary=$boundary".toMediaType())
        val s = exec { t ->
            Request.Builder().url("$UPLOAD/files?uploadType=multipart&fields=$FIELDS")
                .header("Authorization", "Bearer $t").post(body).build()
        }.bodyOrThrow()
        return parseFile(JSONObject(s))
    }

    override suspend fun putStatus(json: String) {
        var id = statusFileId
        if (id == null) {
            id = list("appProperties has { key='kind' and value='${DriveNames.KIND_STATUS}' }").firstOrNull()?.id
        }
        if (id == null) {
            statusFileId = createSmall(DriveNames.STATUS_NAME, DriveNames.KIND_STATUS, json).id
            return
        }
        val r = exec { t ->
            Request.Builder().url("$UPLOAD/files/$id?uploadType=media").header("Authorization", "Bearer $t")
                .patch(json.toRequestBody(JSON)).build()
        }
        if (r.code == 404) { r.close(); statusFileId = null; putStatus(json); return }
        r.bodyOrThrow()
        statusFileId = id
    }

    override suspend fun getStatus(): String? {
        val id = statusFileId
            ?: list("appProperties has { key='kind' and value='${DriveNames.KIND_STATUS}' }").firstOrNull()?.id
            ?: return null
        statusFileId = id
        return exec { t -> Request.Builder().url("$API/files/$id?alt=media").header("Authorization", "Bearer $t").build() }.bodyOrThrow()
    }

    override suspend fun createCommand(id: String, json: String) {
        createSmall(DriveNames.commandName(id), DriveNames.KIND_COMMAND, json)
    }

    override suspend fun listCommands(): List<DriveFile> =
        list("appProperties has { key='kind' and value='${DriveNames.KIND_COMMAND}' }")

    override suspend fun readCommand(fileId: String): String =
        exec { t -> Request.Builder().url("$API/files/$fileId?alt=media").header("Authorization", "Bearer $t").build() }.bodyOrThrow()

    override suspend fun deleteCommand(fileId: String) {
        exec { t -> Request.Builder().url("$API/files/$fileId").header("Authorization", "Bearer $t").delete().build() }
            .use { if (!it.isSuccessful && it.code != 404) throw DriveHttpException(it.code, it.body?.string().orEmpty()) }
    }
}
