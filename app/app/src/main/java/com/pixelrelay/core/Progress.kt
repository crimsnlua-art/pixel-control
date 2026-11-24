package com.pixelrelay.core

import com.pixelrelay.core.db.SendEntry

/** 드라이브(구글 계정 연결)를 쓰는지에 따른 표시 규칙. */
object DriveUse {
    const val KEY_SKIPPED = "driveSkipped"

    /** 연결 안 함을 고른 상태에서는 드라이브 인증 경고를 보여 주지 않는다(집 와이파이 전용). */
    fun visibleWarnings(warnings: List<StatusWarning>, driveUsed: Boolean): List<StatusWarning> =
        if (driveUsed) warnings else warnings.filter { it.code != WarnCode.DRIVE_AUTH }

    /** 홈 화면 빨간 경고 문구: 드라이브를 쓰는데 인증 문제가 있을 때만. */
    fun homeAuthWarning(driveUsed: Boolean, authProblem: Boolean): String? =
        if (driveUsed && authProblem) "구글 드라이브 연결이 필요합니다(집 밖 전송 중지)." else null

    /** 연결 안 함 상태의 회색 안내 한 줄. */
    fun skippedNote(driveUsed: Boolean): String? =
        if (driveUsed) null else "구글 드라이브 연결 안 함: 집 와이파이에서만 보냅니다."

    /** "연결 안 함" 선택: 남아 있던 인증 문제 표시도 함께 지운다(안 지우면 연결 안 함인데도 옛 경고가 남을 수 있다). */
    suspend fun setSkipped(meta: MetaStore, skipped: Boolean) {
        meta.putLong(KEY_SKIPPED, if (skipped) 1 else 0)
        if (skipped) meta.putLong("driveAuthProblem", 0)
    }
}

/** 홈 화면의 보내기 진행 요약(순수 계산). */
data class SendProgress(
    /** 아직 픽셀에 도착하지 않은 보낼 파일 수(대기·전송 중·드라이브 올림) */
    val remaining: Int,
    /** 그중 픽셀 공간 부족으로 기다리는 수 */
    val pixelSpaceWaiting: Int,
    /** 해시 예산 때문에 아직 장부에 올리지 못한 기존 파일 수(확인 중) */
    val scanPending: Int,
    val remainingBytes: Long,
    /** 드라이브에 올라가 있고 픽셀이 받아 가길 기다리는 수 */
    val inDrive: Int = 0,
    /** 드라이브를 쓰는가(연결 안 함이면 드라이브에 남은 파일은 연결 전까지 안 움직인다) */
    val driveUsed: Boolean = true,
) {
    companion object {
        private val open = setOf(
            SendState.WAITING.name, SendState.SENDING_LAN.name, SendState.UPLOADING_DRIVE.name, SendState.IN_DRIVE.name,
        )

        fun of(entries: List<SendEntry>, scanPending: Int, driveUsed: Boolean = true): SendProgress {
            val mine = entries.filter { it.state in open && !it.sha.startsWith("m") }
            val space = mine.count { it.state == SendState.WAITING.name && it.reason == Reason.PIXEL_SPACE }
            return SendProgress(mine.size, space, scanPending, mine.sumOf { it.size }, mine.count { it.state == SendState.IN_DRIVE.name }, driveUsed)
        }
    }

    /** 보일 말이 있으면 한글 줄 목록. */
    fun lines(): List<String> = buildList {
        if (scanPending > 0) add("기존 사진 확인 중: ${scanPending}개 남음(이어서 계속 확인합니다)")
        if (remaining > 0) add("보낼 파일 남음: ${remaining}개")
        if (!driveUsed && inDrive > 0) add("드라이브에 남은 ${inDrive}개(드라이브 연결 필요)")
        if (pixelSpaceWaiting > 0) add("픽셀 공간 부족으로 대기 중 ${pixelSpaceWaiting}개(픽셀이 포토 공간을 비우면 자동으로 이어 보냅니다)")
    }
}
