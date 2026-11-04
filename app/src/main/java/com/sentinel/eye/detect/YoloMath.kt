package com.sentinel.eye.detect

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** نتيجة كشف واحدة بإحداثيات مطبّعة (0..1) نسبةً إلى الإطار المعتدل (Upright). */
data class Detection(
    val classId: Int,
    val label: String,
    val score: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/** إطار تحليل بصيغة ARGB بعد التدوير ليصبح معتدلاً. */
class Frame(val argb: IntArray, val width: Int, val height: Int, val timeMs: Long)

interface Detector : AutoCloseable {
    val name: String
    fun detect(frame: Frame, minScore: Float, allowed: IntArray): List<Detection>
}

object Coco {
    val names: Array<String> = arrayOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
        "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog",
        "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella",
        "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball", "kite",
        "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket", "bottle",
        "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich",
        "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
        "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote",
        "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator", "book",
        "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
    )
    val persons = intArrayOf(0)
    val vehicles = intArrayOf(1, 2, 3, 5, 7)
    val animals = intArrayOf(14, 15, 16, 17, 18, 19, 20, 21, 22, 23)

    private val byName: Map<String, Int> = names.withIndex().associate { it.value to it.index }
    fun idOf(name: String): Int = byName[name.trim().lowercase()] ?: -1
    /** معرّفات الأصناف المخصّصة تبدأ من هنا، وكل نموذج مخصّص له نطاق مستقل بحجم [CUSTOM_STEP]. */
    const val CUSTOM_BASE = 1000
    const val CUSTOM_STEP = 1000
    private val customSets = java.util.concurrent.CopyOnWriteArrayList<List<String>>()

    /** يسجّل أسماء أصناف نموذج مخصّص ويعيد معرّف الصنف الأول له. */
    @Synchronized
    fun registerCustom(names: List<String>): Int {
        var i = customSets.indexOf(names)
        if (i < 0) {
            customSets.add(names)
            i = customSets.size - 1
        }
        return CUSTOM_BASE + i * CUSTOM_STEP
    }

    fun nameOf(id: Int): String {
        if (id >= CUSTOM_BASE) {
            val rel = id - CUSTOM_BASE
            return customSets.getOrNull(rel / CUSTOM_STEP)?.getOrNull(rel % CUSTOM_STEP) ?: "object"
        }
        return if (id in names.indices) names[id] else "object"
    }

    fun labelAr(id: Int): String {
        if (id >= CUSTOM_BASE) {
            val n = nameOf(id)
            val cid = idOf(ClassFilter.norm(n))
            return if (cid == 0 || vehicles.contains(cid) || animals.contains(cid)) labelAr(cid) else n
        }
        return when {
            id == 0 -> "شخص"
            vehicles.contains(id) -> "مركبة"
            animals.contains(id) -> "حيوان"
            else -> "جسم"
        }
    }

    fun allowedIds(persons: Boolean, vehicles: Boolean, animals: Boolean): IntArray {
        val l = ArrayList<Int>()
        if (persons) l.addAll(this.persons.toList())
        if (vehicles) l.addAll(this.vehicles.toList())
        if (animals) l.addAll(this.animals.toList())
        return l.toIntArray()
    }
}

/** حساب التحجيم مع الحشو (Letterbox) من إطار المصدر إلى مدخل النموذج. */
class Letterbox(val srcW: Int, val srcH: Int, val dstW: Int, val dstH: Int) {
    val scale: Float = min(dstW.toFloat() / srcW, dstH.toFloat() / srcH)
    val newW: Int = (srcW * scale).roundToInt().coerceIn(1, dstW)
    val newH: Int = (srcH * scale).roundToInt().coerceIn(1, dstH)
    val padX: Int = (dstW - newW) / 2
    val padY: Int = (dstH - newH) / 2
}

object YoloMath {

