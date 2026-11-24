package com.pixelrelay.core

/** "기존 사진도 보내기" 선택지(설정 화면). */
enum class BackfillMode { NONE, ALL, SINCE }

/** 주력 폰(보내기) 설정. 영구 저장은 DataStore(ui 쪽 SettingsStore), 로직은 이 값 객체만 본다. */
data class SenderSettings(
    val watchFolders: List<String> = listOf("DCIM/Camera"),
    val excludeFolders: List<String> = listOf("Screenshots", "KakaoTalk", "Download", "Telegram"),
    /** 사진을 모바일 데이터로 드라이브에 올려도 되는가 */
    val photoOverMobile: Boolean = true,
    /** 영상은 와이파이에서만(기본 꺼짐) */
    val videoOverMobile: Boolean = false,
    val dailyLimitBytes: Long = 2 * GB,
    /** 역할을 정한 시각(초). "기존 사진도 보내기"를 안 고르면 이 시각 이후 추가된 파일만 보낸다. */
    val roleSetAtSec: Long = 0,
    /** 기존 사진 보내기 기준 시각(초). null = 안 보냄(새로 찍은 것만), 0 = 전부, 그 밖 = 이 시각 이후. */
    val sendFromSec: Long? = null,
) {
    /** 실제 스캔 시작점: 사용자가 고른 값, 안 보냄이면 역할 지정 시각. */
    val scanFromSec: Long get() = sendFromSec ?: roleSetAtSec

    val backfillMode: BackfillMode
        get() = when {
            sendFromSec == null -> BackfillMode.NONE
            sendFromSec == 0L -> BackfillMode.ALL
            else -> BackfillMode.SINCE
        }

    companion object {
        const val MAX_PHOTO_BYTES = 200L * 1024 * 1024
        const val MAX_VIDEO_BYTES = 10L * 1024 * 1024 * 1024
        const val STABLE_MS = 60_000L

        /** 수정 시각이 이만큼(초) 이상 과거인 파일은 60초 안정 조건을 기다리지 않고 바로 안정으로 본다. */
        const val OLD_FILE_SEC = 600L

        /** 설정 화면 선택 -> 저장값. */
        fun sendFromFor(mode: BackfillMode, sinceSec: Long): Long? = when (mode) {
            BackfillMode.NONE -> null
            BackfillMode.ALL -> 0L
            BackfillMode.SINCE -> sinceSec
        }
    }
}

/** 폴더 한 곳에 대한 판정. */
enum class FolderVerdict { OUT, EXCLUDED, SEND }

/** 폴더 규칙(순수 함수). relPath 는 "DCIM/Camera/" 같은 MediaStore RELATIVE_PATH. */
object FolderPolicy {
    private fun norm(p: String) = p.replace('\\', '/').trim('/').lowercase()
    private fun comps(p: String) = norm(p).split('/').filter { it.isNotEmpty() }

    /** 감시 폴더 중 relPath 를 덮는 것 가운데 가장 깊은 것의 깊이(없으면 -1). 하위 폴더 포함. */
    private fun watchDepth(relPath: String, watch: List<String>): Int {
        val r = comps(relPath)
        var best = -1
        for (w in watch) {
            val c = comps(w)
            if (c.isEmpty() || c.size > r.size) continue
            if (r.subList(0, c.size) == c) best = maxOf(best, c.size)
        }
        return best
    }

    /**
     * 제외 폴더 중 relPath 의 경로 조각 순서와 맞는 것의 끝 깊이(없으면 -1).
     * 이름 하나("Screenshots")는 어느 깊이에서든 그 이름의 폴더에 맞고, 경로("DCIM/Camera/Sub")는 그 순서로 이어진 폴더에 맞는다.
     */
    private fun excludeDepth(relPath: String, exclude: List<String>): Int {
        val r = comps(relPath)
        var best = -1
        for (e in exclude) {
            val c = comps(e)
            if (c.isEmpty() || c.size > r.size) continue
            for (start in 0..(r.size - c.size)) {
                if (r.subList(start, start + c.size) == c) best = maxOf(best, start + c.size)
            }
        }
        return best
    }

