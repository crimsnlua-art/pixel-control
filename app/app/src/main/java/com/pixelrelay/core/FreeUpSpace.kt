package com.pixelrelay.core

/** 픽셀 자동 공간 확보(접근성)의 기록 키·결과 코드. 값은 받기 장부 옆 meta 에 저장하고 /status 에 실어 보낸다. */
object CleanupKeys {
    const val RESULT = "cleanupResult"
    const val RESULT_AT = "cleanupResultAt"
    const val REQUEST_AT = "cleanupRequestAt"
    const val AUTO_LAST_AT = "autoCleanLastAt"
    /** 마지막 포토 열기 방법(PhotosOpen 이름) */
    const val OPEN_VIA = "cleanupOpenVia"
}

object CleanupResult {
    /** 접근성 서비스가 포토의 공간 확보 버튼을 눌렀음 */
    const val PRESSED = "PRESSED"
    /** 포토를 열었지만 조건에 맞는 버튼을 못 찾음(2분 안) */
    const val NO_BUTTON = "NO_BUTTON"
    /** 접근성 권한이 꺼져 있음 */
    const val NO_PERMISSION = "NO_PERMISSION"
    /** 바로 열 수 없어 "공간 확보 필요 — 누르면 실행" 알림으로 대신함 */
    const val NOTIFIED = "NOTIFIED"
    /** 구글 포토 앱을 열지 못함 */
    const val OPEN_FAILED = "OPEN_FAILED"
    /** 화면에 백업 확인 문구가 없어 누르지 않음 */
    const val NO_BACKUP_TEXT = "NO_BACKUP_TEXT"
    /** 누를 버튼은 찾았지만 performAction(CLICK) 이 실패함 */
    const val CLICK_FAILED = "CLICK_FAILED"
    /** 백업 문제 문구가 보여 누르지 않음. 저장값은 "BACKUP_PROBLEM:문구" */
    const val BACKUP_PROBLEM = "BACKUP_PROBLEM"
    fun problem(reason: String) = "$BACKUP_PROBLEM:$reason"
}

/** 결과 표시 문구(한글). */
object CleanupText {
    fun korean(code: String): String = when (code) {
        CleanupResult.PRESSED -> "눌렀음"
        CleanupResult.NO_BUTTON -> "버튼 못 찾음"
        CleanupResult.NO_PERMISSION -> "권한 없음(픽셀 접근성 꺼짐)"
        CleanupResult.NOTIFIED -> "바로 열 수 없어 픽셀에 알림만 띄움(누르면 실행)"
        CleanupResult.OPEN_FAILED -> "구글 포토를 열지 못함"
        CleanupResult.NO_BACKUP_TEXT -> "백업 확인 문구 없음(누르지 않음)"
        CleanupResult.CLICK_FAILED -> "버튼은 찾았지만 누르기 실패"
        "" -> "아직 없음"
        else -> if (code.startsWith(CleanupResult.BACKUP_PROBLEM + ":")) "백업 문제로 누르지 않음(${code.substringAfter(':')})" else code
    }

    /** "마지막 공간 확보 결과: 눌렀음 (3분 전)" 형태 한 줄. at 이 0 이면 "아직 없음". */
    fun resultLine(code: String, agoText: String): String =
        if (code.isEmpty()) "마지막 공간 확보 결과: 아직 없음" else "마지막 공간 확보 결과: ${korean(code)} ($agoText)"

    const val ACCESSIBILITY_OFF = "자동 공간 확보 꺼짐(픽셀 접근성 권한 필요)"
    const val NOTIFY_TITLE = "공간 확보 필요"
    const val NOTIFY_TEXT = "공간 확보 필요 — 누르면 실행"
}

/**
 * 포토 화면에서 눌러도 되는 버튼 판정(순수 함수).
 * 규칙: 텍스트가 "여유 공간"으로 시작하고 "확보"로 끝나는 클릭 가능한 버튼이 정확히 하나일 때만 그것을 고른다.
 * 구매·삭제·휴지통 같은 다른 버튼은 문구가 달라 걸리지 않고, 만일을 위해 금지 단어가 들어 있으면 한 번 더 거른다.
 */
