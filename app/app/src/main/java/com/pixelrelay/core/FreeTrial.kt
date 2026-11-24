package com.pixelrelay.core

import org.json.JSONArray
import org.json.JSONObject

/** 무료 업로드 확인 시험의 단계(설계서 11-1). 주력 폰에서 시작하고 상태는 영속 저장한다. */
enum class TrialPhase {
    IDLE,

    /** 시험 사진 3장을 일반 경로로 픽셀에 보내는 중 */
    SENDING,

    /** 픽셀에서 "포토 백업 완료" 신호를 기다리는 중 */
    AWAIT_PIXEL_DONE,

    /** 완료 신호 직후 사용량 재측정 대기(곧 측정) */
    MEASURE_NOW,

    /** 완료 신호 10분 뒤 한 번 더 측정하려고 기다리는 중 */
    WAIT_10MIN,

    /** 공간 확보 시험: 픽셀에서 "포토 공간 확보 열기"를 누르기를 기다리는 중 */
    AWAIT_CLEANUP,

    /** 시험 파일이 포토 확인됨이 되는지 지켜보는 중(최대 1시간) */
    WATCH_CLEANUP,
    FINISHED,
    FAILED,
}

enum class UsageVerdict { FREE, CHARGED, UNKNOWN }
enum class SpaceVerdict { OK, PARTIAL, NOT_ERASED }

data class TrialFile(val mediaId: Long, val size: Long, val name: String, val sha: String? = null)

/**
 * 시험 상태 전체. 시계 차이를 피하려고 픽셀의 시각은 "새 값인가"만 보고(기준값보다 커졌는가),
 * 10분·1시간 경과는 주력 폰이 그 신호를 처음 본 시각(doneSeenAt, cleanupSeenAt) 기준으로 잰다.
 */
data class TrialState(
    val phase: TrialPhase = TrialPhase.IDLE,
    val startedAt: Long = 0,
    val startUsage: Long? = null,
    val files: List<TrialFile> = emptyList(),
    val baseDoneAt: Long = 0,
    val baseCleanupAt: Long = 0,
    val doneSeenAt: Long? = null,
    val usageAfterDone: Long? = null,
    val usageAfter10: Long? = null,
    val usageVerdict: UsageVerdict? = null,
    val cleanupSeenAt: Long? = null,
    val spaceVerdict: SpaceVerdict? = null,
    val confirmedCount: Int = 0,
    val note: String = "",
) {
    val totalBytes: Long get() = files.sumOf { it.size }

    fun toJson(): JSONObject = JSONObject()
        .put("phase", phase.name).put("startedAt", startedAt)
        .put("startUsage", startUsage ?: JSONObject.NULL)
        .put("files", JSONArray().also { a ->
            files.forEach { a.put(JSONObject().put("mediaId", it.mediaId).put("size", it.size).put("name", it.name).put("sha", it.sha ?: JSONObject.NULL)) }
        })
        .put("baseDoneAt", baseDoneAt).put("baseCleanupAt", baseCleanupAt)
        .put("doneSeenAt", doneSeenAt ?: JSONObject.NULL)
        .put("usageAfterDone", usageAfterDone ?: JSONObject.NULL)
        .put("usageAfter10", usageAfter10 ?: JSONObject.NULL)
        .put("usageVerdict", usageVerdict?.name ?: JSONObject.NULL)
        .put("cleanupSeenAt", cleanupSeenAt ?: JSONObject.NULL)
        .put("spaceVerdict", spaceVerdict?.name ?: JSONObject.NULL)
        .put("confirmedCount", confirmedCount).put("note", note)

    companion object {
        private fun JSONObject.longOrNull(k: String): Long? = if (isNull(k) || !has(k)) null else getLong(k)

        fun fromJson(o: JSONObject): TrialState {
            val fa = o.optJSONArray("files") ?: JSONArray()
            return TrialState(
                phase = runCatching { TrialPhase.valueOf(o.getString("phase")) }.getOrDefault(TrialPhase.IDLE),
                startedAt = o.optLong("startedAt"),
                startUsage = o.longOrNull("startUsage"),
                files = (0 until fa.length()).map {
                    val f = fa.getJSONObject(it)
                    TrialFile(f.getLong("mediaId"), f.getLong("size"), f.optString("name"), if (f.isNull("sha")) null else f.optString("sha"))
                },
                baseDoneAt = o.optLong("baseDoneAt"), baseCleanupAt = o.optLong("baseCleanupAt"),
                doneSeenAt = o.longOrNull("doneSeenAt"),
                usageAfterDone = o.longOrNull("usageAfterDone"), usageAfter10 = o.longOrNull("usageAfter10"),
                usageVerdict = if (o.isNull("usageVerdict")) null else runCatching { UsageVerdict.valueOf(o.getString("usageVerdict")) }.getOrNull(),
                cleanupSeenAt = o.longOrNull("cleanupSeenAt"),
                spaceVerdict = if (o.isNull("spaceVerdict")) null else runCatching { SpaceVerdict.valueOf(o.getString("spaceVerdict")) }.getOrNull(),
                confirmedCount = o.optInt("confirmedCount"),
                note = o.optString("note"),
            )
        }
    }
}

