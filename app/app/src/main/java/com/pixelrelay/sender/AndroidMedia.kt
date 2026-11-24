package com.pixelrelay.sender

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * 주력 폰 MediaStore 읽기(설계서 4절).
 * 원본은 MediaStore.setRequireOriginal(uri) 로 연 스트림만 쓴다(GPS 가림 방지, ACCESS_MEDIA_LOCATION 필요).
 */
class MediaStoreSource(private val ctx: Context) : MediaSource {
    private val resolver: ContentResolver get() = ctx.contentResolver

    private fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    override fun hasFullMediaAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO)
        else granted(Manifest.permission.READ_EXTERNAL_STORAGE)

    override fun hasLocationAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 29) granted(Manifest.permission.ACCESS_MEDIA_LOCATION) else true

    override fun list(addedSinceSec: Long): List<MediaItem> = query(
        "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?,?) AND ${MediaStore.Files.FileColumns.DATE_ADDED} >= ?",
        arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            addedSinceSec.toString(),
        ),
    )

    override fun stat(id: Long): MediaItem? = query("${MediaStore.Files.FileColumns._ID} = ?", arrayOf(id.toString())).firstOrNull()

    private fun query(sel: String, args: Array<String>): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        val base = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val proj = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.IS_PENDING,
        )
        val cursor = if (Build.VERSION.SDK_INT >= 30) {
            val q = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, sel)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                // 기록 중(IS_PENDING=1) 파일도 보여야 안정 조건으로 거를 수 있다
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }
            resolver.query(base, proj, q, null)
        } else {
            resolver.query(MediaStore.setIncludePending(base), proj, sel, args, null)
        } ?: return out
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val mediaType = c.getInt(6)
                val video = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val tbase = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val modSec = c.getLong(3)
                val taken = c.getLong(8).takeIf { it > 0 } ?: (modSec * 1000)
                out += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(tbase, id).toString(),
                    name = c.getString(1) ?: "media_$id",
                    size = c.getLong(2),
                    dateModifiedSec = modSec,
                    dateAddedSec = c.getLong(4),
                    takenAtMs = taken,
                    mime = c.getString(5) ?: (if (video) "video/mp4" else "image/jpeg"),
                    relPath = c.getString(7) ?: "",
                    isVideo = video,
                    isPending = c.getInt(9) == 1,
                )
            }
        }
        return out
    }

    override fun openOriginal(uri: String): InputStream? = try {
        val u = MediaStore.setRequireOriginal(Uri.parse(uri))
        resolver.openInputStream(u)
    } catch (e: FileNotFoundException) {
        null
    }
}