object CleanupButton {
    const val PHOTOS_PACKAGE = "com.google.android.apps.photos"
    private const val PREFIX = "여유 공간"
    private const val SUFFIX = "확보"
    private const val MAX_LEN = 40
    private val FORBIDDEN = listOf("구매", "삭제", "휴지통", "결제", "구독", "업그레이드", "Google One", "취소", "닫기")

    data class Node(val text: String?, val clickable: Boolean)

    private fun norm(t: String?): String = (t ?: "").replace(Regex("\\s+"), " ").trim()

    /** 금지 단어(구매·삭제·휴지통 등)가 들어 있는가 */
    fun forbidden(text: String?): Boolean { val t = norm(text); return t.isNotEmpty() && FORBIDDEN.any { t.contains(it, ignoreCase = true) } }

    /** 노드 하나: text 와 contentDescription 중 하나가 눌러야 하는 문구이고, 둘 중 어디에도 금지 단어가 없을 때만 후보 */
    fun matchesNode(text: String?, desc: String?): Boolean {
        val ts = listOf(text, desc).map(::norm).filter { it.isNotEmpty() }
        return ts.any { matches(it) } && ts.none { forbidden(it) }
    }

    /** 이 문구가 눌러야 하는 문구인가 */
    fun matches(text: String?): Boolean {
        val t = norm(text)
        if (t.isEmpty() || t.length > MAX_LEN) return false
        if (!t.startsWith(PREFIX) || !t.endsWith(SUFFIX)) return false
        return FORBIDDEN.none { t.contains(it, ignoreCase = true) }
    }

    /** 후보들 중 눌러야 하는 하나의 위치. 없거나 둘 이상이면(애매함) null. */
    fun pick(nodes: List<Node>): Int? =
        nodes.withIndex().filter { (_, n) -> n.clickable && matches(n.text) }.map { it.index }.singleOrNull()
}

/** 접근성 서비스가 "우리 앱이 2분 안에 시작한 경우"에만 누르도록 하는 관문(순수 함수). */
object CleanupGate {
    const val WINDOW_MS = 2 * 60_000L

    /** pressedFor 는 이미 누른 요청의 시작 시각. 같은 요청에서는 두 번 누르지 않는다. */
    fun active(now: Long, requestedAt: Long, pressedFor: Long): Boolean =
        requestedAt > 0 && now >= requestedAt && now - requestedAt <= WINDOW_MS && pressedFor != requestedAt
}

/** 한 요청의 진행 상태 한 장면(요청 식별자로 읽은 것). */
data class CleanupSnap(
    val pressed: Boolean,
    /** 0 = 아직 모름, 1 = 눌림 성공, -1 = performAction 실패 */
    val clickResult: Int,
    val seen: Boolean,
    val blockReason: String?,
    val noBackupText: Boolean,
)

/**
 * 프로세스 안에서 공유하는 요청 상태(접근성 서비스와 흐름이 같은 프로세스에서 돈다).
 * 요청마다 고유한 식별자를 주고, 모든 읽기·쓰기는 식별자로 한정한다. 취소하면 식별자가 바뀌어
 * 이전 요청을 지켜보던 작업이 새 요청의 상태를 읽거나 지우거나 기록하지 못한다.
 */
object CleanupSession {
    private var nextId = 0L
    private var id = 0L
    private var requestedAt = 0L
    private var pressed = false
    private var clickResult = 0
    private var seenAt = 0L
    private var block: String? = null
    private var noBackup = false

    /** 새 요청을 열고 식별자를 돌려준다(이전 요청은 자동으로 무효). */
    @Synchronized fun begin(now: Long): Long {
        id = ++nextId; requestedAt = now; pressed = false; clickResult = 0; seenAt = 0; block = null; noBackup = false
        return id
    }

    /** 활성 요청을 무효화한다(알림 전환·실패·끝). */
    @Synchronized fun cancel() { id = ++nextId; requestedAt = 0; pressed = false; clickResult = 0; seenAt = 0; block = null; noBackup = false }

    /** 그 요청이 아직 현재 요청일 때만 무효화한다. 무효화했으면 true(결과 기록 권한을 얻은 것). */
    @Synchronized fun cancelIf(rid: Long): Boolean {
        if (rid != id || requestedAt <= 0) return false
        cancel()
        return true
    }

