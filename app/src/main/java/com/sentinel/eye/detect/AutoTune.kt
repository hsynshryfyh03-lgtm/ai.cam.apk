package com.sentinel.eye.detect

import android.content.Context
import android.os.SystemClock
import com.sentinel.eye.core.AppLog
import com.sentinel.eye.core.Config
import com.sentinel.eye.core.Perf
import java.util.concurrent.atomic.AtomicBoolean

/** ضبط تلقائي: يجرّب مسرّعات وأعداد خيوط مختلفة على جهازك ويختار الأسرع. */
object AutoTune {
    class Trial(val label: String, val accel: Int, val threads: Int, val ms: Float)

    fun run(
        ctx: Context, spec: ModelSpec, base: Config, cancel: AtomicBoolean,
        onProgress: (done: Int, total: Int, label: String) -> Unit
    ): List<Trial> {
        Perf.boostCurrentThread(true)
        val file = ModelManager.fileOf(spec)
        val cands = ArrayList<Pair<Int, Int>>()
        if (spec.engine == EngineType.YOLO) {
            for (t in intArrayOf(2, 3, 4, 6)) cands.add(Pair(Accel.CPU.id, t))
            for (t in intArrayOf(2, 3, 4, 6)) cands.add(Pair(Accel.XNNPACK.id, t))
            cands.add(Pair(Accel.GPU_NNAPI.id, 4))
        } else {
            cands.add(Pair(Accel.CPU.id, 4))
            cands.add(Pair(Accel.GPU_NNAPI.id, 4))
        }
        val w = 640
        val h = 360
        val argb = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            (0xFF shl 24) or (((x * 255) / w) shl 16) or (((y * 255) / h) shl 8) or 128
        }
        val frame = Frame(argb, w, h, 0L)
        val runs = if (spec.approxMb > 60) 3 else 6
        val out = ArrayList<Trial>()
        for ((i, c) in cands.withIndex()) {
            if (cancel.get()) break
            val accel = Accel.of(c.first)
            val label = accel.title + " · " + c.second + " خيوط"
            onProgress(i, cands.size, label)
            try {
                val det = DetectorFactory.create(ctx, spec, file, base.copy(accel = c.first, threads = c.second))
                try {
                    det.detect(frame, 0.5f, intArrayOf(0))
                    det.detect(frame, 0.5f, intArrayOf(0))
                    val t0 = SystemClock.elapsedRealtime()
                    for (k in 0 until runs) det.detect(frame, 0.5f, intArrayOf(0))
                    val avg = (SystemClock.elapsedRealtime() - t0) / runs.toFloat()
                    out.add(Trial(label, c.first, c.second, avg))
                } finally {
                    det.close()
                }
            } catch (t: Throwable) {
                AppLog.w("AutoTune", "تخطّي $label", t)
            }
        }
        onProgress(cands.size, cands.size, "")
        out.sortBy { it.ms }
        return out
    }
}
