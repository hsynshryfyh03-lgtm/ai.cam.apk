package com.sentinel.eye.core

import android.app.GameManager
import android.app.GameState
import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.PowerManager
import android.os.Process
import java.io.File

/** أدوات رفع أداء التطبيق: أولوية الخيوط، تلميحات ADPF، وإشارة Game Mode. */
object Perf {

    /** يرفع أولوية الخيط الحالي. الخيوط التي ينشئها لاحقاً (مثل مجمّع ONNX) ترث الأولوية. */
    fun boostCurrentThread(maxPerf: Boolean) {
        try {
            Process.setThreadPriority(if (maxPerf) -12 else Process.THREAD_PRIORITY_FOREGROUND)
        } catch (_: Throwable) {
        }
    }

    fun taskIds(): Set<Int> = try {
        File("/proc/self/task").list()?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()
    } catch (_: Throwable) {
        emptySet()
    }

    /** يُعلم النظام أن التطبيق في «طور لعب» غير قابل للمقاطعة (يتطلب تصنيف التطبيق كلعبة). */
    fun setGameState(ctx: Context, active: Boolean) {
        if (Build.VERSION.SDK_INT < 33) return
        try {
            val gm = ctx.getSystemService(GameManager::class.java) ?: return
            val mode = if (active) GameState.MODE_GAMEPLAY_UNINTERRUPTIBLE else GameState.MODE_NONE
            gm.setGameState(GameState(false, mode))
        } catch (t: Throwable) {
            AppLog.w("Perf", "تعذّر ضبط حالة اللعبة", t)
        }
    }

    private val thermalNames = arrayOf("طبيعية", "خفيفة", "متوسطة", "شديدة", "حرجة", "طارئة", "إيقاف")

    fun thermalLabel(ctx: Context): String = try {
        val pm = ctx.getSystemService(PowerManager::class.java)
        val s = pm?.currentThermalStatus ?: 0
        thermalNames[s.coerceIn(0, thermalNames.size - 1)]
    } catch (_: Throwable) {
        "—"
    }
}

/** جلسة تلميحات الأداء (ADPF): تخبر النظام بخيوط العمل الثقيلة ليرفع تردد المعالج لها. */
class HintSession(private val ctx: Context) {
    private var session: PerformanceHintManager.Session? = null

    @Synchronized
    fun start(tids: IntArray, targetNs: Long) {
        close()
        if (tids.isEmpty()) return
        try {
            val m = ctx.getSystemService(PerformanceHintManager::class.java) ?: return
            session = m.createHintSession(tids, targetNs)
            AppLog.i("Perf", "جلسة ADPF: ${if (session != null) "فعّالة" else "غير مدعومة"} (${tids.size} خيط)")
        } catch (t: Throwable) {
            AppLog.w("Perf", "ADPF غير متاح", t)
            session = null
        }
    }

    @Synchronized
    fun report(actualNs: Long) {
        try {
            session?.reportActualWorkDuration(actualNs.coerceAtLeast(1L))
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    fun close() {
        try { session?.close() } catch (_: Throwable) { }
        session = null
    }

    @Synchronized
    fun isActive(): Boolean = session != null
}
