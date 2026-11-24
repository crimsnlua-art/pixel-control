package com.pixelrelay.core

/** 마법사가 여는 시스템 설정 바로가기 종류(실제 인텐트는 ui/Shortcuts.kt). */
enum class Shortcut { BATTERY_OPT, LOCK_SCREEN, STORAGE_SETTINGS, OPEN_PHOTOS, ACCESSIBILITY }

/** AUTO: 앱이 확인할 수 있어 자동 판정. MANUAL: 확인 불가라 사용자가 "했어요"를 체크. */
enum class StepKind { AUTO, MANUAL }

data class WizardStep(
    val id: String,
    val title: String,
    val body: String,
    val kind: StepKind,
    val shortcut: Shortcut? = null,
    /** 바로가기 인텐트가 안 열릴 때 보여줄 글 안내 */
    val fallback: String = "",
)

object WizardIds {
    const val PERMISSIONS = "permissions"
    const val BATTERY = "battery"
    const val LOCK_SCREEN = "lockScreen"
    const val SMART_STORAGE = "smartStorage"
    const val PHOTOS_BACKUP = "photosBackup"
    const val PHOTOS_FOLDER_ON = "photosFolderOn"
    const val ACCESSIBILITY = "accessibility"
    const val PHOTOS_OFF = "photosOff"
    const val GOOGLE = "google"
    const val PAIRING = "pairing"
}

/** 역할별 단계 목록(설계서 11-1). */
object WizardSteps {
    fun forRole(role: String): List<WizardStep> = if (role == "receiver") receiver() else sender()

    private fun receiver() = listOf(
        WizardStep(WizardIds.PERMISSIONS, "권한", "저장소(파일 쓰기)와 알림 권한을 허용하세요.", StepKind.AUTO),
        WizardStep(WizardIds.BATTERY, "배터리 최적화 제외", "상시 서버가 꺼지지 않도록 이 앱을 배터리 최적화에서 제외하세요.", StepKind.AUTO, Shortcut.BATTERY_OPT,
            "설정 > 앱 > 픽셀중계 > 배터리 > 제한 없음(또는 최적화 안 함)으로 바꿔 주세요."),
        WizardStep(WizardIds.LOCK_SCREEN, "화면 잠금 '없음'", "재부팅 뒤에도 잠금 없이 서비스가 시작되도록 화면 잠금을 '없음'으로 두세요.", StepKind.MANUAL, Shortcut.LOCK_SCREEN,
            "설정 > 보안 > 화면 잠금 > 없음 으로 바꿔 주세요."),
        WizardStep(WizardIds.SMART_STORAGE, "스마트 저장용량 끄기", "켜져 있으면 포토 공간 확보 기록 없이 파일이 사라져 '원인 불명'이 늘어납니다.", StepKind.MANUAL, Shortcut.STORAGE_SETTINGS,
            "설정 > 저장용량 > 스마트 저장용량을 끄세요."),
        WizardStep(WizardIds.PHOTOS_BACKUP, "구글 포토에서 PixelRelay 폴더 백업 켜기",
            "앱이 Pictures/PixelRelay 폴더를 만들고 안내용 작은 이미지 1장을 넣었습니다. 포토에서 '기기 폴더 백업'에 PixelRelay 폴더가 보이면 켜 주세요(원본 화질).",
            StepKind.MANUAL, Shortcut.OPEN_PHOTOS, "구글 포토 앱을 열어 라이브러리 > 기기 폴더 > PixelRelay 에서 백업을 켜 주세요."),
        WizardStep(WizardIds.PHOTOS_FOLDER_ON, "포토 '기기 폴더 백업'에서 PixelRelay 켜기",
            "구글 포토 > 라이브러리 > 기기 폴더에서 PixelRelay 의 백업 스위치가 켜져 있는지 직접 확인하세요. 꺼져 있으면 받은 사진이 업로드되지 않아 픽셀이 가득 차 받기가 멈춥니다.",
            StepKind.MANUAL, Shortcut.OPEN_PHOTOS, "구글 포토 앱 > 라이브러리 > 기기 폴더 > PixelRelay 의 '백업' 스위치를 켜 주세요."),
        WizardStep(WizardIds.ACCESSIBILITY, "자동 공간 확보 켜기(접근성)",
            "접근성 설정에서 '픽셀중계'를 켜면, 업로드가 끝난 뒤 포토의 '여유 공간 … 확보' 버튼을 앱이 대신 눌러 줍니다. 이 서비스는 구글 포토 화면만 보고, 우리 앱이 공간 확보를 시작했을 때만 그 버튼 하나를 누릅니다. 꺼 두면 공간 확보를 사람이 눌러야 합니다.",
            StepKind.AUTO, Shortcut.ACCESSIBILITY, "설정 > 접근성 > 설치된 서비스(또는 다운로드한 앱) > 픽셀중계 를 켜 주세요."),
        WizardStep(WizardIds.GOOGLE, "구글 계정 연결", "집 밖 전송과 상태 확인에 씁니다. 주력 폰과 같은 구글 계정으로 연결하세요.", StepKind.AUTO),
        WizardStep(WizardIds.PAIRING, "짝짓기", "'짝짓기' 탭의 6자리 코드를 주력 폰에서 입력하면 끝납니다.", StepKind.AUTO),
    )

