package com.pixelrelay.receiver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.pixelrelay.core.GuideFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 픽셀 설정 마법사: Pictures/PixelRelay 폴더를 미리 만들고, 구글 포토가 폴더를 인식하도록 안내용 작은 이미지 1장을 둔다.
 * 안내 이미지는 받기 장부에 올리지 않고(포토 확인 판정 대상 아님) 파일명 접두어 [GuideFile.PREFIX] 로 구분한다.
 */
object ReadyFolder {
    /** 폴더를 만들고 안내 이미지가 없으면 만든다. 이미 있으면 덮어쓰지 않는다. 안내 파일을 돌려준다. */
    fun prepare(dir: File, png: () -> ByteArray): File {
        dir.mkdirs()
        val f = File(dir, GuideFile.NAME)
        if (!f.exists() || f.length() == 0L) f.writeBytes(png())
        return f
    }

    /** "PixelRelay 준비" 글자가 든 작은 PNG */
    fun renderGuidePng(): ByteArray {
        val bmp = Bitmap.createBitmap(480, 240, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(30, 90, 160))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 56f; textAlign = Paint.Align.CENTER }
        c.drawText("PixelRelay 준비", 240f, 130f, p)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    /** 폴더 준비 + 미디어 스캔(포토 목록에 나타나게). 파일 경로를 돌려준다. */
    suspend fun prepareAndScan(ctx: Context, dir: File = PixelStorage.root()): File = withContext(Dispatchers.IO) {
        val f = prepare(dir) { renderGuidePng() }
        runCatching { AndroidMediaIndex(ctx).scan(f.absolutePath, "image/png") }
        f
    }
}