    @Synchronized fun isCurrent(rid: Long): Boolean = rid == id && requestedAt > 0

    /** 지금 눌러도 되는 활성 요청의 식별자(2분 안, 아직 안 누름). 없으면 0. */
    @Synchronized fun activeId(now: Long): Long =
        if (CleanupGate.active(now, requestedAt, if (pressed) requestedAt else 0L)) id else 0L

    fun active(now: Long): Boolean = activeId(now) != 0L

    @Synchronized fun noteSeen(rid: Long, now: Long) { if (rid == id && seenAt == 0L) seenAt = now }
    @Synchronized fun noteBlock(rid: Long, reason: String) { if (rid == id) block = reason }
    @Synchronized fun noteNoBackupText(rid: Long) { if (rid == id) noBackup = true }
    @Synchronized fun clearFlags(rid: Long) { if (rid == id) { block = null; noBackup = false } }

    /** 시작·취소·종료·결과 저장·누르기를 한 줄로 세우는 직렬 경계(서비스의 클릭도 이 안에서 한다). */
    val gate = kotlinx.coroutines.sync.Mutex()

    /** 검증한 요청 rid 가 지금도 현재 요청이고 2분 안이며 아직 안 눌렀을 때만 true. 같은 요청에서 한 번만 true. */
    @Synchronized fun claimPress(rid: Long, now: Long): Boolean {
        if (rid == 0L || activeId(now) != rid) return false
        pressed = true
        return true
    }

    @Synchronized fun reportClick(rid: Long, ok: Boolean) { if (rid == id && pressed) clickResult = if (ok) 1 else -1 }

    @Synchronized fun snapshot(rid: Long): CleanupSnap? =
        if (isCurrent(rid)) CleanupSnap(pressed, clickResult, seenAt > 0, block, noBackup) else null

    @Synchronized fun reset() { cancel() }
}

/** 픽셀 남은 공간·포토 확인 대기 수에 따른 자동 실행 조건(순수 함수). */
object AutoCleanPolicy {
    const val LOW_FREE_BYTES = 6 * GB
    const val MIN_GAP_MS = 30 * 60_000L

    /** 남은 공간 6GB 미만 + PUBLISHED(포토 확인 대기) 있음 + 마지막 자동 실행 후 30분 경과. lastAutoAt 0 = 한 번도 안 함. */
    fun shouldRun(freeBytes: Long, published: Int, lastAutoAt: Long, now: Long): Boolean =
        published > 0 && freeBytes < LOW_FREE_BYTES && (lastAutoAt <= 0L || now - lastAutoAt >= MIN_GAP_MS)
}

/** 접근성 서비스가 켜져 있는지 설정 문자열로 판정(순수 함수). */
object AccessibilityCheck {
    /** enabledServices: Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES("a/b:c/d" 형태). */
    fun enabled(enabledServices: String?, pkg: String, cls: String): Boolean {
        if (enabledServices.isNullOrBlank()) return false
        return enabledServices.split(':').map { it.trim() }.any { s ->
            val slash = s.indexOf('/')
            if (slash < 0) return@any false
            val p = s.substring(0, slash)
            val c = s.substring(slash + 1).let { if (it.startsWith(".")) p + it else it }
            p.equals(pkg, true) && c.equals(cls, true)
        }
    }
}

/** 주력 폰 화면의 짧은 안내·결과 문구(순수 함수). */
object HomeText {
    /** "지금 보내기" 결과: route 는 SenderCommands.send 의 반환("LAN" 또는 "드라이브"). */
    fun sendNow(route: String): String = when (route) {
        "LAN" -> "지금 보내기를 시작했습니다. 픽셀에 지금 받기 요청을 보냈습니다(집 와이파이)."
        "드라이브" -> "지금 보내기를 시작했습니다. 픽셀에 지금 받기 요청을 보냈습니다(구글 드라이브 경유, 픽셀이 확인하면 시작)."
        else -> "지금 보내기를 시작했습니다. 픽셀에 지금 받기 요청을 보냈습니다($route)."
    }

    fun freeUp(route: String): String = when (route) {
        "LAN" -> "픽셀에 공간 확보 요청을 보냈습니다(집 와이파이)."
        "드라이브" -> "픽셀에 공간 확보 요청을 보냈습니다(구글 드라이브 경유, 픽셀이 확인하면 실행)."
        else -> "픽셀에 공간 확보 요청을 보냈습니다($route)."
    }

