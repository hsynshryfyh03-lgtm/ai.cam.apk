package com.sentinel.eye.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.Image
import android.media.MediaFormat
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import com.sentinel.eye.MainActivity
import com.sentinel.eye.R
import androidx.core.content.ContextCompat
import com.sentinel.eye.alerts.Telegram
import com.sentinel.eye.capture.AudioPipeline
import com.sentinel.eye.capture.CapturePlan
import com.sentinel.eye.capture.CapturePlanner
import com.sentinel.eye.capture.CaptureRig
import com.sentinel.eye.capture.EventRecorder
import com.sentinel.eye.capture.Rot
import com.sentinel.eye.core.AppLog
import com.sentinel.eye.core.Config
import com.sentinel.eye.core.ConfigStore
import com.sentinel.eye.core.EncodedSample
import com.sentinel.eye.core.EventMeta
import com.sentinel.eye.core.EventStateMachine
import com.sentinel.eye.core.FrameConverter
import com.sentinel.eye.core.HintSession
import com.sentinel.eye.core.ImageUtil
import com.sentinel.eye.core.MotionGate
import com.sentinel.eye.core.Perf
import com.sentinel.eye.core.Storage
import com.sentinel.eye.detect.Coco
import com.sentinel.eye.detect.Detection
import com.sentinel.eye.detect.Detector
import com.sentinel.eye.detect.DetectorFactory
import com.sentinel.eye.detect.Frame
import com.sentinel.eye.detect.ModelCatalog
import com.sentinel.eye.detect.ModelManager
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

sealed class MonitorStatus {
    object Stopped : MonitorStatus()
    object Starting : MonitorStatus()
    data class Running(val desc: String) : MonitorStatus()
    data class Error(val message: String, val retrying: Boolean) : MonitorStatus()
}

class LiveFrame(val bitmap: Bitmap, val detections: List<Detection>, val timeMs: Long)

data class Stats(
    val engine: String = "",
    val inferMs: Int = 0,
    val fps: Float = 0f,
    val recording: Boolean = false,
    val eventStartMs: Long = 0L,
    val video: String = "",
    val bufferSec: Int = 0,
    val modelNote: String = "",
    val thermal: String = "",
    val hint: Boolean = false
)

/** قناة الحالة بين خدمة المراقبة والواجهة. */
object MonitorBus {
    val status = MutableStateFlow<MonitorStatus>(MonitorStatus.Stopped)
    val frame = MutableStateFlow<LiveFrame?>(null)
    val stats = MutableStateFlow(Stats())
    val eventsChanged = MutableStateFlow(0L)

    @Volatile var uiVisible = false
    @Volatile var micFgs = false
    @Volatile var liveVisible = false

    fun previewWanted(): Boolean = uiVisible && liveVisible

    fun updateStats(block: (Stats) -> Stats) {
        synchronized(this) { stats.value = block(stats.value) }
    }
}

object Notifier {
    const val CH_MONITOR = "monitor"
    const val CH_EVENTS = "events"
    const val CH_ANALYSIS = "analysis"
    const val ID_FG = 1001

    fun init(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val m = NotificationChannel(CH_MONITOR, "حالة المراقبة", NotificationManager.IMPORTANCE_LOW)
        m.setShowBadge(false)
        val e = NotificationChannel(CH_EVENTS, "الأحداث المرصودة", NotificationManager.IMPORTANCE_HIGH)
        val a = NotificationChannel(CH_ANALYSIS, "تحليل الفيديو", NotificationManager.IMPORTANCE_LOW)
        a.setShowBadge(false)
        nm.createNotificationChannel(m)
        nm.createNotificationChannel(e)
        nm.createNotificationChannel(a)
    }

