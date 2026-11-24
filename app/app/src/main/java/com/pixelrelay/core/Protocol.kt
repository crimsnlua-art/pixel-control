package com.pixelrelay.core

import com.pixelrelay.core.db.AppDb
import com.pixelrelay.core.db.Meta
import org.json.JSONArray
import org.json.JSONObject

object Proto {
    const val PORT = 47321
    const val CHUNK_BYTES = 8 * 1024 * 1024
    const val SERVICE_TYPE = "_pixelrelay._tcp."
    const val SERVICE_NAME = "PixelRelay"
    const val HDR_TIME = "X-PR-Time"
    const val HDR_SIG = "X-PR-Sig"

    /** 받기 공간 여유(설계서 6절). */
    const val SPACE_MARGIN = 4 * GB

    /** 보내기 쪽 드라이브 용량 여유(설계서 4절). */
    const val DRIVE_SEND_MARGIN = 2 * GB

    const val HTTP_BUSY = 423
    const val HTTP_NO_SPACE = 507
}

enum class QueueMode { IMMEDIATE, CAPACITY }

/** 받기 설정. setConfig 명령으로 주력 폰에서 바꿀 수 있다. */
data class ReceiverConfig(
    val queueMode: QueueMode = QueueMode.IMMEDIATE,
    val thresholdBytes: Long = 10 * GB,
    /** 드라이브 계정 남은 용량에서 비워 둘 여유 (기본 2GB + 1GB = 3GB). */
    val reserveBytes: Long = 3 * GB,
) {
    fun toJson() = JSONObject()
        .put("queueMode", queueMode.name)
        .put("thresholdBytes", thresholdBytes)
        .put("reserveBytes", reserveBytes)

    companion object {
        fun fromJson(o: JSONObject) = ReceiverConfig(
            queueMode = runCatching { QueueMode.valueOf(o.optString("queueMode", "IMMEDIATE")) }.getOrDefault(QueueMode.IMMEDIATE),
            thresholdBytes = o.optLong("thresholdBytes", 10 * GB),
            reserveBytes = o.optLong("reserveBytes", 3 * GB),
        )
    }
}

object WarnCode {
    const val PHOTOS_STALLED = "PHOTOS_STALLED"
    const val LOW_SPACE = "LOW_SPACE"
    const val HIGH_TEMP = "HIGH_TEMP"
    const val UNPLUG_SUGGESTED = "UNPLUG_SUGGESTED"
    const val PLUG_SUGGESTED = "PLUG_SUGGESTED"
    const val LOW_BATTERY = "LOW_BATTERY"
    const val DRIVE_AUTH = "DRIVE_AUTH"

    fun korean(code: String): String = when (code) {
        PHOTOS_STALLED -> "픽셀에 정리 안 된 사진이 6시간 넘게 쌓여 있음 — 포토의 '기기 폴더 백업'에서 PixelRelay가 켜져 있는지 확인하고, 백업이 끝났으면 공간 확보를 하세요"
        LOW_SPACE -> "픽셀 저장 공간 부족(4GB 미만)"
        HIGH_TEMP -> "픽셀 온도 높음(40도 초과), 받기 중지"
        UNPLUG_SUGGESTED -> "충전기를 뽑는 것을 권장(80% 이상)"
        PLUG_SUGGESTED -> "충전기를 꽂는 것을 권장(60% 이하)"
        LOW_BATTERY -> "픽셀 배터리 20% 이하"
        DRIVE_AUTH -> "픽셀의 구글 드라이브 인증 필요"
        else -> code
    }
}

data class StatusWarning(val code: String, val since: Long)

data class StatusCounts(val published: Int, val confirmed: Int, val vanished: Int, val failed: Int)

