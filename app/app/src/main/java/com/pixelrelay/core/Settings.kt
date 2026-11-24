package com.pixelrelay.core

/** 주력 폰(보내기) 설정. 영구 저장은 DataStore(ui 쪽 SettingsStore), 로직은 이 값 객체만 본다. */
data class SenderSettings(
    val watchFolders: List<String> = listOf("DCIM/Camera"),
    val excludeFolders: List<String> = listOf("Screenshots", "KakaoTalk", "Download", "Telegram"),
    /** 사진을 모바일 데이터로 드라이브에 올려도 되는가 */
    val photoOverMobile: Boolean = true,
    /** 영상은 와이파이에서만(기본 꺼짐) */
    val videoOverMobile: Boolean = false,
    val dailyLimitBytes: Long = 2 * GB,
    /** 역할을 정한 시각(초). 이 시각 이후 추가된 파일만 보낸다. */
    val roleSetAtSec: Long = 0,
) {
    companion object {
        const val MAX_PHOTO_BYTES = 200L * 1024 * 1024
        const val MAX_VIDEO_BYTES = 10L * 1024 * 1024 * 1024
        const val STABLE_MS = 60_000L
    }
}

/** 폴더 규칙(순수 함수). relPath 는 "DCIM/Camera/" 같은 MediaStore RELATIVE_PATH. */
object FolderPolicy {
    private fun norm(p: String) = p.replace('\\', '/').trim('/').lowercase()

    fun inWatch(relPath: String, watch: List<String>): Boolean {
        val r = norm(relPath)
        return watch.any { w -> val n = norm(w); r == n || r.startsWith("$n/") }
    }

    fun excluded(relPath: String, exclude: List<String>): Boolean {
        val parts = norm(relPath).split('/')
        return exclude.any { e -> val n = norm(e); n.isNotEmpty() && (parts.contains(n) || norm(relPath).contains("$n/") || norm(relPath).endsWith(n)) }
    }
}