    private fun openIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun foreground(ctx: Context, text: String): Notification {
        val stop = PendingIntent.getService(
            ctx, 1,
            Intent(ctx, MonitorService::class.java).setAction(MonitorService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = Notification.Builder(ctx, CH_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_eye)
            .setContentTitle("الحارس الذكي")
            .setContentText(text)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(openIntent(ctx))
            .addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_stat_eye), "إيقاف", stop).build())
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    fun update(ctx: Context, text: String) {
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(ID_FG, foreground(ctx, text))
        } catch (_: Throwable) {
        }
    }

    private fun post(ctx: Context, id: String, title: String, text: String, thumb: Bitmap?) {
        try {
            val b = Notification.Builder(ctx, CH_EVENTS)
                .setSmallIcon(R.drawable.ic_stat_eye)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openIntent(ctx))
                .setAutoCancel(true)
            if (thumb != null) b.setStyle(Notification.BigPictureStyle().bigPicture(thumb))
            ctx.getSystemService(NotificationManager::class.java).notify(id.hashCode(), b.build())
        } catch (t: Throwable) {
            AppLog.w("Notifier", "تعذّر إرسال إشعار", t)
        }
    }

    fun eventStart(ctx: Context, id: String, label: String) = post(ctx, id, "رُصد $label", "جارٍ التسجيل…", null)

    fun eventDone(ctx: Context, id: String, label: String, durationMs: Long, thumb: Bitmap?) {
        val s = (durationMs / 1000).toInt()
        post(ctx, id, "تم تسجيل حدث: $label", "المدة %d:%02d".format(s / 60, s % 60), thumb)
    }
}

object AlarmPlayer {
    fun play(ctx: Context, ms: Long = 6000L) {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val r = RingtoneManager.getRingtone(ctx, uri) ?: return
            r.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            r.play()
            Handler(Looper.getMainLooper()).postDelayed({ try { r.stop() } catch (_: Throwable) { } }, ms)
        } catch (t: Throwable) {
            AppLog.w("Alarm", "تعذّر تشغيل المنبّه", t)
        }
    }
}

/**
 * قلب المراقبة: يدير الكاميرا والمرمّز والنموذج وآلة الأحداث والتسجيل.
 * كل أوامر التحكم تُنفَّذ على خيط تحكم واحد لتفادي السباقات.
 */
