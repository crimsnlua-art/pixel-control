package com.pixelrelay.sender

import com.pixelrelay.core.Clock
import com.pixelrelay.core.LedgerItem
import com.pixelrelay.core.MetaStore
import com.pixelrelay.core.PixelStatus
import com.pixelrelay.core.Reason
import com.pixelrelay.core.SendState
import com.pixelrelay.core.SenderLedger
import org.json.JSONObject

/**
 * 픽셀의 변화 기록(seq)을 주력 폰 장부에 반영한다 (설계서 7절 상태 보고).
 * 받은 seq 에 빈틈이 있으면 표시해 두었다가 다음 LAN 연결 때 /ledger?since= 로 전부 맞춘다.
 */
class LedgerSync(private val ledger: SenderLedger, private val meta: MetaStore) {
    companion object {
        const val KEY_LAST = "syncLastSeq"
        const val KEY_NEEDS_FULL = "syncNeedsFull"
    }

    suspend fun lastSeq(): Long = meta.getLong(KEY_LAST) ?: 0L
    suspend fun needsFull(): Boolean = (meta.getLong(KEY_NEEDS_FULL) ?: 0L) == 1L

    /** 변화 목록을 반영한다. 빈틈이 있으면 true. */
    suspend fun applyChanges(items: List<LedgerItem>): Boolean {
        var last = lastSeq()
        var gap = false
        for (it in items.sortedBy { x -> x.seq }) {
            if (it.seq <= last) continue
            applyState(it)
            if (!gap && it.seq == last + 1) last = it.seq else gap = true
        }
        meta.putLong(KEY_LAST, last)
        if (gap) meta.putLong(KEY_NEEDS_FULL, 1)
        return gap
    }

    /** 상태 객체의 seq 가 내가 아는 것보다 앞서는데 변화 목록이 못 메웠으면 빈틈으로 본다. */
    suspend fun noteRemoteSeq(seq: Long) {
        if (seq > lastSeq()) meta.putLong(KEY_NEEDS_FULL, 1)
    }

    /** LAN 연결 때: /ledger 를 끝까지 따라가 빈틈 없이 맞춘다. */
    suspend fun syncViaLan(api: PixelApi) {
        while (true) {
            val last = lastSeq()
            val page = api.ledger(last, 500)
            if (page.items.isEmpty()) break
            applyChanges(page.items)
            if (!page.hasMore) break
            if (lastSeq() == last) break // 진전 없음(안전장치)
        }
        val remote = runCatching { api.status().seq }.getOrNull()
        if (remote == null || lastSeq() >= remote) meta.putLong(KEY_NEEDS_FULL, 0)
    }

    private suspend fun applyState(it: LedgerItem) {
        val e = ledger.get(it.sha) ?: return
        when (it.state) {
            "PUBLISHED" -> ledger.tryTransition(e.sha, SendState.RECEIVED)
            "PHOTOS_CONFIRMED", "CLEANED" -> ledger.tryTransition(e.sha, SendState.PHOTOS_CONFIRMED)
            "VANISHED_UNKNOWN" -> ledger.tryTransition(e.sha, SendState.VANISHED_UNKNOWN)
            // 픽셀이 다시 받기를 허용했음이 확인됨 -> RESEND_PENDING 일 때만 WAITING 으로
            "RESEND_REQUESTED" -> ledger.resendConfirmed(e.sha)
            "SKIPPED" -> ledger.tryTransition(e.sha, SendState.SKIPPED, Reason.TOO_LARGE)
            else -> {}
        }
    }
}

/** 마지막으로 받은 픽셀 상태(LAN 또는 드라이브 경유)를 기억한다. */
class RemoteStatusStore(private val meta: MetaStore, private val clock: Clock) {
    data class Saved(val status: PixelStatus, val via: String, val at: Long)

    suspend fun save(s: PixelStatus, via: String) {
        // 더 최근 보고를 덮어쓰지 않도록 reportedAt 비교
        val cur = load(via)
        if (cur != null && cur.status.reportedAt > s.reportedAt) return
        meta.putString("pixelStatus:$via", JSONObject().put("status", s.toJson()).put("at", clock.nowMs()).toString())
    }

    suspend fun load(via: String): Saved? {
        val raw = meta.getString("pixelStatus:$via") ?: return null
        return runCatching {
            val o = JSONObject(raw)
            Saved(PixelStatus.fromJson(o.getJSONObject("status")), via, o.optLong("at"))
        }.getOrNull()
    }

    /** LAN 과 드라이브 중 더 최근 것 */
    suspend fun latest(): Saved? = listOfNotNull(load("lan"), load("drive")).maxByOrNull { it.at }
}