/** 시험에 쓰는 신호 이름(픽셀 장부 옆 키-값). */
object TrialSignals {
    /** 픽셀에서 "포토 백업 완료됐어요"를 누른 시각(픽셀 시계) */
    const val KEY_PHOTOS_DONE = "trialPhotosDoneAt"
}

/** 한 번 읽은 바깥 상황(순수 입력). */
data class TrialSnapshot(
    val now: Long,
    /** 시험 파일 sha -> 보내기 장부 상태 이름 */
    val fileStates: Map<String, String>,
    val pixelDoneAt: Long,
    val pixelCleanupAt: Long,
    /** 이번 회차에 잰 드라이브 계정 사용량(안 쟀으면 null) */
    val usageBytes: Long?,
)

sealed class StartResult {
    data class Started(val state: TrialState) : StartResult()

    /** 구글 계정이 연결되지 않아 사용량을 못 읽음 */
    object AccountRequired : StartResult()
    object WrongCount : StartResult()
}

/** 시험 판정·상태 전이. 안드로이드·네트워크를 모르는 순수 논리. */
object FreeTrialMachine {
    const val COUNT = 3
    const val MEASURE_DELAY_MS = 10 * 60_000L
    const val CLEANUP_WINDOW_MS = 60 * 60_000L

    /** 증가량이 시험 파일 합계의 이 비율 이상이면 용량이 차감된 것으로 본다. */
    const val CHARGE_RATIO = 0.5

    private val ARRIVED = setOf("RECEIVED", "PHOTOS_CONFIRMED", "VANISHED_UNKNOWN")
    private val DEAD = setOf("SKIPPED", "FAILED")

    /** 사용량 증가 판정: 증가량 >= 합계의 50% 이면 차감됨. 합계가 0 이하이거나 값이 없으면 판단 불가. */
    fun judgeUsage(start: Long?, after: Long?, totalBytes: Long): UsageVerdict {
        if (start == null || after == null || totalBytes <= 0) return UsageVerdict.UNKNOWN
        return if ((after - start).toDouble() >= totalBytes * CHARGE_RATIO) UsageVerdict.CHARGED else UsageVerdict.FREE
    }

    fun start(startUsage: Long?, files: List<TrialFile>, baseDoneAt: Long, baseCleanupAt: Long, now: Long): StartResult {
        if (startUsage == null) return StartResult.AccountRequired
        if (files.size != COUNT || files.map { it.mediaId }.toSet().size != COUNT) return StartResult.WrongCount
        return StartResult.Started(
            TrialState(
                phase = TrialPhase.SENDING, startedAt = now, startUsage = startUsage, files = files,
                baseDoneAt = baseDoneAt, baseCleanupAt = baseCleanupAt,
            ),
        )
    }

    /** 이번 회차에 드라이브 사용량을 재야 하는가 */
    fun wantsUsage(s: TrialState, now: Long): Boolean = when (s.phase) {
        TrialPhase.MEASURE_NOW -> true
        TrialPhase.WAIT_10MIN -> s.doneSeenAt != null && now - s.doneSeenAt >= MEASURE_DELAY_MS
        else -> false
    }