    /** يملأ مصفوفة CHW (RGB، 0..1) من إطار ARGB مع Letterbox وتحجيم ثنائي الخطية. */
    fun preprocess(argb: IntArray, srcW: Int, srcH: Int, lb: Letterbox, out: FloatArray) {
        val plane = lb.dstW * lb.dstH
        java.util.Arrays.fill(out, 0, plane * 3, 114f / 255f)
        val newW = lb.newW
        val newH = lb.newH
        val x0 = IntArray(newW)
        val x1 = IntArray(newW)
        val wx = FloatArray(newW)
        val sx = srcW.toFloat() / newW
        for (dx in 0 until newW) {
            var fx = (dx + 0.5f) * sx - 0.5f
            if (fx < 0f) fx = 0f
            val ix = fx.toInt().coerceAtMost(srcW - 1)
            x0[dx] = ix
            x1[dx] = if (ix + 1 < srcW) ix + 1 else ix
            wx[dx] = fx - ix
        }
        val sy = srcH.toFloat() / newH
        val inv255 = 1f / 255f
        for (dy in 0 until newH) {
            var fy = (dy + 0.5f) * sy - 0.5f
            if (fy < 0f) fy = 0f
            val iy = fy.toInt().coerceAtMost(srcH - 1)
            val iy1 = if (iy + 1 < srcH) iy + 1 else iy
            val wy = fy - iy
            val row0 = iy * srcW
            val row1 = iy1 * srcW
            val outRow = (lb.padY + dy) * lb.dstW + lb.padX
            for (dx in 0 until newW) {
                val a = argb[row0 + x0[dx]]
                val b = argb[row0 + x1[dx]]
                val c = argb[row1 + x0[dx]]
                val d = argb[row1 + x1[dx]]
                val w = wx[dx]
                val r = bilerp((a shr 16) and 0xFF, (b shr 16) and 0xFF, (c shr 16) and 0xFF, (d shr 16) and 0xFF, w, wy)
                val g = bilerp((a shr 8) and 0xFF, (b shr 8) and 0xFF, (c shr 8) and 0xFF, (d shr 8) and 0xFF, w, wy)
                val bl = bilerp(a and 0xFF, b and 0xFF, c and 0xFF, d and 0xFF, w, wy)
                out[outRow + dx] = r * inv255
                out[plane + outRow + dx] = g * inv255
                out[2 * plane + outRow + dx] = bl * inv255
            }
        }
    }

    private fun bilerp(a: Int, b: Int, c: Int, d: Int, wx: Float, wy: Float): Float {
        val top = a + (b - a) * wx
        val bot = c + (d - c) * wx
        return top + (bot - top) * wy
    }

    /**
     * فك مخرجات YOLOv8/YOLO11 ذات الشكل [1, 4+nc, anchors] أو [1, anchors, 4+nc].
     * الإحداثيات بوحدات بكسل مدخل النموذج (cx, cy, w, h).
     */
    fun decode(
        data: FloatArray,
        channels: Int,
        anchors: Int,
        channelsFirst: Boolean,
        lb: Letterbox,
        allowed: IntArray,
        minScore: Float
    ): List<Detection> {
        val nc = channels - 4
        if (nc <= 0 || allowed.isEmpty()) return emptyList()
        val cand = ArrayList<Detection>()
        for (i in 0 until anchors) {
            var best = -1
            var bestScore = minScore
            for (cls in allowed) {
                if (cls >= nc) continue
                val s = if (channelsFirst) data[(4 + cls) * anchors + i] else data[i * channels + 4 + cls]
                if (s >= bestScore) {
                    bestScore = s
                    best = cls
                }
            }
            if (best < 0) continue
            val cx: Float
            val cy: Float
            val w: Float
            val h: Float
            if (channelsFirst) {
                cx = data[i]; cy = data[anchors + i]; w = data[2 * anchors + i]; h = data[3 * anchors + i]
            } else {
                val o = i * channels
                cx = data[o]; cy = data[o + 1]; w = data[o + 2]; h = data[o + 3]
            }
            val l = ((cx - w / 2f) - lb.padX) / lb.newW
            val t = ((cy - h / 2f) - lb.padY) / lb.newH
            val r = ((cx + w / 2f) - lb.padX) / lb.newW
            val b = ((cy + h / 2f) - lb.padY) / lb.newH
            val dl = l.coerceIn(0f, 1f)
            val dt = t.coerceIn(0f, 1f)
            val dr = r.coerceIn(0f, 1f)
            val db = b.coerceIn(0f, 1f)
            if (dr - dl <= 0.002f || db - dt <= 0.002f) continue
            cand.add(Detection(best, Coco.nameOf(best), bestScore, dl, dt, dr, db))
        }
        return Nms.apply(cand, 0.45f, 30)
    }
}

