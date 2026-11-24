package com.pixelrelay.core

/** 보내기 장부 상태 (설계서 3절). */
/** RESEND_PENDING: 다시 보내기를 요청했고 픽셀이 RESEND_REQUESTED 로 확인해 줄 때까지 기다리는 상태(영속). */
enum class SendState { WAITING, SENDING_LAN, UPLOADING_DRIVE, IN_DRIVE, RECEIVED, PHOTOS_CONFIRMED, VANISHED_UNKNOWN, FAILED, SKIPPED, RESEND_PENDING }

/** 받기 장부 상태. FAILED 는 드라이브 해시 불일치 3회 뒤 표시용(설계서 7절). */
enum class RecvState { RECEIVING, PUBLISHED, PHOTOS_CONFIRMED, CLEANED, VANISHED_UNKNOWN, SKIPPED, FAILED, RESEND_REQUESTED }

/** 대기·건너뜀·실패 사유 코드. */
object Reason {
    const val RECORDING = "기록중"
    const val PERMISSION = "권한필요"
    const val NETWORK = "네트워크조건"
    const val DAILY_LIMIT = "하루한도"
    const val DRIVE_QUOTA = "드라이브용량"
    const val PIXEL_SPACE = "픽셀공간"
    const val TOO_LARGE = "크기초과"
    const val EXCLUDED = "제외폴더"
    const val ORIGINAL_MISSING = "원본없음"
    const val ORIGINAL_CHANGED = "원본변경"
}

class IllegalTransition(msg: String) : IllegalStateException(msg)

object Transitions {
    private val progress = setOf(
        SendState.RECEIVED, SendState.PHOTOS_CONFIRMED, SendState.VANISHED_UNKNOWN,
    )

    private val send: Map<SendState, Set<SendState>> = mapOf(
        SendState.WAITING to (setOf(
            SendState.WAITING, SendState.SENDING_LAN, SendState.UPLOADING_DRIVE, SendState.IN_DRIVE,
            SendState.FAILED, SendState.SKIPPED, SendState.RESEND_PENDING,
        ) + progress),
        SendState.SENDING_LAN to (setOf(SendState.WAITING, SendState.FAILED, SendState.SKIPPED, SendState.RESEND_PENDING) + progress),
        SendState.UPLOADING_DRIVE to (setOf(
            SendState.WAITING, SendState.IN_DRIVE, SendState.FAILED, SendState.SKIPPED,
        ) + progress),
        SendState.IN_DRIVE to (setOf(SendState.FAILED, SendState.SKIPPED) + progress),
        SendState.RECEIVED to setOf(SendState.PHOTOS_CONFIRMED, SendState.VANISHED_UNKNOWN),
        SendState.PHOTOS_CONFIRMED to emptySet(),
        // 원인 불명 -> 사용자가 확정할 때만 포토 확인됨 (다시 보내기는 resendVanished() 전용 예외)
        SendState.VANISHED_UNKNOWN to setOf(SendState.PHOTOS_CONFIRMED, SendState.RESEND_PENDING),
        // 다시 보내기 요청 중: 픽셀이 허용(RESEND_REQUESTED)을 확인하면 WAITING(resendConfirmed 전용), 그 사이 확정되면 포토 확인됨
        SendState.RESEND_PENDING to setOf(SendState.PHOTOS_CONFIRMED, SendState.VANISHED_UNKNOWN),
        // 실패 -> 사용자 재시도는 retryFailed() 전용 예외
        SendState.FAILED to emptySet(),
        SendState.SKIPPED to emptySet(),
    )

    private val recv: Map<RecvState, Set<RecvState>> = mapOf(
        RecvState.RECEIVING to setOf(RecvState.PUBLISHED, RecvState.FAILED, RecvState.SKIPPED),
        RecvState.PUBLISHED to setOf(RecvState.PHOTOS_CONFIRMED, RecvState.VANISHED_UNKNOWN),
        RecvState.PHOTOS_CONFIRMED to setOf(RecvState.CLEANED),
        RecvState.CLEANED to emptySet(),
        RecvState.VANISHED_UNKNOWN to setOf(RecvState.PHOTOS_CONFIRMED, RecvState.RESEND_REQUESTED),
        RecvState.SKIPPED to emptySet(),
        RecvState.FAILED to setOf(RecvState.RECEIVING),
        // 주력 폰이 "다시 보내기"를 요청한 항목: 다음 begin/드라이브 수신 때 RECEIVING
        RecvState.RESEND_REQUESTED to setOf(RecvState.RECEIVING),
    )

    fun allowed(from: SendState, to: SendState): Boolean = send[from]?.contains(to) == true
    fun allowed(from: RecvState, to: RecvState): Boolean = recv[from]?.contains(to) == true

    /** 한 번 픽셀에 도착한 뒤의 상태들: 자동 재전송 대상이 아니다. */
    val sendDone: Set<SendState> = setOf(
        SendState.RECEIVED, SendState.PHOTOS_CONFIRMED, SendState.VANISHED_UNKNOWN,
    )
}