/** /status 와 status.json 이 공유하는 상태 형식 (설계서 9절). */
data class PixelStatus(
    val freeBytes: Long,
    val batteryPct: Int,
    val charging: Boolean,
    val tempC: Double,
    val counts: StatusCounts,
    val lastPhotosActivityAt: Long,
    val config: ReceiverConfig,
    val warnings: List<StatusWarning>,
    val seq: Long,
    val appVersion: String,
    val reportedAt: Long,
    /** 무료 확인 시험: 픽셀에서 "포토 백업 완료됐어요"를 누른 시각(픽셀 시계, 0 = 없음) */
    val trialDoneAt: Long = 0,
    /** 마지막으로 "포토 공간 확보 열기"를 누른 시각(픽셀 시계, 0 = 없음) */
    val cleanupOpenedAt: Long = 0,
    /** 마지막 자동 공간 확보 결과 코드(CleanupResult, 빈 문자열 = 아직 없음)와 시각(픽셀 시계) */
    val cleanupResult: String = "",
    val cleanupResultAt: Long = 0,
    /** 픽셀의 접근성 서비스(자동 공간 확보)가 켜져 있는가. 옛 버전 픽셀이 안 보내면 true 로 본다(거짓 경고 방지). */
    val accessibilityOn: Boolean = true,
    /** 마지막 포토 열기 방법(LINK/LAUNCHER/FAILED, 빈 문자열 = 기록 없음) */
    val cleanupOpenVia: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("freeBytes", freeBytes)
        .put("batteryPct", batteryPct)
        .put("charging", charging)
        .put("tempC", tempC)
        .put(
            "counts",
            JSONObject().put("published", counts.published).put("confirmed", counts.confirmed)
                .put("vanished", counts.vanished).put("failed", counts.failed),
        )
        .put("lastPhotosActivityAt", lastPhotosActivityAt)
        .put("config", config.toJson())
        .put("warnings", JSONArray().also { a -> warnings.forEach { a.put(JSONObject().put("code", it.code).put("since", it.since)) } })
        .put("seq", seq)
        .put("appVersion", appVersion)
        .put("reportedAt", reportedAt)
        .put("trialDoneAt", trialDoneAt)
        .put("cleanupOpenedAt", cleanupOpenedAt)
        .put("cleanupResult", cleanupResult)
        .put("cleanupResultAt", cleanupResultAt)
        .put("accessibilityOn", accessibilityOn)
        .put("cleanupOpenVia", cleanupOpenVia)

    companion object {
        fun fromJson(o: JSONObject): PixelStatus {
            val c = o.optJSONObject("counts") ?: JSONObject()
            val w = o.optJSONArray("warnings") ?: JSONArray()
            return PixelStatus(
                freeBytes = o.optLong("freeBytes"),
                batteryPct = o.optInt("batteryPct"),
                charging = o.optBoolean("charging"),
                tempC = o.optDouble("tempC", 0.0),
                counts = StatusCounts(c.optInt("published"), c.optInt("confirmed"), c.optInt("vanished"), c.optInt("failed")),
                lastPhotosActivityAt = o.optLong("lastPhotosActivityAt"),
                config = ReceiverConfig.fromJson(o.optJSONObject("config") ?: JSONObject()),
                warnings = (0 until w.length()).map { StatusWarning(w.getJSONObject(it).getString("code"), w.getJSONObject(it).optLong("since")) },
                seq = o.optLong("seq"),
                appVersion = o.optString("appVersion"),
                reportedAt = o.optLong("reportedAt"),
                trialDoneAt = o.optLong("trialDoneAt"),
                cleanupOpenedAt = o.optLong("cleanupOpenedAt"),
                cleanupResult = o.optString("cleanupResult", ""),
                cleanupResultAt = o.optLong("cleanupResultAt"),
                accessibilityOn = o.optBoolean("accessibilityOn", true),
                cleanupOpenVia = o.optString("cleanupOpenVia", ""),
            )
        }
    }
}

data class LedgerItem(val sha: String, val state: String, val at: Long, val seq: Long) {
    fun toJson(): JSONObject = JSONObject().put("sha", sha).put("state", state).put("at", at).put("seq", seq)

