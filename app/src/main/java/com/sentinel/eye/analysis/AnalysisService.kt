package com.sentinel.eye.analysis

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import com.sentinel.eye.MainActivity
import com.sentinel.eye.R
import com.sentinel.eye.core.AppLog
import com.sentinel.eye.core.Config
import com.sentinel.eye.core.Perf
import com.sentinel.eye.detect.Coco
import com.sentinel.eye.detect.DetectorFactory
import com.sentinel.eye.detect.ModelCatalog
import com.sentinel.eye.detect.ModelManager
import com.sentinel.eye.monitor.Notifier
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

sealed class AnState {
    object Idle : AnState()
    class Running(val doneSec: Int, val totalSec: Int, val hits: Int, val frames: Int, val startedAtMs: Long) : AnState()
    class Done(
        val uri: Uri, val durationSec: Int, val segments: List<Segment>, val hitSeconds: Int,
        val frames: Int, val elapsedMs: Long, val labels: Set<Int>, val fallback: Boolean
    ) : AnState()
    class Failed(val message: String) : AnState()
}

sealed class ExState {
    object Idle : ExState()
    class Running(val frac: Float) : ExState()
    class Done(val file: File, val keptSec: Int) : ExState()
    class Failed(val message: String) : ExState()
}

sealed class Request {
    class Analyze(val uri: Uri, val cfg: Config) : Request()
    class Export(val uri: Uri, val segments: List<Segment>) : Request()
}

/** قناة الحالة بين خدمة التحليل والواجهة. */
object AnalysisBus {
    val state = MutableStateFlow<AnState>(AnState.Idle)
    val export = MutableStateFlow<ExState>(ExState.Idle)
    val selected = MutableStateFlow<Uri?>(null)
    val selectedName = MutableStateFlow("")
    val selectedInfo = MutableStateFlow("")
    val cancel = AtomicBoolean(false)

    @Volatile
    var pending: Request? = null

    fun busy(): Boolean = state.value is AnState.Running || export.value is ExState.Running
}

class AnalysisService : Service() {

    companion object {
        const val ID = 1002

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, AnalysisService::class.java))
        }
    }

    private var worker: Thread? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.init(applicationContext)
    }

    private fun notif(text: String, done: Int, total: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = Notification.Builder(this, Notifier.CH_ANALYSIS)
            .setSmallIcon(R.drawable.ic_stat_eye)
            .setContentTitle("تحليل الفيديو")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
        if (total > 0) b.setProgress(total, done, false) else b.setProgress(0, 0, true)
        return b.build()
    }

    private fun updateNotif(text: String, done: Int, total: Int) {
        try {
            getSystemService(android.app.NotificationManager::class.java).notify(ID, notif(text, done, total))
        } catch (_: Throwable) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(this, ID, notif("جارٍ التحضير…", 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (t: Throwable) {
            AppLog.e("AnalysisSvc", "تعذّر بدء الخدمة الأمامية", t)
            AnalysisBus.state.value = AnState.Failed("تعذّر بدء الخدمة: ${t.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        val req = AnalysisBus.pending
        AnalysisBus.pending = null
        if (req == null || worker?.isAlive == true) {
            if (worker?.isAlive != true) stopSelf()
            return START_NOT_STICKY
        }
        val th = Thread({
            try {
                when (req) {
                    is Request.Analyze -> runAnalyze(req)
                    is Request.Export -> runExport(req)
                }
            } catch (t: Throwable) {
                AppLog.e("AnalysisSvc", "خطأ غير متوقع", t)
            } finally {
                stopSelf()
            }
        }, "analysis-worker")
        worker = th
        th.start()
        return START_NOT_STICKY
    }

    private fun runAnalyze(req: Request.Analyze) {
        val cfg = req.cfg
        AnalysisBus.cancel.set(false)
        Perf.boostCurrentThread(cfg.maxPerf)
        val pm = getSystemService(PowerManager::class.java)
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sentinel:analysis")
        wl.acquire(6 * 60 * 60 * 1000L)
        val started = SystemClock.elapsedRealtime()
        try {
            val spec = ModelCatalog.byId(cfg.modelId)
            if (spec == null || !ModelManager.isInstalled(spec)) {
                AnalysisBus.state.value = AnState.Failed("ثبّت نموذجاً واخترْه من تبويب «النماذج» أولاً.")
                return
            }
            val allowed = Coco.allowedIds(cfg.anPersons, cfg.anVehicles, cfg.anAnimals)
            if (allowed.isEmpty()) {
                AnalysisBus.state.value = AnState.Failed("اختر هدفاً واحداً على الأقل.")
                return
            }
            AnalysisBus.state.value = AnState.Running(0, 0, 0, 0, started)
            val det = DetectorFactory.create(applicationContext, spec, ModelManager.fileOf(spec), cfg)
            try {
                val res = VideoAnalyzer(applicationContext).analyze(
                    req.uri, det, allowed, cfg.anConfidence, cfg.anSampling == 0,
                    cfg.analysisWidth.coerceAtLeast(320), AnalysisBus.cancel
                ) { p ->
                    AnalysisBus.state.value = AnState.Running(p.doneSec, p.totalSec, p.hits, p.frames, started)
                    updateNotif("${p.doneSec} / ${p.totalSec} ثانية · ${p.hits} لحظة", p.doneSec, p.totalSec)
                }
                val segs = Segments.build(res.hits, res.durationSec, cfg.anPadSec, cfg.anGapSec)
                var hitSec = 0
                for (h in res.hits) if (h) hitSec++
                AnalysisBus.state.value = AnState.Done(
                    req.uri, res.durationSec, segs, hitSec, res.frames, res.elapsedMs, res.labels, res.usedFallback
                )
            } finally {
                try { det.close() } catch (_: Throwable) { }
            }
        } catch (c: CancelledException) {
            AnalysisBus.state.value = AnState.Idle
        } catch (t: Throwable) {
            AppLog.e("AnalysisSvc", "فشل التحليل", t)
            AnalysisBus.state.value = AnState.Failed(t.message ?: "فشل التحليل")
        } finally {
            try { if (wl.isHeld) wl.release() } catch (_: Throwable) { }
        }
    }

    private fun runExport(req: Request.Export) {
        AnalysisBus.cancel.set(false)
        AnalysisBus.export.value = ExState.Running(0f)
        val dir = getExternalFilesDir("analysis") ?: File(filesDir, "analysis")
        dir.mkdirs()
        val out = File(dir, "highlights_" + System.currentTimeMillis() + ".mp4")
        try {
            val ok = ClipExporter.export(applicationContext, req.uri, req.segments, out, AnalysisBus.cancel) { f ->
                AnalysisBus.export.value = ExState.Running(f)
                updateNotif("جارٍ استخراج المقاطع", (f * 100).toInt(), 100)
            }
            if (ok && out.exists() && out.length() > 0L) {
                AnalysisBus.export.value = ExState.Done(out, req.segments.sumOf { it.length })
            } else {
                out.delete()
                AnalysisBus.export.value = ExState.Failed("تعذّر استخراج المقاطع من هذا الفيديو.")
            }
        } catch (c: CancelledException) {
            out.delete()
            AnalysisBus.export.value = ExState.Idle
        } catch (t: Throwable) {
            out.delete()
            AnalysisBus.export.value = ExState.Failed(t.message ?: "فشل الاستخراج")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