    private fun sender() = listOf(
        WizardStep(WizardIds.PERMISSIONS, "권한", "사진·영상 '전체 허용', 사진 위치 정보, 알림, 로컬 네트워크(Android 16+ 기기) 권한을 허용하세요.", StepKind.AUTO),
        WizardStep(WizardIds.BATTERY, "배터리 최적화 제외", "백그라운드 전송이 끊기지 않도록 이 앱을 배터리 최적화에서 제외하세요.", StepKind.AUTO, Shortcut.BATTERY_OPT,
            "설정 > 앱 > 픽셀중계 > 배터리 > 제한 없음(또는 최적화 안 함)으로 바꿔 주세요. 삼성은 '절전 예외 앱'에도 추가하세요."),
        WizardStep(WizardIds.PHOTOS_OFF, "이 폰의 구글 포토 백업 끄기",
            "주력 폰이 먼저 올리면 구글 용량이 차감됩니다. 이 폰의 구글 포토 백업을 꺼 주세요.", StepKind.MANUAL, Shortcut.OPEN_PHOTOS,
            "구글 포토 앱 > 프로필 > 포토 설정 > 백업 을 끄세요."),
        WizardStep(WizardIds.GOOGLE, "구글 계정 연결", "집 밖 전송에 씁니다. 픽셀과 같은 구글 계정으로 연결하세요.", StepKind.AUTO),
        WizardStep(WizardIds.PAIRING, "픽셀 짝짓기", "픽셀 앱의 6자리 코드를 '연결' 탭에 입력하세요.", StepKind.AUTO),
    )
}

/** 단계 진행 판정(순수). 자동 단계는 auto 맵, 수동 단계는 사용자 체크 집합으로 본다. */
class WizardProgress(private val auto: Map<String, Boolean>, private val manual: Set<String>) {
    fun isDone(step: WizardStep): Boolean = if (step.kind == StepKind.AUTO) auto[step.id] == true else step.id in manual
    fun doneCount(steps: List<WizardStep>): Int = steps.count { isDone(it) }
    fun allDone(steps: List<WizardStep>): Boolean = steps.all { isDone(it) }
    fun firstUndone(steps: List<WizardStep>): WizardStep? = steps.firstOrNull { !isDone(it) }
}

/** 구글 계정 연결 실패 안내 문구. */
object AccountText {
    fun failure(hasConsentIntent: Boolean, detail: String?): String =
        if (hasConsentIntent) "승인 창을 열었습니다"
        else "앱 등록이 아직 안 됨 — 개발자 설정 필요(앱 주인이 구글 콘솔에서 OAuth 클라이언트를 한 번 등록해야 합니다). LAN 전송 등 나머지 기능은 그대로 동작합니다." +
            (detail?.let { " ($it)" } ?: "")
}

/** 픽셀 PixelRelay 폴더 안내 이미지 규칙. 이 파일은 받기 장부에 올리지 않고 포토 확인 판정 대상도 아니다. */
object GuideFile {
    const val PREFIX = "_pixelrelay_"
    const val NAME = "_pixelrelay_guide.png"
    fun isGuide(fileName: String): Boolean = fileName.startsWith(PREFIX)
}
