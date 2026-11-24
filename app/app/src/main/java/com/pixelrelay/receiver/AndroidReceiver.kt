package com.pixelrelay.receiver

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 픽셀의 MediaStore 연동(scanFile 로 알림, 경로·id 조회). */
class AndroidMediaIndex(private val ctx: Context) : MediaIndex {
    override fun scan(path: String, mime: String): Long? {
        val latch = CountDownLatch(1)
        var uri: Uri? = null
        MediaScannerConnection.scanFile(ctx, arrayOf(path), arrayOf(mime)) { _, u -> uri = u; latch.countDown() }
        latch.await(10, TimeUnit.SECONDS)
        return uri?.let { runCatching { ContentUris.parseId(it) }.getOrNull() }
    }

    @Suppress("DEPRECATION")
    override fun idForPath(path: String): Long? {
        ctx.contentResolver.query(
            MediaStore.Files.getContentUri("external"), arrayOf(MediaStore.Files.FileColumns._ID),
            "${MediaStore.Files.FileColumns.DATA} = ?", arrayOf(path), null,
        )?.use { return if (it.moveToFirst()) it.getLong(0) else null }
        return null
    }

    override fun hasId(id: Long): Boolean {
        ctx.contentResolver.query(
            ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id),
            arrayOf(MediaStore.Files.FileColumns._ID), null, null, null,
        )?.use { return it.moveToFirst() }
        return false
    }
}

object PixelStorage {
    /** Pictures/PixelRelay (legacy 외부 저장소 직접 접근: API 29 에서 requestLegacyExternalStorage) */
    @Suppress("DEPRECATION")
    fun root(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "PixelRelay")

    fun sink(ctx: Context): FileMediaSink {
        val root = root().also { it.mkdirs() }
        return FileMediaSink(
            root = root,
            free = { runCatching { StatFs(root.absolutePath).availableBytes }.getOrDefault(0L) },
            total = { runCatching { StatFs(root.absolutePath).totalBytes }.getOrDefault(0L) },
            index = AndroidMediaIndex(ctx),
        )
    }
}

class AndroidDeviceProbe(private val ctx: Context) : DeviceProbe {
    private fun intent(): Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    override fun batteryPct(): Int {
        val i = intent() ?: return 0
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (level < 0) 0 else level * 100 / scale
    }

    override fun charging(): Boolean {
        val st = intent()?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
    }

    override fun tempC(): Double = (intent()?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
}
