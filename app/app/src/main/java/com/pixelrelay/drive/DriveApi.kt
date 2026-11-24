package com.pixelrelay.drive

import java.io.InputStream

/** 드라이브 appDataFolder 안의 파일 한 개. */
data class DriveFile(
    val id: String,
    val name: String,
    val size: Long,
    val sha256: String?,
    val props: Map<String, String>,
) {
    /** 우리가 올릴 때 붙인 원본 sha (appProperties.sha) */
    val sha: String? get() = props["sha"]
}

data class Quota(val limitBytes: Long?, val usageBytes: Long) {
    /** 계정 남은 용량. 한도를 모르면(무제한) 아주 큰 값. */
    val remaining: Long get() = if (limitBytes == null) Long.MAX_VALUE / 4 else limitBytes - usageBytes
}

sealed class ChunkResult {
    data class More(val nextOffset: Long) : ChunkResult()
    data class Done(val file: DriveFile) : ChunkResult()
}

/** 사용자 동의가 필요하거나 인증을 받을 수 없음. 드라이브 경로를 멈추고 알림을 띄운다. */
class DriveAuthException(val pendingIntent: android.app.PendingIntent? = null, msg: String = "구글 드라이브 인증 필요") : Exception(msg)

class DriveHttpException(val code: Int, val body: String) : Exception("드라이브 HTTP $code: ${body.take(200)}")

object DriveNames {
    const val KIND_QUEUE = "queue"
    const val KIND_STATUS = "status"
    const val KIND_COMMAND = "command"
    const val STATUS_NAME = "status.json"

    fun queueName(sha: String, name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(8)
        return if (ext.isEmpty()) "queue/$sha" else "queue/$sha.$ext"
    }

    fun commandName(uuid: String) = "commands/$uuid.json"
}

/**
 * 드라이브 경계(설계서 7절). 실제 구현 = RealDriveApi, 테스트 = 가짜.
 *
 * 삭제 규칙(유일): 큐 파일 삭제 [deleteFile] 은 [com.pixelrelay.receiver.Cleaner] 에서만 호출한다.
 * 명령 파일은 [deleteCommand] 로 따로 지운다.
 */
interface DriveApi {
    suspend fun about(): Quota
    suspend fun listQueue(): List<DriveFile>
    suspend fun findBySha(sha: String): List<DriveFile>

    /** 재개 가능 업로드 세션을 시작하고 세션 URI 를 돌려준다. */
    suspend fun startUpload(name: String, size: Long, mime: String, props: Map<String, String>): String

    /** [offset] 부터 [len] 바이트를 올린다. 마지막 조각이면 Done. */
    suspend fun uploadChunk(session: String, offset: Long, data: ByteArray, len: Int, total: Long): ChunkResult

    /** 세션이 올린 만큼(바이트). 세션이 사라졌으면 null. */
    suspend fun queryOffset(session: String, total: Long): Long?

    suspend fun openDownload(fileId: String, from: Long): InputStream

    suspend fun deleteFile(fileId: String)

    suspend fun putStatus(json: String)
    suspend fun getStatus(): String?

    suspend fun createCommand(id: String, json: String)
    suspend fun listCommands(): List<DriveFile>
    suspend fun readCommand(fileId: String): String
    suspend fun deleteCommand(fileId: String)
}
