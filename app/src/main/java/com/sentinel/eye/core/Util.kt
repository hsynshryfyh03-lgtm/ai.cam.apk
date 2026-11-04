package com.sentinel.eye.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.StatFs
import com.sentinel.eye.detect.Detection
import java.io.File

object ImageUtil {
    fun bitmapFromArgb(argb: IntArray, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(argb, 0, w, 0, 0, w, h)
        return bmp
    }

    /** يرسم مربعات الكشف على نسخة قابلة للتعديل من الصورة. */
    fun drawBoxes(bmp: Bitmap, dets: List<Detection>) {
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.STROKE
        p.strokeWidth = (bmp.width / 200f).coerceAtLeast(2f)
        p.color = Color.rgb(52, 199, 89)
        for (d in dets) {
            c.drawRoundRect(
                d.left * bmp.width, d.top * bmp.height, d.right * bmp.width, d.bottom * bmp.height,
                8f, 8f, p
            )
        }
    }
}

/** مواقع التخزين الخاصة بالتطبيق. */
object Storage {
    lateinit var events: EventStore
        private set
    lateinit var dir: File
        private set

    @Synchronized
    fun init(ctx: Context) {
        if (::events.isInitialized) return
        val base = ctx.applicationContext.getExternalFilesDir("events") ?: File(ctx.applicationContext.filesDir, "events")
        base.mkdirs()
        dir = base
        events = EventStore(base)
    }

    fun freeBytes(): Long = try { StatFs(dir.path).availableBytes } catch (_: Throwable) { Long.MAX_VALUE }
}