    companion object {
        fun fromJson(o: JSONObject) = LedgerItem(o.getString("sha"), o.getString("state"), o.optLong("at"), o.getLong("seq"))
    }
}

data class LedgerPage(val items: List<LedgerItem>, val nextSeq: Long, val hasMore: Boolean) {
    fun toJson(): JSONObject = JSONObject()
        .put("items", JSONArray().also { a -> items.forEach { a.put(it.toJson()) } })
        .put("nextSeq", nextSeq)
        .put("hasMore", hasMore)

    companion object {
        fun fromJson(o: JSONObject): LedgerPage {
            val a = o.optJSONArray("items") ?: JSONArray()
            return LedgerPage((0 until a.length()).map { LedgerItem.fromJson(a.getJSONObject(it)) }, o.optLong("nextSeq"), o.optBoolean("hasMore"))
        }
    }
}

/** 명령 (LAN /command 와 드라이브 commands/{uuid}.json 공통). */
data class RemoteCommand(
    val type: String,
    val config: ReceiverConfig? = null,
    /** commandId(UUID). 파괴적 명령은 필수이고 한 번만 처리된다. */
    val id: String? = null,
    val sha: String? = null,
    /** sendNow: 이번 배치 sha 목록. 이들이 받기 장부에 오를 때까지(최대 24시간) 강제 수신 유지. */
    val expectShas: List<String> = emptyList(),
) {
    fun toJson(): JSONObject {
        val o = JSONObject().put("type", type)
        if (config != null) o.put("config", config.toJson())
        if (id != null) o.put("id", id)
        if (sha != null) o.put("sha", sha)
        if (expectShas.isNotEmpty()) o.put("expectShas", JSONArray(expectShas))
        return o
    }

    companion object {
        const val SEND_NOW = "sendNow"
        const val DRAIN_NOW = "drainNow"
        const val SET_CONFIG = "setConfig"
        const val CLEANUP_OPENED = "cleanupOpened"

        /** 설계서 목록에 없는 추가 명령: 원인 불명 항목을 주력 폰에서 "포토 확인됨으로 확정"(8절 화면 요구). */
        const val CONFIRM_VANISHED = "confirmVanished"
        const val RESEND_VANISHED = "resendVanished"

        /** 원인 불명 항목 전부를 "포토 확인됨"으로 확정(사용자 확인 대화상자 뒤에만 보냄). 파괴적. */
        const val CONFIRM_ALL_VANISHED = "confirmAllVanished"

        /** 픽셀 포토의 "공간 확보"를 실행(접근성 서비스가 버튼을 누름). 파괴적 명령 목록에는 없지만 commandId 는 1회만 처리한다. */
        const val FREE_UP_SPACE = "freeUpSpace"

        val DESTRUCTIVE = setOf(CONFIRM_VANISHED, RESEND_VANISHED, CONFIRM_ALL_VANISHED)

        /** commandId 를 반드시 요구하고 한 번만 처리하는 명령 */
        val ONCE_ONLY = DESTRUCTIVE + FREE_UP_SPACE

        fun fromJson(o: JSONObject) = RemoteCommand(
            type = o.getString("type"),
            config = o.optJSONObject("config")?.let { ReceiverConfig.fromJson(it) },
            id = o.optString("id").ifEmpty { null },
            sha = o.optString("sha").ifEmpty { null },
            expectShas = o.optJSONArray("expectShas")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
        )
    }
}

/** 장부 옆에 두는 작은 키-값 저장소(양쪽 역할 공용). */
class MetaStore(private val db: AppDb) {
    suspend fun getLong(k: String): Long? = db.recv().meta(k)?.v?.toLongOrNull()
    suspend fun putLong(k: String, v: Long) = db.recv().putMeta(Meta(k, v.toString()))
    suspend fun getString(k: String): String? = db.recv().meta(k)?.v
    suspend fun putString(k: String, v: String) = db.recv().putMeta(Meta(k, v))
}