    fun inWatch(relPath: String, watch: List<String>): Boolean = watchDepth(relPath, watch) >= 0

    /** 제외 규칙에 걸리는가(감시 폴더와의 깊이 비교 없이). */
    fun excluded(relPath: String, exclude: List<String>): Boolean = excludeDepth(relPath, exclude) >= 0

    /**
     * 최종 판정. 감시 밖 = OUT. 감시 안이라도 제외 폴더가 감시 폴더보다 같거나 깊으면 EXCLUDED.
     * 제외 폴더가 감시 폴더보다 얕으면(더 구체적인 감시 폴더가 안에 있는 것) 감시가 이긴다.
     */
    fun verdict(relPath: String, watch: List<String>, exclude: List<String>): FolderVerdict {
        val w = watchDepth(relPath, watch)
        if (w < 0) return FolderVerdict.OUT
        return if (excludeDepth(relPath, exclude) >= w) FolderVerdict.EXCLUDED else FolderVerdict.SEND
    }
}

/** 설정 화면 폴더 목록 한 줄. path 는 "DCIM/Camera" 처럼 양끝 슬래시 없는 표시용 경로. */
data class FolderRow(val path: String, val count: Int, val checked: Boolean)

/** 폴더 체크 목록 <-> 감시·제외 규칙 변환(순수 함수). */
object FolderSelection {
    fun display(rel: String): String = rel.replace('\\', '/').trim('/')

    /** MediaStore 폴더별 파일 수 + 저장된 규칙으로 목록을 만든다. 저장된 감시 폴더가 목록에 없으면 0개로 덧붙인다. */
    fun rows(counts: Map<String, Int>, watch: List<String>, exclude: List<String>): List<FolderRow> {
        val merged = LinkedHashMap<String, Int>()
        for ((k, v) in counts) {
            val d = display(k)
            if (d.isEmpty()) continue
            merged[d] = (merged[d] ?: 0) + v
        }
        for (w in watch) {
            val d = display(w)
            if (d.isNotEmpty() && merged.keys.none { it.equals(d, ignoreCase = true) }) merged[d] = 0
        }
        return merged.map { (p, n) -> FolderRow(p, n, FolderPolicy.verdict(p, watch, exclude) == FolderVerdict.SEND) }
            .sortedWith(compareByDescending<FolderRow> { it.checked }.thenByDescending { it.count }.thenBy { it.path.lowercase() })
    }

    /**
     * 체크한 폴더 -> (감시 폴더, 제외 폴더).
     * 감시 = 체크한 폴더 전부. 제외 = 체크하지 않았지만 체크한 폴더의 하위인 폴더(하위 폴더도 감시에 포함되므로 명시적으로 뺀다).
     * 기존 제외 규칙(Screenshots·KakaoTalk 같은 이름 기반 포함)은 지금 폴더 목록에 없어도 유지한다(나중에 그 폴더가 생겨도 계속 제외).
     * 단 사용자가 명시적으로 체크한 폴더에 걸리는 기존 규칙만 뺀다(그 폴더를 보내겠다는 뜻).
     */
    fun toRules(
        all: Collection<String>,
        checked: Set<String>,
        existingExclude: List<String> = emptyList(),
        /** 주면 "기존 규칙 기준 변경분만 적용" 방식(아래 diffRules). 안 주면 체크 목록 전체로 규칙을 새로 만든다. */
        existingWatch: List<String>? = null,
    ): Pair<List<String>, List<String>> {
        if (existingWatch != null) return diffRules(all, checked, existingWatch, existingExclude)
        val ck = checked.map { display(it) }.filter { it.isNotEmpty() }.distinct().sorted()
        val ckNorm = ck.map { it.lowercase() }
        val exclude = all.map { display(it) }.filter { it.isNotEmpty() && it !in ck }.distinct().filter { u ->
            val un = u.lowercase()
            ckNorm.any { c -> un.startsWith("$c/") }
        }.sorted()
        val kept = existingExclude.map { it.trim() }.filter { e ->
            e.isNotEmpty() && ck.none { c -> FolderPolicy.excluded(c, listOf(e)) }
        }
        val merged = (exclude + kept).distinctBy { it.lowercase().trim('/') }
        return ck to merged
    }