    const val EMPTY_HINT = "새로 찍은 사진만 보냅니다. 예전 사진도 보내려면 설정의 '기존 사진도 보내기'를 켜세요"

    /** 보낼 파일이 0개이고 기존 사진 보내기가 꺼져 있으면 안내 문구, 아니면 null. */
    fun emptyHint(remaining: Int, scanPending: Int, backfill: BackfillMode): String? =
        if (remaining == 0 && scanPending == 0 && backfill == BackfillMode.NONE) EMPTY_HINT else null

    /** 픽셀 남은 공간·업로드 대기(포토 확인 대기) 두 줄. */
    fun pixelSpaceLines(freeBytes: Long, published: Int, freeText: String): List<String> = listOf(
        "픽셀 남은 공간: $freeText" + if (freeBytes < AutoCleanPolicy.LOW_FREE_BYTES) " (부족)" else "",
        "픽셀 업로드 대기 ${published}개(포토 확인 대기)",
    )

    /** 픽셀이 접근성 꺼짐으로 보고했을 때의 경고(없으면 null). */
    fun accessibilityWarning(st: PixelStatus?): String? =
        if (st != null && !st.accessibilityOn) CleanupText.ACCESSIBILITY_OFF else null

    /** 결과 시각(픽셀 시계)을 보내기 폰 시계로 옮긴다: 마지막 소식을 받은 시각 - (보고 시각 - 결과 시각). */
    fun resultAtOnLocalClock(st: PixelStatus, receivedAtLocal: Long): Long =
        if (st.cleanupResultAt <= 0) 0L else receivedAtLocal - maxOf(0L, st.reportedAt - st.cleanupResultAt)
}

/** 화면 문구로 "눌러도 안전한가" 판정(순수 함수): 백업 확인 문구가 있고 백업 문제 문구가 없을 때만 SAFE. */
object CleanupScreenCheck {
    sealed class Verdict {
        object Safe : Verdict()
        /** 백업 확인 문구("이미 안전하게 백업되었습니다"/"백업되었습니다")가 화면에 없음 */
        object NoBackupText : Verdict()
        /** 백업 문제 문구가 보임(reason = 보인 문구) */
        data class Problem(val reason: String) : Verdict()
    }

    private val OK_PHRASES = listOf("이미 안전하게 백업되었습니다", "백업되었습니다")
    private val PROBLEM_PHRASES = listOf("백업이 중지되었습니다", "백업 오류", "백업되지 않음", "백업되지 않았습니다", "백업 중지됨", "백업 일시중지", "백업 실패")

    private fun norm(t: String) = t.replace(Regex("\\s+"), " ").trim()

    /** 문제 문구가 하나라도 있으면 Problem(첫 번째), 없고 확인 문구가 있으면 Safe, 둘 다 없으면 NoBackupText. */
    fun verdict(texts: List<String?>): Verdict = verdict(texts, texts)

    /** 안전 근거(확인 문구)는 보이는 문구만, 문제 문구는 보이든 안 보이든 전체에서 찾는다. */
    fun verdict(visibleTexts: List<String?>, allTexts: List<String?>): Verdict {
        val all = allTexts.filterNotNull().map(::norm).filter { it.isNotEmpty() }
        val vis = visibleTexts.filterNotNull().map(::norm).filter { it.isNotEmpty() }
        for (p in PROBLEM_PHRASES) if (all.any { it.contains(p) }) return Verdict.Problem(p)
        return if (OK_PHRASES.any { ok -> vis.any { it.contains(ok) } }) Verdict.Safe else Verdict.NoBackupText
    }
}

/** 포토를 연 방법. LINK = 공간 확보 화면 직접 링크, LAUNCHER = 링크가 안 열려 일반 실행 화면으로 대신함. */
enum class PhotosOpen { LINK, LAUNCHER, FAILED }

/** 포토 열기 방법 표시(없으면 null). 직접 링크로 열린 정상 경우는 말하지 않는다. */
object OpenViaText {
    fun note(via: String): String? = when (via) {
        PhotosOpen.LAUNCHER.name -> "포토 공간 확보 링크가 안 열려 기본 화면으로 열었습니다"
        else -> null
    }
}