/** محلل عام لمخرجات YOLO بمختلف عائلاته وأنواع مهامه. يُستعمل عند وجود أصناف مخصّصة أو نمط إخراج غير تقليدي. */
object YoloDecodeEx {

    /** مخرجات بلا NMS بشكل [N, 6] = x1,y1,x2,y2,score,class (YOLOv10 و YOLO26 وما شابه). */
    fun isEnd2End(a: Int, b: Int, meta: YoloMeta): Boolean = meta.end2end || (b == 6 && a in 7..1000)

    /**
     * صيغة YOLOv5/v7: [cx, cy, w, h, objectness, أصناف…] أي nc+5 قناة. النماذج المائلة (OBB) لها العدد نفسه
     * (4+nc+زاوية) لكنها بلا objectness، لذلك تُستثنى بحسب المهمة وإلا فُسّرت خطأً.
     */
    fun isV5(a: Int, b: Int, meta: YoloMeta): Boolean {
        if (meta.nc <= 0 || meta.task == "obb" || isEnd2End(a, b, meta)) return false
        val channels = if (a < b) a else b
        return channels == meta.nc + 5
    }

    fun decode(
        data: FloatArray, a: Int, b: Int, meta: YoloMeta, lb: Letterbox,
        allowedCoco: IntArray, mask: BooleanArray?, classBase: Int, minScore: Float
    ): List<Detection> =
        if (isEnd2End(a, b, meta)) decodeE2E(data, a, b, lb, allowedCoco, mask, classBase, minScore)
        else decodeGrid(data, a, b, meta, lb, allowedCoco, mask, classBase, minScore)

    private fun permits(k: Int, allowedCoco: IntArray, mask: BooleanArray?): Boolean =
        if (mask != null) (k >= 0 && k < mask.size && mask[k]) else allowedCoco.contains(k)

    /**
     * شبكة الكشف: [1, 4+nc(+إضافات), N] أو مقلوبة. الإضافات (أقنعة التقسيم، نقاط الوضعية، زاوية OBB)
     * تُتجاهل لأن عدد الأصناف يُؤخذ من أسماء النموذج لا من عدد الأعمدة. وصيغة YOLOv5 تحوي عمود objectness.
     */
    private fun decodeGrid(
        data: FloatArray, a: Int, b: Int, meta: YoloMeta, lb: Letterbox,
        allowedCoco: IntArray, mask: BooleanArray?, classBase: Int, minScore: Float
    ): List<Detection> {
        val channelsFirst = a < b
        val channels = if (channelsFirst) a else b
        val anchors = if (channelsFirst) b else a
        val nc = if (meta.nc > 0) meta.nc else channels - 4
        val v5 = isV5(a, b, meta)
        val clsOff = if (v5) 5 else 4
        if (nc <= 0 || channels < clsOff + nc || data.size < channels * anchors) return emptyList()
        val obb = meta.task == "obb" && channels >= clsOff + nc + 1

        val permitted = ArrayList<Int>()
        for (k in 0 until nc) if (permits(k, allowedCoco, mask)) permitted.add(k)
        if (permitted.isEmpty()) return emptyList()
        val cls = permitted.toIntArray()

        fun g(row: Int, i: Int): Float = if (channelsFirst) data[row * anchors + i] else data[i * channels + row]

        val cand = ArrayList<Detection>()
        for (i in 0 until anchors) {
            val obj = if (v5) g(4, i) else 1f
            if (obj < minScore) continue
            var best = -1
            var bestScore = minScore
            for (k in cls) {
                val sc = g(clsOff + k, i) * obj
                if (sc >= bestScore) {
                    bestScore = sc
                    best = k
                }
            }
            if (best < 0) continue
            val cx = g(0, i)
            val cy = g(1, i)
            var w = g(2, i)
            var h = g(3, i)
            if (obb) {
                val ang = g(clsOff + nc, i)
                val cs = abs(cos(ang))
                val sn = abs(sin(ang))
                val nw = w * cs + h * sn
                val nh = w * sn + h * cs
                w = nw
                h = nh
            }
            val l = ((cx - w / 2f) - lb.padX) / lb.newW
            val t = ((cy - h / 2f) - lb.padY) / lb.newH
            val r = ((cx + w / 2f) - lb.padX) / lb.newW
            val bt = ((cy + h / 2f) - lb.padY) / lb.newH
            val dl = l.coerceIn(0f, 1f)
            val dt = t.coerceIn(0f, 1f)
            val dr = r.coerceIn(0f, 1f)
            val db = bt.coerceIn(0f, 1f)
            if (dr - dl <= 0.002f || db - dt <= 0.002f) continue
            val id = classBase + best
            cand.add(Detection(id, Coco.nameOf(id), bestScore, dl, dt, dr, db))
        }
        return Nms.apply(cand, 0.45f, 30)
    }

