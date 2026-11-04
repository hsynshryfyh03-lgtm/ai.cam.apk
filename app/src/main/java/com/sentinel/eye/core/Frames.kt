package com.sentinel.eye.core

/**
 * تحويل YUV_420_888 إلى ARGB مع تدوير وانعكاس اختياري وتصغير بالتخطّي (step).
 * المصفوفات تُنسخ مسبقاً من مخازن الصورة. التحويل مفصول إلى جداول أعمدة وصفوف لتسريع الحلقة الداخلية.
 */
object FrameConverter {

    fun outWidth(srcW: Int, srcH: Int, rotation: Int, step: Int = 1): Int =
        if (rotation == 90 || rotation == 270) srcH / step else srcW / step

    fun outHeight(srcW: Int, srcH: Int, rotation: Int, step: Int = 1): Int =
        if (rotation == 90 || rotation == 270) srcW / step else srcH / step

    private var colY = IntArray(0)
    private var rowY = IntArray(0)
    private var colUV = IntArray(0)
    private var rowUV = IntArray(0)

    @Synchronized
    fun convert(
        y: ByteArray, yRowStride: Int, yPixStride: Int,
        u: ByteArray, v: ByteArray, uvRowStride: Int, uvPixStride: Int,
        srcW: Int, srcH: Int, rotation: Int, mirror: Boolean, out: IntArray, step: Int = 1
    ) {
        val vw = srcW / step // أبعاد المصدر الافتراضية بعد التصغير
        val vh = srcH / step
        val outW = outWidth(srcW, srcH, rotation, step)
        val outH = outHeight(srcW, srcH, rotation, step)
        if (colY.size < outW) { colY = IntArray(outW); colUV = IntArray(outW) }
        if (rowY.size < outH) { rowY = IntArray(outH); rowUV = IntArray(outH) }
        // كل إحداثي مصدر (sx, sy) يعتمد على متغير واحد فقط من (ox, oy) في الدورانات الأربعة
        for (ox in 0 until outW) {
            val o = if (mirror) outW - 1 - ox else ox
            var sx = 0
            var sy = 0
            when (rotation) {
                90 -> sy = vh - 1 - o
                180 -> sx = vw - 1 - o
                270 -> sy = o
                else -> sx = o
            }
            // نحتاج sx أو sy فقط من العمود؛ الآخر يأتي من الصف
            colY[ox] = if (rotation == 90 || rotation == 270) (sy * step) * yRowStride else (sx * step) * yPixStride
            colUV[ox] = if (rotation == 90 || rotation == 270) ((sy * step) shr 1) * uvRowStride else ((sx * step) shr 1) * uvPixStride
        }
        for (oy in 0 until outH) {
            var sx = 0
            var sy = 0
            when (rotation) {
                90 -> sx = oy
                180 -> sy = vh - 1 - oy
                270 -> sx = vw - 1 - oy
                else -> sy = oy
            }
            rowY[oy] = if (rotation == 90 || rotation == 270) (sx * step) * yPixStride else (sy * step) * yRowStride
            rowUV[oy] = if (rotation == 90 || rotation == 270) ((sx * step) shr 1) * uvPixStride else ((sy * step) shr 1) * uvRowStride
        }
        var idx = 0
        for (oy in 0 until outH) {
            val ry = rowY[oy]
            val ruv = rowUV[oy]
            for (ox in 0 until outW) {
                val yy = y[colY[ox] + ry].toInt() and 0xFF
                val uvIdx = colUV[ox] + ruv
                val uu = (u[uvIdx].toInt() and 0xFF) - 128
                val vv = (v[uvIdx].toInt() and 0xFF) - 128
                var r = yy + ((359 * vv) shr 8)
                var g = yy - ((88 * uu + 183 * vv) shr 8)
                var b = yy + ((454 * uu) shr 8)
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255
                out[idx++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }
}

/** بوابة حركة خفيفة: تقارن شبكة صغيرة من اللُّمَع بين إطارين متتاليين. */
class MotionGate(private val gw: Int = 32, private val gh: Int = 18) {
    private var prev: IntArray? = null
    private val cur = IntArray(gw * gh)

    fun reset() { prev = null }

    /** تُرجع نسبة الخلايا المتغيرة (0..1)، وأول استدعاء يُرجع 1. */
    fun changedFraction(y: ByteArray, rowStride: Int, pixStride: Int, w: Int, h: Int, pixelThreshold: Int): Float {
        for (gy in 0 until gh) {
            val y0 = ((gy + 0.3f) * h / gh).toInt().coerceIn(0, h - 1)
            val y1 = ((gy + 0.7f) * h / gh).toInt().coerceIn(0, h - 1)
            for (gx in 0 until gw) {
                val x0 = ((gx + 0.3f) * w / gw).toInt().coerceIn(0, w - 1)
                val x1 = ((gx + 0.7f) * w / gw).toInt().coerceIn(0, w - 1)
                val s = (y[y0 * rowStride + x0 * pixStride].toInt() and 0xFF) +
                    (y[y0 * rowStride + x1 * pixStride].toInt() and 0xFF) +
                    (y[y1 * rowStride + x0 * pixStride].toInt() and 0xFF) +
                    (y[y1 * rowStride + x1 * pixStride].toInt() and 0xFF)
                cur[gy * gw + gx] = s shr 2
            }
        }
        val p = prev
        if (p == null) {
            prev = cur.copyOf()
            return 1f
        }
        var changed = 0
        for (i in cur.indices) {
            val d = cur[i] - p[i]
            if (d > pixelThreshold || d < -pixelThreshold) changed++
            p[i] = cur[i]
        }
        return changed.toFloat() / cur.size
    }
}