    /**
     * 변경분만 적용: 기존 규칙으로 이미 "보냄"인 행(자동 체크)과 사용자가 이번에 바꾼 행을 구분한다.
     * 바꾼 게 없으면 기존 감시·제외 규칙을 그대로 돌려준다. 새로 체크한 폴더는 그 경로만 감시에 추가하고(더 구체적인 감시가 이긴다),
     * 새로 체크 해제한 폴더는 그 경로만 제외에 추가한다. 이름 기반 제외 규칙은 지우지 않는다
     * (그 폴더를 직접 체크했는데도 같은 깊이의 이름 규칙에 막힐 때만 그 규칙을 뺀다).
     */
    fun diffRules(all: Collection<String>, checked: Set<String>, existingWatch: List<String>, existingExclude: List<String>): Pair<List<String>, List<String>> {
        val paths = all.map { display(it) }.filter { it.isNotEmpty() }.distinct().sortedBy { it.count { c -> c == '/' } }
        val want = checked.map { display(it) }.toSet()
        val initial = paths.filter { FolderPolicy.verdict(it, existingWatch, existingExclude) == FolderVerdict.SEND }.toSet()
        if (paths.all { (it in want) == (it in initial) }) return existingWatch to existingExclude

        val watch = existingWatch.toMutableList()
        val exclude = existingExclude.toMutableList()
        for (p in paths) if (p !in want && FolderPolicy.verdict(p, watch, exclude) == FolderVerdict.SEND) exclude += p
        for (p in paths) if (p in want && FolderPolicy.verdict(p, watch, exclude) != FolderVerdict.SEND) watch += p
        for (p in paths) if (p in want && FolderPolicy.verdict(p, watch, exclude) != FolderVerdict.SEND) {
            exclude.removeAll { e -> FolderPolicy.excluded(p, listOf(e)) }
        }
        fun List<String>.dedup() = distinctBy { it.lowercase().trim('/') }
        return watch.dedup() to exclude.dedup()
    }
}

/** "이 날짜 이후" 날짜 <-> 초 단위 기준 시각 변환(순수 함수). */
object BackfillDates {
    /** 날짜 선택기가 주는 UTC 자정 밀리초 -> 그 날짜의 기기 시간대 자정(초) */
    fun startOfDaySec(utcMidnightMs: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long {
        val d = java.time.Instant.ofEpochMilli(utcMidnightMs).atZone(java.time.ZoneOffset.UTC).toLocalDate()
        return d.atStartOfDay(zone).toEpochSecond()
    }

    /** 저장된 기준 시각(초) -> 날짜 선택기 초기값(UTC 자정 밀리초) */
    fun toPickerMs(sec: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long {
        val d = java.time.Instant.ofEpochSecond(sec).atZone(zone).toLocalDate()
        return d.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
    }

    fun label(sec: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String =
        java.time.Instant.ofEpochSecond(sec).atZone(zone).toLocalDate().toString()
}

/** 날짜 창 상태 전이(순수): 열 때마다 직전 모드를 기록하고, 취소·바깥 터치·뒤로 가기는 항상 그 모드로 복원한다. */
class DateDialogState private constructor(val before: BackfillMode) {
    companion object {
        fun open(current: BackfillMode) = DateDialogState(current)
    }

    fun cancel(): BackfillMode = before
    fun confirm(): BackfillMode = BackfillMode.SINCE
}
