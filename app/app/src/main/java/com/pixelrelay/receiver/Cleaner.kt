package com.pixelrelay.receiver

import com.pixelrelay.core.RecvState
import com.pixelrelay.core.ReceiverLedger
import com.pixelrelay.drive.DriveApi

enum class CleanResult { NOT_FOUND, NOT_CONFIRMED, NO_COPIES, PARTIAL, CLEANED }

/**
 * 삭제 규칙(유일, 설계서 7절): 받기 장부 항목이 PHOTOS_CONFIRMED 가 되면 그 sha 의 드라이브 사본 전부를 영구 삭제한다.
 * 큐 파일을 지우는 [DriveApi.deleteFile] 호출은 이 파일 안에만 있어야 한다(테스트가 소스를 검사한다).
 */
class Cleaner(
    private val ledger: ReceiverLedger,
    private val drive: DriveApi,
) {
    suspend fun clean(sha: String): CleanResult {
        val e = ledger.get(sha) ?: return CleanResult.NOT_FOUND
        val st = e.stateEnum
        if (st != RecvState.PHOTOS_CONFIRMED && st != RecvState.CLEANED) return CleanResult.NOT_CONFIRMED
        val copies = ledger.copies(sha)
        if (copies.isEmpty()) return CleanResult.NO_COPIES
        var allOk = true
        for (c in copies) {
            try {
                drive.deleteFile(c.driveFileId)
                ledger.removeCopy(sha, c.driveFileId)
            } catch (ex: Exception) {
                allOk = false
            }
        }
        if (!allOk) return CleanResult.PARTIAL
        if (st == RecvState.PHOTOS_CONFIRMED) ledger.transition(sha, RecvState.CLEANED)
        return CleanResult.CLEANED
    }

    /** 삭제에 실패했거나 뒤늦게 붙은 사본까지 다시 시도한다. */
    suspend fun cleanAllConfirmed() {
        for (e in ledger.all()) {
            val st = e.stateEnum
            if (st == RecvState.PHOTOS_CONFIRMED || st == RecvState.CLEANED) clean(e.sha)
        }
    }
}

/** 유일한 예외: 해시가 맞지 않아 보내기 쪽이 방금 만든 사본을 직접 지운다(설계서 7절). */
object UploadCleanup {
    suspend fun deleteBadUpload(drive: DriveApi, fileId: String) {
        drive.deleteFile(fileId)
    }
}
