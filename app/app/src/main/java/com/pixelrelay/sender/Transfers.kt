package com.pixelrelay.sender

import com.pixelrelay.core.Clock
import com.pixelrelay.core.Proto
import com.pixelrelay.core.Reason
import com.pixelrelay.core.SendState
import com.pixelrelay.core.SenderLedger
import com.pixelrelay.core.db.SendEntry
import com.pixelrelay.drive.ChunkResult
import com.pixelrelay.drive.DriveApi
import com.pixelrelay.drive.DriveFile
import com.pixelrelay.drive.DriveNames
import com.pixelrelay.receiver.UploadCleanup
import java.io.InputStream

sealed class SendResult {
    /** 끝까지 보냄 */
    data class Done(val bytes: Long) : SendResult()

    /** 시간 예산이 끝나 일부만 보냄. 다음 워커가 이어 보낸다. */
    data class Partial(val bytes: Long) : SendResult()

    /** 연결 문제(LAN 끊김 등). 장부는 WAITING(네트워크조건)으로 돌아간다. */
    data class Network(val bytes: Long, val why: String) : SendResult()
    data class NoSpace(val permanent: Boolean) : SendResult()
    object Busy : SendResult()

    /** 픽셀이 already 라고 했지만 이 항목은 다시 보내기 흐름 중이라 도착으로 처리하면 안 된다. */
    object ResendNotYet : SendResult()
    object Hot : SendResult()
    object OriginalGone : SendResult()
    data class Failed(val why: String, val bytes: Long) : SendResult()
}

internal fun skipFully(ins: InputStream, n: Long): Boolean {
    var left = n
    while (left > 0) {
        val s = ins.skip(left)
        if (s <= 0) {
            if (ins.read() < 0) return false
            left -= 1
        } else left -= s
    }
    return true
}

internal fun readChunk(ins: InputStream, buf: ByteArray, want: Int): Int {
    var off = 0
    while (off < want) {
        val n = ins.read(buf, off, want - off)
        if (n < 0) break
        off += n
    }
    return off
}