class MonitorEngine(
    private val app: Context,
    private val onStatusText: (String) -> Unit
) : CaptureRig.Sink {

    private class EventTrack(val id: String, val startMs: Long, val engine: String) {
        var maxScore = 0f
        val classes = LinkedHashSet<Int>()
        var bestScore = -1f
        var bestArgb: IntArray? = null
        var bestW = 0
        var bestH = 0
        var bestDets: List<Detection> = emptyList()
    }

    private val control = Executors.newSingleThreadScheduledExecutor()
    private val inferExec = Executors.newSingleThreadExecutor { r ->
        Thread({
            Perf.boostCurrentThread(ConfigStore.current.maxPerf)
            r.run()
        }, "infer-worker")
    }
    private val hint = HintSession(app)
    private var audio: AudioPipeline? = null
    @Volatile private var audioOffsetUs: Long? = null
    private val inferBusy = AtomicBoolean(false)
    private val eventLock = Any()
    private val store = Storage.events
    private val recorder = EventRecorder(store)
    private val sm = EventStateMachine(2, 8000L, 600_000L)
    private val gate = MotionGate()
    private val trackers = ConcurrentHashMap<String, EventTrack>()

    @Volatile private var detector: Detector? = null
    @Volatile private var rig: CaptureRig? = null
    @Volatile private var running = false
    @Volatile private var current: EventTrack? = null
    @Volatile private var lastDetections: List<Detection> = emptyList()
    @Volatile private var lastDetTime = 0L

    private var plans: List<CapturePlan> = emptyList()
    private var planIndex = 0
    private var retryAttempt = 0
    private var retryFuture: ScheduledFuture<*>? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var captureKey = ""
    private var modelKey = ""
    private var engineName = ""
    private var lastModelCheck = 0L
    private var blockedUntil = 0L

    private var yArr = ByteArray(0)
    private var uArr = ByteArray(0)
    private var vArr = ByteArray(0)
    private var lastPreview = 0L
    private var lastAnalysis = 0L
    private var lastInfer = 0L
    private var lastStatsPush = 0L
    @Volatile private var emaMs = 0f
    @Volatile private var inferCount = 0
    private var inferCountAtPush = 0

    private val configListener: (Config) -> Unit = { cfg -> control.execute { onConfigChanged(cfg) } }

    init {
        recorder.onFinished = { f -> finalizeEvent(f) }
    }

    // ───────────── دورة الحياة ─────────────

    fun start() { control.execute { doStart() } }

    fun stopAndWait() {
        try {
            control.submit(Runnable { doStop() }).get(8, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            AppLog.w("Engine", "انتهت مهلة الإيقاف", t)
        }
        control.shutdown()
        inferExec.shutdown()
        recorder.shutdown()
    }

    private fun doStart() {
        if (running) return
        running = true
        try {
            MonitorBus.status.value = MonitorStatus.Starting
            val cfg = ConfigStore.current
            captureKey = cfg.captureKey()
            modelKey = cfg.modelKey()
            ConfigStore.addListener(configListener)
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            Perf.setGameState(app, true)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sentinel:monitor").also {
                it.setReferenceCounted(false)
                it.acquire()
            }
            store.cleanOrphans(emptySet())
            store.enforceQuota(cfg.maxStorageGb * 1_000_000_000L, cfg.maxDays, emptySet())
            loadDetector(cfg)
            plans = CapturePlanner.plans(app, cfg)
            planIndex = 0
            syncAudio(cfg)
            if (plans.isEmpty()) {
                scheduleRetry("لا توجد كاميرا متاحة")
                return
            }
            startRig()
        } catch (t: Throwable) {
            AppLog.e("Engine", "فشل بدء المراقبة", t)
            scheduleRetry("خطأ عند البدء: ${t.message}")
        }
    }

    private fun doStop() {
        if (!running) return
        running = false
        ConfigStore.removeListener(configListener)
        retryFuture?.cancel(false)
        synchronized(eventLock) { endEvent() }
        stopRigQuiet()
        stopAudio()
        val old = detector
        detector = null
        try { inferExec.submit(Runnable { }).get(5, TimeUnit.SECONDS) } catch (_: Throwable) { }
        try { old?.close() } catch (_: Throwable) { }
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) { }
        hint.close()
        Perf.setGameState(app, false)
        MonitorBus.status.value = MonitorStatus.Stopped
        MonitorBus.frame.value = null
        MonitorBus.updateStats { Stats() }
    }

    private fun startRig() {
        val cfg = ConfigStore.current
        val plan = plans[planIndex]
        recorder.reset()
        recorder.configure(cfg.preRollSec, Rot.rotation(plan, cfg.orientation))
        audioOffsetUs = if (plan.realtimeTs) 0L else null
        gate.reset()
        MonitorBus.updateStats { it.copy(video = plan.description) }
        AppLog.i("Engine", "بدء الكاميرا: ${plan.description}")
        val r = CaptureRig(app, plan, cfg, this)
        rig = r
        r.start()
    }

    private fun stopRigQuiet() {
        val r = rig
        rig = null
        recorder.reset()
        try { r?.stop() } catch (_: Throwable) { }
    }

    private fun scheduleRetry(message: String) {
        retryAttempt++
        val delay = min(30_000L, 2_000L shl min(retryAttempt, 4))
        MonitorBus.status.value = MonitorStatus.Error(message, true)
        onStatusText("$message · إعادة المحاولة")
        retryFuture?.cancel(false)
        retryFuture = control.schedule(Runnable {
            if (!running) return@Runnable
            try {
                plans = CapturePlanner.plans(app, ConfigStore.current)
                planIndex = 0
                if (plans.isEmpty()) scheduleRetry(message) else startRig()
            } catch (t: Throwable) {
                scheduleRetry("خطأ: ${t.message}")
            }
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun restartRig() {
        synchronized(eventLock) { endEvent() }
        stopRigQuiet()
        retryFuture?.cancel(false)
        try {
            plans = CapturePlanner.plans(app, ConfigStore.current)
            planIndex = 0
            retryAttempt = 0
            if (plans.isEmpty()) scheduleRetry("لا توجد كاميرا متاحة") else startRig()
        } catch (t: Throwable) {
            scheduleRetry("خطأ: ${t.message}")
        }
    }

    private fun onConfigChanged(cfg: Config) {
        if (!running) return
        if (cfg.captureKey() != captureKey) {
            captureKey = cfg.captureKey()
            restartRig()
        }
        if (cfg.modelKey() != modelKey) {
            modelKey = cfg.modelKey()
            loadDetector(cfg)
        }
        syncAudio(cfg)
    }

    private fun syncAudio(cfg: Config) {
        val allowed = cfg.audio && MonitorBus.micFgs &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!allowed) {
            if (audio != null) stopAudio()
            return
        }
        if (audio != null) return
        try {
            val a = AudioPipeline(
                { audioOffsetUs },
                { f -> recorder.setAudioFormat(f) },
                { smp -> recorder.onAudioSample(smp) },
                { t -> AppLog.w("Engine", "خطأ في مسار الصوت", t) }
            )
            a.start()
            audio = a
            AppLog.i("Engine", "تسجيل الصوت يعمل")
        } catch (t: Throwable) {
            AppLog.w("Engine", "تعذّر تشغيل الصوت", t)
            audio = null
        }
    }

    private fun stopAudio() {
        try { audio?.stop() } catch (_: Throwable) { }
        audio = null
        recorder.clearAudio()
    }

    private fun loadDetector(cfg: Config) {
        val spec = ModelCatalog.byId(cfg.modelId)
        var created: Detector? = null
        var note = ""
        if (spec == null || !ModelManager.isInstalled(spec)) {
            note = "لا يوجد نموذج مثبّت. ثبّت نموذجاً من تبويب النماذج."
        } else {
            try {
                // يُنشأ الكاشف على خيط الاستدلال ذي الأولوية العالية لترث خيوط ONNX/TFLite أولويته
                val res = inferExec.submit(Callable {
                    Perf.boostCurrentThread(cfg.maxPerf)
                    val before = Perf.taskIds()
                    val d = DetectorFactory.create(app, spec, ModelManager.fileOf(spec), cfg)
                    val tids = HashSet<Int>(Perf.taskIds())
                    tids.removeAll(before)
                    tids.add(Process.myTid())
                    Pair(d, tids.toIntArray())
                }).get()
                created = res.first
                val target = if (cfg.maxPerf) 8_000_000L else 1_000_000_000L / cfg.analysisFps.coerceAtLeast(1)
                hint.start(res.second, target)
                AppLog.i("Engine", "تم تحميل النموذج: ${res.first.name}")
            } catch (t: Throwable) {
                val cause = t.cause ?: t
                AppLog.e("Engine", "تعذّر تحميل النموذج ${spec.id}", cause)
                note = "تعذّر تحميل النموذج: ${cause.message}"
            }
        }
        val old = detector
        detector = created
        engineName = created?.name ?: ""
        MonitorBus.updateStats { it.copy(engine = engineName, modelNote = note) }
        if (old != null) {
            try { inferExec.submit(Runnable { }).get(5, TimeUnit.SECONDS) } catch (_: Throwable) { }
            try { old.close() } catch (_: Throwable) { }
        }
    }

    private fun handleFailure(r: CaptureRig, stage: String, configFailure: Boolean) {
        if (!running || r !== rig) return
        synchronized(eventLock) { endEvent() }
        stopRigQuiet()
        if (configFailure && planIndex + 1 < plans.size) {
            planIndex++
            AppLog.i("Engine", "الانتقال إلى إعداد أقل: ${plans[planIndex].description}")
            startRig()
            return
        }
        scheduleRetry("تعذّر تشغيل الكاميرا ($stage)")
    }

    // ───────────── CaptureRig.Sink ─────────────

    override fun onFormat(rig: CaptureRig, f: MediaFormat) {
        if (rig === this.rig) recorder.setFormat(f)
    }

    override fun onSample(rig: CaptureRig, s: EncodedSample) {
        if (rig !== this.rig) return
        if (audioOffsetUs == null && !rig.plan.realtimeTs) {
            // ساعة الكاميرا مجهولة: نقدّر الإزاحة من وصول أول عينة (مع تأخير ترميز تقريبي 60ms)
            audioOffsetUs = s.ptsUs - (SystemClock.elapsedRealtimeNanos() / 1000L - 60_000L)
        }
        recorder.onSample(s)
    }

    override fun onStreaming(rig: CaptureRig) {
        control.execute {
            if (running && rig === this.rig) {
                retryAttempt = 0
                MonitorBus.status.value = MonitorStatus.Running(rig.plan.description)
                onStatusText("تعمل المراقبة · ${rig.plan.description}")
            }
        }
    }

    override fun onFailure(rig: CaptureRig, stage: String, t: Throwable?, configFailure: Boolean) {
        control.execute { handleFailure(rig, stage, configFailure) }
    }

    override fun onImage(rig: CaptureRig, image: Image) {
        if (rig !== this.rig) return
        val cfg = ConfigStore.current
        val now = SystemClock.elapsedRealtime()
        val wantPreview = MonitorBus.previewWanted() && now - lastPreview >= 100L
        val minInterval = if (cfg.analysisFps <= 0) 0L else 1000L / cfg.analysisFps
        val wantAnalysis = now - lastAnalysis >= minInterval
        if (!wantPreview && !wantAnalysis) return

        val w = image.width
        val h = image.height
        val planes = image.planes
        val yP = planes[0]
        val uP = planes[1]
        yArr = copyPlane(yP.buffer, yArr)
        uArr = copyPlane(uP.buffer, uArr)
        vArr = copyPlane(planes[2].buffer, vArr)
        val rot = Rot.rotation(rig.plan, cfg.orientation)
        val outW = FrameConverter.outWidth(w, h, rot)
        val outH = FrameConverter.outHeight(w, h, rot)
        val argb = IntArray(outW * outH)
        FrameConverter.convert(
            yArr, yP.rowStride, yP.pixelStride, uArr, vArr, uP.rowStride, uP.pixelStride,
            w, h, rot, rig.plan.front, argb
        )

        if (wantPreview) {
            lastPreview = now
            val bmp = ImageUtil.bitmapFromArgb(argb, outW, outH)
            val dets = if (now - lastDetTime < 1500L) lastDetections else emptyList()
            MonitorBus.frame.value = LiveFrame(bmp, dets, now)
        }

        if (wantAnalysis) {
            lastAnalysis = now
            sm.confirmFrames = cfg.confirmFrames
            sm.postRollMs = cfg.postRollSec * 1000L
            sm.maxEventMs = cfg.maxEventMin * 60_000L
            val motion = if (cfg.motionGate) gate.changedFraction(yArr, yP.rowStride, yP.pixelStride, w, h, 18) else 1f
            val needInfer = !cfg.motionGate || motion >= 0.003f || sm.active || now - lastInfer >= 1500L
            val det = detector
            var submitted = false
            if (det != null && needInfer && inferBusy.compareAndSet(false, true)) {
                submitted = true
                lastInfer = now
                val frame = Frame(argb, outW, outH, now)
                inferExec.execute {
                    try {
                        runInference(det, frame, cfg)
                    } catch (t: Throwable) {
                        AppLog.e("Engine", "خطأ أثناء الاستدلال", t)
                    } finally {
                        inferBusy.set(false)
                    }
                }
            }
            if (!submitted) handleAction(sm.update(now, null), null, emptyList())
            if (det == null && now - lastModelCheck > 5000L) {
                lastModelCheck = now
                control.execute { if (running && detector == null) loadDetector(ConfigStore.current) }
            }
            publishStats(now)
        }
    }

    private fun copyPlane(buf: ByteBuffer, cur: ByteArray): ByteArray {
        val n = buf.remaining()
        val arr = if (cur.size >= n) cur else ByteArray(n)
        buf.duplicate().get(arr, 0, n)
        return arr
    }

    // ───────────── الاستدلال والأحداث ─────────────

    private fun inZone(d: Detection, cfg: Config): Boolean {
        if (!cfg.zoneEnabled) return true
        fun inside(x: Float, y: Float) = x >= cfg.zoneL && x <= cfg.zoneR && y >= cfg.zoneT && y <= cfg.zoneB
        return inside(d.centerX, d.centerY) || inside(d.centerX, d.bottom - 0.01f)
    }

    private fun runInference(det: Detector, frame: Frame, cfg: Config) {
        Perf.boostCurrentThread(cfg.maxPerf)
        val t0 = SystemClock.elapsedRealtime()
        val raw = det.detect(frame, cfg.confidence, cfg.allowedClasses())
        val t1 = SystemClock.elapsedRealtime()
        val minH = cfg.minHeightPct / 100f
        val dets = raw.filter { it.height >= minH && inZone(it, cfg) }
        lastDetections = dets
        lastDetTime = t1
        inferCount++
        val ms = (t1 - t0).toFloat()
        hint.report((t1 - t0) * 1_000_000L)
        emaMs = if (emaMs == 0f) ms else emaMs * 0.8f + ms * 0.2f
        val action = sm.update(t1, dets.isNotEmpty())
        handleAction(action, frame, dets)
        trackUpdate(frame, dets)
        publishStats(t1)
    }

    private fun publishStats(now: Long) {
        if (now - lastStatsPush < 500L) return
        val dt = (now - lastStatsPush).coerceAtLeast(1L)
        val c = inferCount
        val fps = if (lastStatsPush == 0L) 0f else (c - inferCountAtPush) * 1000f / dt
        lastStatsPush = now
        inferCountAtPush = c
        val cur = current
        MonitorBus.updateStats {
            it.copy(
                inferMs = emaMs.toInt(), fps = fps, bufferSec = recorder.bufferSeconds(),
                recording = cur != null, eventStartMs = cur?.startMs ?: 0L,
                thermal = Perf.thermalLabel(app), hint = hint.isActive()
            )
        }
    }

    private fun handleAction(a: EventStateMachine.Action, frame: Frame?, dets: List<Detection>) {
        if (a == EventStateMachine.Action.NONE) return
        synchronized(eventLock) {
            when (a) {
                EventStateMachine.Action.START -> beginEvent(frame, dets)
                EventStateMachine.Action.STOP -> endEvent()
                EventStateMachine.Action.ROTATE -> rotateEvent()
                EventStateMachine.Action.NONE -> { }
            }
        }
    }

    private fun beginEvent(frame: Frame?, dets: List<Detection>) {
        val cfg = ConfigStore.current
        val nowTick = SystemClock.elapsedRealtime()
        if (nowTick < blockedUntil) {
            sm.forceStop()
            return
        }
        if (Storage.freeBytes() < 300_000_000L) {
            AppLog.w("Engine", "المساحة الحرة أقل من 300MB، لن يبدأ التسجيل")
            blockedUntil = nowTick + 15_000L
            sm.forceStop()
            return
        }
        val id = "ev_" + System.currentTimeMillis()
        if (!recorder.start(id)) {
            AppLog.w("Engine", "تعذّر بدء التسجيل (لا إطار مفتاحي بعد)")
            blockedUntil = nowTick + 1_000L
            sm.forceStop()
            return
        }
        val tr = EventTrack(id, System.currentTimeMillis(), engineName)
        trackers[id] = tr
        current = tr
        val label = dets.firstOrNull()?.let { Coco.labelAr(it.classId) } ?: "حدث"
        if (cfg.notify) Notifier.eventStart(app, id, label)
        if (cfg.alarm) AlarmPlayer.play(app)
        if (cfg.tgInstant && Telegram.ready(cfg)) Telegram.sendText(cfg, "رُصد $label — جارٍ التسجيل")
        AppLog.i("Engine", "بدأ حدث $id ($label)")
        MonitorBus.updateStats { it.copy(recording = true, eventStartMs = tr.startMs) }
    }

    private fun endEvent() {
        val c = current ?: return
        current = null
        recorder.stop()
        AppLog.i("Engine", "انتهى حدث ${c.id}")
        MonitorBus.updateStats { it.copy(recording = false, eventStartMs = 0L) }
    }

    private fun rotateEvent() {
        val old = current ?: return
        val id = "ev_" + System.currentTimeMillis()
        recorder.rotate(id)
        val tr = EventTrack(id, System.currentTimeMillis(), old.engine)
        trackers[id] = tr
        current = tr
        AppLog.i("Engine", "تقسيم الحدث ${old.id} إلى $id")
    }

    private fun trackUpdate(frame: Frame, dets: List<Detection>) {
        val tr = current ?: return
        if (dets.isEmpty()) return
        val best = dets.maxByOrNull { it.score } ?: return
        if (best.score > tr.maxScore) tr.maxScore = best.score
        for (d in dets) tr.classes.add(d.classId)
        if (best.score > tr.bestScore) {
            tr.bestScore = best.score
            tr.bestArgb = frame.argb
            tr.bestW = frame.width
            tr.bestH = frame.height
            tr.bestDets = dets
        }
    }

    private fun finalizeEvent(f: EventRecorder.Finished) {
        val tr = trackers.remove(f.id)
        if (!f.ok || f.bytes < 2048L) {
            f.file.delete()
            AppLog.w("Engine", "حدث مهمل ${f.id} (الحجم=${f.bytes})")
            return
        }
        val cfg = ConfigStore.current
        var hasThumb = false
        var thumb: Bitmap? = null
        val argb = tr?.bestArgb
        if (tr != null && argb != null) {
            try {
                val bmp = ImageUtil.bitmapFromArgb(argb, tr.bestW, tr.bestH)
                ImageUtil.drawBoxes(bmp, tr.bestDets)
                FileOutputStream(store.thumbFile(f.id)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                hasThumb = true
                thumb = bmp
            } catch (t: Throwable) {
                AppLog.w("Engine", "تعذّر حفظ الصورة المصغّرة", t)
            }
        }
        val endMs = System.currentTimeMillis()
        val labels = tr?.classes?.map { Coco.labelAr(it) }?.distinct()?.joinToString("، ") ?: ""
        val meta = EventMeta(
            id = f.id,
            startMs = tr?.startMs ?: (endMs - f.durationUs / 1000L),
            endMs = endMs,
            durationMs = f.durationUs / 1000L,
            maxScore = tr?.maxScore ?: 0f,
            labels = labels,
            sizeBytes = f.bytes + (if (hasThumb) store.thumbFile(f.id).length() else 0L),
            engine = tr?.engine ?: "",
            width = tr?.bestW ?: 0,
            height = tr?.bestH ?: 0,
            hasThumb = hasThumb
        )
        try {
            store.save(meta)
        } catch (t: Throwable) {
            AppLog.e("Engine", "تعذّر حفظ بيانات الحدث", t)
            return
        }
        store.enforceQuota(cfg.maxStorageGb * 1_000_000_000L, cfg.maxDays, trackers.keys.toSet())
        MonitorBus.eventsChanged.value = endMs
        if (cfg.notify) Notifier.eventDone(app, f.id, labels.ifEmpty { "حدث" }, meta.durationMs, thumb)
        if (Telegram.ready(cfg)) {
            val cap = "تم تسجيل حدث: " + labels.ifEmpty { "حدث" } + " — المدة " + (meta.durationMs / 1000) + " ث"
            if (cfg.tgPhoto && hasThumb) Telegram.sendPhoto(cfg, store.thumbFile(f.id), cap)
            if (cfg.tgVideo) Telegram.sendVideo(cfg, f.file, cap)
        }
    }
}