/** 접근성 화면 한 노드의 사본(순수 판정용). parent = 부모 위치(-1 = 뿌리). */
data class SnapNode(
    val id: Int,
    val parent: Int,
    val text: String?,
    val desc: String?,
    val clickable: Boolean,
    val enabled: Boolean,
    val visible: Boolean,
)

/** complete = false 면 노드 수 제한·가져오기 실패로 화면 일부만 본 것이다. */
data class ScreenSnapshot(val nodes: List<SnapNode>, val complete: Boolean)

/**
 * 화면 전체 사본으로 "무엇을 눌러도 되는가"를 정하는 순수 함수.
 * - 화면을 끝까지 못 봤으면(complete=false) 누르지 않는다.
 * - 안전 근거(백업 확인 문구)는 보이는 노드의 문구만 쓰고, 문제 문구는 보이든 안 보이든 하나라도 있으면 막는다.
 * - 후보는 보이는 노드이며 text·contentDescription 모두 금지 단어가 없어야 한다.
 * - 실제로 누를 노드(후보 자신 또는 가까운 부모)는 보이고 활성이고 클릭 가능해야 하고, 그 노드 아래 모든 문구에 금지 단어가 없어야 한다.
 *   검증 안 된 부모로 올라가 누르는 일은 없다. 서로 다른 누를 대상이 둘 이상이거나 하나라도 검증 실패면 아무것도 누르지 않는다.
 */
object CleanupPlanner {
    const val MAX_PARENT_HOPS = 3

    enum class Why { INCOMPLETE, PROBLEM, NO_BUTTON, NO_BACKUP_TEXT }

    sealed class Decision {
        data class Press(val nodeId: Int) : Decision()
        data class Skip(val why: Why, val detail: String? = null) : Decision()
    }

    private fun texts(n: SnapNode) = listOfNotNull(n.text, n.desc)

    fun decide(s: ScreenSnapshot): Decision {
        if (!s.complete) return Decision.Skip(Why.INCOMPLETE)
        val byId = s.nodes.associateBy { it.id }
        val children = HashMap<Int, MutableList<Int>>()
        for (n in s.nodes) if (n.parent >= 0) children.getOrPut(n.parent) { ArrayList() } += n.id

        val allTexts = s.nodes.flatMap { texts(it) }
        val visTexts = s.nodes.filter { it.visible }.flatMap { texts(it) }
        val verdict = CleanupScreenCheck.verdict(visTexts, allTexts)
        if (verdict is CleanupScreenCheck.Verdict.Problem) return Decision.Skip(Why.PROBLEM, verdict.reason)

        // 후보 -> 누를 대상
        val targets = LinkedHashSet<Int>()
        for (n in s.nodes) {
            if (!n.visible || !CleanupButton.matchesNode(n.text, n.desc)) continue
            var cur: SnapNode? = n
            var hops = 0
            var found: Int? = null
            while (cur != null && hops <= MAX_PARENT_HOPS) {
                if (cur.clickable && cur.enabled && cur.visible) { found = cur.id; break }
                cur = byId[cur.parent]
                hops++
            }
            if (found != null) targets += found
        }
        if (targets.size != 1) return Decision.Skip(Why.NO_BUTTON)
        val target = targets.first()
        // 대상 단위 검증: 대상과 그 아래 모든 문구에 금지 단어가 없어야 한다
        val stack = ArrayDeque<Int>().also { it.add(target) }
        while (stack.isNotEmpty()) {
            val n = byId[stack.removeLast()] ?: continue
            if (texts(n).any { CleanupButton.forbidden(it) }) return Decision.Skip(Why.NO_BUTTON)
            children[n.id]?.let { stack.addAll(it) }
        }
        if (verdict == CleanupScreenCheck.Verdict.NoBackupText) return Decision.Skip(Why.NO_BACKUP_TEXT)
        return Decision.Press(target)
    }
}

/** 드라이브 사용 여부를 아직 읽지 못했으면(null) 사용 중이 아닌 것으로 본다(경고가 잠깐 깜빡이는 것을 막는다). */
object DriveKnown {
    fun used(loaded: Boolean?): Boolean = loaded == true
}