    private fun decodeE2E(
        data: FloatArray, a: Int, b: Int, lb: Letterbox,
        allowedCoco: IntArray, mask: BooleanArray?, classBase: Int, minScore: Float
    ): List<Detection> {
        val transposed = a == 6 && b != 6
        if (!transposed && b != 6) return emptyList()
        val n = if (transposed) b else a
        if (n <= 0 || data.size < n * 6) return emptyList()
        fun g(i: Int, c: Int): Float = if (transposed) data[c * n + i] else data[i * 6 + c]

        // بعض المصدّرات تعطي إحداثيات مطبّعة 0..1 بدل البكسل.
        var maxC = 0f
        for (i in 0 until n) if (g(i, 4) >= minScore) maxC = max(maxC, max(g(i, 2), g(i, 3)))
        val normalized = maxC > 0.0001f && maxC <= 1.5f
        val sx = if (normalized) lb.dstW.toFloat() else 1f
        val sy = if (normalized) lb.dstH.toFloat() else 1f

        val cand = ArrayList<Detection>()
        for (i in 0 until n) {
            val score = g(i, 4)
            if (score < minScore) continue
            val k = g(i, 5).roundToInt()
            if (!permits(k, allowedCoco, mask)) continue
            val l = ((g(i, 0) * sx) - lb.padX) / lb.newW
            val t = ((g(i, 1) * sy) - lb.padY) / lb.newH
            val r = ((g(i, 2) * sx) - lb.padX) / lb.newW
            val bt = ((g(i, 3) * sy) - lb.padY) / lb.newH
            val dl = l.coerceIn(0f, 1f)
            val dt = t.coerceIn(0f, 1f)
            val dr = r.coerceIn(0f, 1f)
            val db = bt.coerceIn(0f, 1f)
            if (dr - dl <= 0.002f || db - dt <= 0.002f) continue
            val id = classBase + k
            cand.add(Detection(id, Coco.nameOf(id), score, dl, dt, dr, db))
        }
        return cand.sortedByDescending { it.score }.take(30)
    }
}

object Nms {
    fun iou(a: Detection, b: Detection): Float {
        val il = max(a.left, b.left)
        val it = max(a.top, b.top)
        val ir = min(a.right, b.right)
        val ib = min(a.bottom, b.bottom)
        val iw = ir - il
        val ih = ib - it
        if (iw <= 0f || ih <= 0f) return 0f
        val inter = iw * ih
        val union = a.width * a.height + b.width * b.height - inter
        return if (union <= 0f) 0f else inter / union
    }

    fun apply(dets: List<Detection>, iouThr: Float, maxOut: Int): List<Detection> {
        if (dets.isEmpty()) return dets
        val sorted = dets.sortedByDescending { it.score }
        val kept = ArrayList<Detection>()
        for (d in sorted) {
            var ok = true
            for (k in kept) {
                if (k.classId == d.classId && iou(k, d) > iouThr) {
                    ok = false
                    break
                }
            }
            if (ok) {
                kept.add(d)
                if (kept.size >= maxOut) break
            }
        }
        return kept
    }
}