/** LAN 으로 한 파일을 보낸다(begin -> 조각 -> commit). 이어 보내기: 오프셋은 픽셀이 기억한다. */
class LanSender(
    private val source: MediaSource,
    private val ledger: SenderLedger,
    private val clock: Clock,
    private val chunk: Int = Proto.CHUNK_BYTES,
) {
    suspend fun send(e: SendEntry, api: PixelApi, deadlineMs: Long): SendResult {
        var sent = 0L
        try {
            val b = api.begin(e.sha, e.name, e.size, e.takenAt, e.mime)
            var offset = when (b) {
                ApiBegin.Already ->
                    return if (ledger.get(e.sha)?.resendCommandId != null) SendResult.ResendNotYet else SendResult.Done(0)
                ApiBegin.Busy -> return SendResult.Busy
                ApiBegin.Hot -> return SendResult.Hot
                is ApiBegin.NoSpace -> return SendResult.NoSpace(b.permanent)
                is ApiBegin.Ok -> b.offset
            }
            ledger.tryTransition(e.sha, SendState.SENDING_LAN) { it.copy(route = "lan", lanOffset = offset) }

            var ins = source.openOriginal(e.uri) ?: return SendResult.OriginalGone
            try {
                if (!skipFully(ins, offset)) return SendResult.Failed("원본이 짧아짐", sent)
                val buf = ByteArray(chunk)
                while (true) {
                    if (offset >= e.size) {
                        when (val c = api.commit(e.sha)) {
                            ApiCommit.Published -> return SendResult.Done(sent)
                            ApiCommit.Mismatch -> return SendResult.Failed("픽셀 해시 불일치", sent)
                            ApiCommit.NotBegun -> return SendResult.Failed("받기 세션 없음", sent)
                            is ApiCommit.Incomplete -> {
                                offset = c.offset
                                ins.close(); ins = source.openOriginal(e.uri) ?: return SendResult.OriginalGone
                                skipFully(ins, offset)
                                continue
                            }
                        }
                    }
                    if (clock.nowMs() > deadlineMs) return SendResult.Partial(sent)
                    val want = minOf(chunk.toLong(), e.size - offset).toInt()
                    val n = readChunk(ins, buf, want)
                    if (n <= 0) return SendResult.Failed("원본이 짧아짐", sent)
                    when (val r = api.put(e.sha, offset, buf, n)) {
                        is ApiPut.Ok -> { sent += n; offset = r.offset; ledger.setLanOffset(e.sha, offset) }
                        is ApiPut.Conflict -> {
                            offset = r.offset
                            ins.close(); ins = source.openOriginal(e.uri) ?: return SendResult.OriginalGone
                            skipFully(ins, offset)
                        }
                        ApiPut.Hot -> return SendResult.Hot
                        ApiPut.NotBegun -> return SendResult.Network(sent, "받기 세션 사라짐")
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                return SendResult.Done(sent)
            } finally {
                runCatching { ins.close() }
            }
        } catch (ex: java.io.IOException) {
            return SendResult.Network(sent, ex.message ?: "연결 끊김")
        } catch (ex: SecurityException) {
            return SendResult.Failed("권한 오류", sent)
        }
    }
}

sealed class DriveSendResult {
    data class Done(val fileId: String, val bytes: Long) : DriveSendResult()
    data class Partial(val bytes: Long) : DriveSendResult()
    data class Network(val bytes: Long, val why: String) : DriveSendResult()
    object Auth : DriveSendResult()

    /** 403/429/5xx: 시도 횟수를 늘리지 않고 나중에 다시 */
    data class Retry(val bytes: Long, val why: String) : DriveSendResult()
    object OriginalGone : DriveSendResult()
    data class Failed(val why: String, val bytes: Long) : DriveSendResult()
}

/** 드라이브 appDataFolder/queue 로 한 파일을 올린다(재개 가능 업로드, 설계서 7절). */
class DriveSender(
    private val source: MediaSource,
    private val ledger: SenderLedger,
    private val drive: DriveApi,
    private val clock: Clock,
    private val chunk: Int = Proto.CHUNK_BYTES,
) {
    suspend fun send(e0: SendEntry, deadlineMs: Long): DriveSendResult {
        var sent = 0L
        try {
            var e = e0
            // 1) 같은 sha 가 이미 queue 에 있으면 업로드 없이 IN_DRIVE
            if (e.driveSessionUri == null) {
                val existing = drive.findBySha(e.sha)
                if (existing.isNotEmpty()) {
                    markInDrive(e, existing.first().id)
                    return DriveSendResult.Done(existing.first().id, 0)
                }
                val props = mapOf(
                    "kind" to DriveNames.KIND_QUEUE, "sha" to e.sha, "name" to e.name,
                    "takenAt" to e.takenAt.toString(), "mime" to e.mime, "size" to e.size.toString(),
                )
                val session = drive.startUpload(DriveNames.queueName(e.sha, e.name), e.size, e.mime, props)
                val setSession = { x: SendEntry -> x.copy(route = "drive", driveSessionUri = session, driveOffset = 0) }
                if (ledger.get(e.sha)?.stateEnum == SendState.UPLOADING_DRIVE) ledger.patch(e.sha, setSession)
                else ledger.tryTransition(e.sha, SendState.UPLOADING_DRIVE, null, setSession)
                e = ledger.get(e.sha) ?: return DriveSendResult.Failed("장부 없음", sent)
            }
            val session = e.driveSessionUri ?: return DriveSendResult.Failed("세션 없음", sent)

            // 2) 서버가 아는 진행 위치로 맞춘다
            val serverOffset = drive.queryOffset(session, e.size)
            if (serverOffset == null) {
                ledger.patch(e.sha) { it.copy(driveSessionUri = null, driveOffset = 0) }
                return DriveSendResult.Failed("업로드 세션 만료(다음에 새로 시작)", sent)
            }
            var offset: Long = serverOffset

            var ins = source.openOriginal(e.uri) ?: return DriveSendResult.OriginalGone
            try {
                if (!skipFully(ins, offset)) return DriveSendResult.Failed("원본이 짧아짐", sent)
                val buf = ByteArray(chunk)
                while (offset < e.size) {
                    if (clock.nowMs() > deadlineMs) return DriveSendResult.Partial(sent)
                    val want = minOf(chunk.toLong(), e.size - offset).toInt()
                    val n = readChunk(ins, buf, want)
                    if (n <= 0) return DriveSendResult.Failed("원본이 짧아짐", sent)
                    when (val r = drive.uploadChunk(session, offset, buf, n, e.size)) {
                        is ChunkResult.More -> {
                            sent += n
                            if (r.nextOffset != offset + n) {
                                ins.close(); ins = source.openOriginal(e.uri) ?: return DriveSendResult.OriginalGone
                                skipFully(ins, r.nextOffset)
                            }
                            offset = r.nextOffset
                            ledger.setDriveOffset(e.sha, offset)
                        }
                        is ChunkResult.Done -> {
                            sent += n
                            return finish(e, r.file, sent)
                        }
                    }
                }
                // 이미 전부 올라가 있었던 경우: 파일을 찾아 확인
                val f = drive.findBySha(e.sha).firstOrNull() ?: return DriveSendResult.Failed("완료 확인 실패", sent)
                return finish(e, f, sent)
            } finally {
                runCatching { ins.close() }
            }
        } catch (ex: com.pixelrelay.drive.DriveAuthException) {
            return DriveSendResult.Auth
        } catch (ex: com.pixelrelay.drive.DriveHttpException) {
            // 드라이브 오류 분류(§15): 401 -> 인증, 403/429/5xx -> 재시도, 그 밖 -> 실패(시도 증가). 파일별로 처리한다.
            return when {
                ex.code == 401 -> DriveSendResult.Auth
                ex.code == 403 || ex.code == 429 || ex.code in 500..599 -> DriveSendResult.Retry(sent, "드라이브 ${ex.code}")
                else -> DriveSendResult.Failed("드라이브 오류 ${ex.code}", sent)
            }
        } catch (ex: java.io.IOException) {
            return DriveSendResult.Network(sent, ex.message ?: "연결 끊김")
        } catch (ex: SecurityException) {
            return DriveSendResult.Failed("권한 오류", sent)
        }
    }

    /** 응답의 sha256Checksum 과 대조: 일치해야 IN_DRIVE, 불일치면 그 사본을 지우고 처음부터 다시. */
    private suspend fun finish(e: SendEntry, f: DriveFile, sent: Long): DriveSendResult {
        val sum = f.sha256
        if (sum != null && sum != e.sha) {
            UploadCleanup.deleteBadUpload(drive, f.id)
            ledger.patch(e.sha) { it.copy(driveSessionUri = null, driveOffset = 0, driveFileId = null) }
            return DriveSendResult.Failed("드라이브 해시 불일치(사본 삭제)", sent)
        }
        markInDrive(e, f.id)
        return DriveSendResult.Done(f.id, sent)
    }

    private suspend fun markInDrive(e: SendEntry, fileId: String) {
        val cur = ledger.get(e.sha) ?: return
        // WAITING 에서 바로 IN_DRIVE 로(이미 있던 사본) 또는 UPLOADING_DRIVE 에서
        ledger.tryTransition(cur.sha, SendState.IN_DRIVE) {
            it.copy(driveFileId = fileId, driveSessionUri = null, driveOffset = it.size, route = "drive")
        }
    }
}