    fun advance(s: TrialState, snap: TrialSnapshot): TrialState {
        val now = snap.now
        return when (s.phase) {
            TrialPhase.SENDING -> {
                val states = s.files.map { f -> f.sha?.let { snap.fileStates[it] } }
                when {
                    states.any { it in DEAD } -> s.copy(phase = TrialPhase.FAILED, note = "시험 파일 전송이 건너뜀/실패로 끝났습니다. 다른 사진으로 다시 해 주세요.")
                    states.all { it != null && it in ARRIVED } -> s.copy(phase = TrialPhase.AWAIT_PIXEL_DONE)
                    else -> s
                }
            }
            TrialPhase.AWAIT_PIXEL_DONE ->
                if (snap.pixelDoneAt > s.baseDoneAt) s.copy(phase = TrialPhase.MEASURE_NOW, doneSeenAt = now) else s

            TrialPhase.MEASURE_NOW -> {
                val u = snap.usageBytes ?: return s
                if (FreeTrialMachine.judgeUsage(s.startUsage, u, s.totalBytes) == UsageVerdict.CHARGED) {
                    // 이미 차감이 보이면 10분을 기다릴 필요 없이 결론
                    s.copy(usageAfterDone = u, usageVerdict = UsageVerdict.CHARGED, phase = TrialPhase.AWAIT_CLEANUP)
                } else s.copy(usageAfterDone = u, phase = TrialPhase.WAIT_10MIN)
            }
            TrialPhase.WAIT_10MIN -> {
                val u = snap.usageBytes ?: return s
                if (!wantsUsage(s, now)) return s
                val worst = maxOf(s.usageAfterDone ?: u, u)
                s.copy(usageAfter10 = u, usageVerdict = judgeUsage(s.startUsage, worst, s.totalBytes), phase = TrialPhase.AWAIT_CLEANUP)
            }
            TrialPhase.AWAIT_CLEANUP ->
                if (snap.pixelCleanupAt > s.baseCleanupAt) s.copy(phase = TrialPhase.WATCH_CLEANUP, cleanupSeenAt = now) else s

            TrialPhase.WATCH_CLEANUP -> {
                val confirmed = s.files.count { f -> f.sha?.let { snap.fileStates[it] } == "PHOTOS_CONFIRMED" }
                val since = s.cleanupSeenAt ?: now
                when {
                    confirmed == s.files.size -> s.copy(confirmedCount = confirmed, spaceVerdict = SpaceVerdict.OK, phase = TrialPhase.FINISHED)
                    now - since >= CLEANUP_WINDOW_MS ->
                        s.copy(
                            confirmedCount = confirmed, phase = TrialPhase.FINISHED,
                            spaceVerdict = if (confirmed == 0) SpaceVerdict.NOT_ERASED else SpaceVerdict.PARTIAL,
                        )
                    else -> s.copy(confirmedCount = confirmed)
                }
            }
            TrialPhase.IDLE, TrialPhase.FINISHED, TrialPhase.FAILED -> s
        }
    }
}

/** 화면에 보일 한글 문구(판정은 순수 논리라 테스트한다). */
object TrialText {
    fun phase(s: TrialState): String = when (s.phase) {
        TrialPhase.IDLE -> "시험을 시작하지 않았습니다."
        TrialPhase.SENDING -> "시험 사진 3장을 픽셀로 보내는 중입니다."
        TrialPhase.AWAIT_PIXEL_DONE -> "픽셀 화면에서 포토 백업이 끝나면 '포토 백업 완료됐어요'를 눌러 주세요."
        TrialPhase.MEASURE_NOW -> "완료 신호를 받았습니다. 드라이브 사용량을 확인하는 중입니다."
        TrialPhase.WAIT_10MIN -> "10분 뒤 사용량을 한 번 더 확인합니다(구글의 사용량 반영이 늦을 수 있어서입니다)."
        TrialPhase.AWAIT_CLEANUP -> "이어서 공간 확보 시험: 픽셀에서 '포토 공간 확보 열기'를 누르고 포토 앱에서 기기 공간 확보를 실행하세요."
        TrialPhase.WATCH_CLEANUP -> "시험 파일이 사라지는지 지켜보는 중입니다(최대 1시간). 지금 ${s.confirmedCount}/${s.files.size}장 확인됨."
        TrialPhase.FINISHED -> "시험이 끝났습니다."
        TrialPhase.FAILED -> s.note.ifEmpty { "시험이 실패했습니다." }
    }

    fun usage(s: TrialState): String? = when (s.usageVerdict) {
        UsageVerdict.FREE -> "무료 확인: 시험 파일 합계(${s.totalBytes}바이트)의 50% 미만만 늘었습니다. 구글 쪽 반영이 늦을 수 있어, 며칠 뒤 한 번 더 확인하면 더 확실합니다."
        UsageVerdict.CHARGED -> "용량 차감됨(무료 아님): 계정 사용량이 시험 파일 합계의 50% 이상 늘었습니다. 포토 백업 화질 설정을 확인하세요."
        UsageVerdict.UNKNOWN -> "사용량 판단 불가(값을 읽지 못했습니다)."
        null -> null
    }

    fun space(s: TrialState): String? = when (s.spaceVerdict) {
        SpaceVerdict.OK -> "포토 확인 방식 정상: 시험 파일 ${s.files.size}장이 모두 포토 확인됨이 되었습니다."
        SpaceVerdict.PARTIAL -> "일부만 지워졌습니다(${s.confirmedCount}/${s.files.size}장). 최근 항목은 안 지울 수 있어 운영 조정이 필요합니다."
        SpaceVerdict.NOT_ERASED -> "최근 항목은 안 지움 — 운영 조정 필요: 1시간 안에 시험 파일이 사라지지 않았습니다."
        null -> null
    }

    const val DATE_LOCATION_GUIDE = "날짜와 위치는 앱이 구글 포토 클라우드를 읽을 수 없어 자동 확인이 안 됩니다. 포토 앱에서 시험 사진을 열어 날짜·위치가 맞는지 직접 확인해 주세요."
}
