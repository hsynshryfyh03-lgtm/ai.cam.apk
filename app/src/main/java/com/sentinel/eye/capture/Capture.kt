package com.sentinel.eye.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Range
import android.util.Size
import android.view.Surface
import com.sentinel.eye.core.AppLog
import com.sentinel.eye.core.Config
import com.sentinel.eye.core.EncodedSample
import java.util.concurrent.Executor
import kotlin.math.abs

/** مرمّز فيديو بسطح إدخال (Surface) يعمل باستمرار ويُخرج عينات مضغوطة. */
class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val hevc: Boolean,
    private val onFormat: (MediaFormat) -> Unit,
    private val onSample: (EncodedSample) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val boost: Boolean = false
) {
    private var codec: MediaCodec? = null
    var inputSurface: Surface? = null
        private set
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        val mime = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        var lastErr: Throwable? = null
        for (vbr in booleanArrayOf(true, false)) {
            var created: MediaCodec? = null
            try {
                val fmt = MediaFormat.createVideoFormat(mime, width, height)
                fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                fmt.setInteger(MediaFormat.KEY_PRIORITY, 0)
                if (vbr) fmt.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                val c = MediaCodec.createEncoderByType(mime)
                created = c
                c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surf = c.createInputSurface()
                c.start()
                codec = c
                inputSurface = surf
                running = true
                val th = Thread({ drain(c) }, "enc-drain")
                thread = th
                th.start()
                return
            } catch (t: Throwable) {
                lastErr = t
                AppLog.w("Encoder", "فشل تهيئة المرمّز (vbr=$vbr)", t)
                try { created?.release() } catch (_: Throwable) { }
            }
        }
        throw lastErr ?: IllegalStateException("تعذّر تشغيل المرمّز")
    }

    private fun drain(c: MediaCodec) {
        if (boost) {
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) } catch (_: Throwable) { }
        }
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val idx = c.dequeueOutputBuffer(info, 10_000)
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) continue
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    onFormat(c.outputFormat)
                    continue
                }
                if (idx >= 0) {
                    val buf = c.getOutputBuffer(idx)
                    if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val data = ByteArray(info.size)
                        buf.get(data)
                        onSample(EncodedSample(data, info.presentationTimeUs, (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0))
                    }
                    c.releaseOutputBuffer(idx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        } catch (t: Throwable) {
            if (running) onError(t)
        }
    }

    fun stop() {
        running = false
        try { thread?.join(1500) } catch (_: InterruptedException) { }
        try { codec?.stop() } catch (_: Throwable) { }
        try { codec?.release() } catch (_: Throwable) { }
        try { inputSurface?.release() } catch (_: Throwable) { }
        codec = null
        inputSurface = null
    }
}

object EncoderCaps {
    fun supports(mime: String, w: Int, h: Int, fps: Int): Boolean {
        return try {
            val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            infos.any { ci ->
                ci.isEncoder && ci.supportedTypes.any { it.equals(mime, ignoreCase = true) } && run {
                    val vc = ci.getCapabilitiesForType(mime).videoCapabilities
                    vc != null && vc.isSizeSupported(w, h) && vc.areSizeAndRateSupported(w, h, fps.toDouble())
                }
            }
        } catch (_: Throwable) {
            true
        }
    }
}

data class CapturePlan(
    val cameraId: String,
    val front: Boolean,
    val sensorOrientation: Int,
    val videoSize: Size,
    val analysisSize: Size,
    val fpsRange: Range<Int>,
    val fps: Int,
    val realtimeTs: Boolean,
    val description: String
)

object Rot {
    /** التدوير (باتجاه عقارب الساعة) اللازم لجعل إطار المستشعر معتدلاً. */
    fun rotation(plan: CapturePlan, orientation: Int): Int =
        if (plan.front) (plan.sensorOrientation + orientation) % 360 else (plan.sensorOrientation - orientation + 360) % 360
}

object CapturePlanner {
    private fun ar(s: Size): Float = s.width.toFloat() / s.height

    fun plans(ctx: Context, cfg: Config): List<CapturePlan> {
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = mgr.cameraIdList
        val wantFacing = if (cfg.useFront) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        var chosen: String? = null
        for (cid in ids) {
            val c = mgr.getCameraCharacteristics(cid)
            if (c.get(CameraCharacteristics.LENS_FACING) == wantFacing) { chosen = cid; break }
        }
        val cameraId = chosen ?: ids.firstOrNull() ?: return emptyList()
        val ch = mgr.getCameraCharacteristics(cameraId)
        val front = ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        val sensor = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val realtime = ch.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()

        val raw = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        fun usable(s: Size) = s.height in 360..1080 && s.width <= 1920 &&
            EncoderCaps.supports(MediaFormat.MIMETYPE_VIDEO_AVC, s.width, s.height, cfg.fps)
        val wide = raw.filter { abs(ar(it) - 16f / 9f) < 0.03f && usable(it) }.sortedByDescending { it.height }
        val pool = if (wide.isNotEmpty()) wide else raw.filter { usable(it) }.sortedByDescending { it.height }
        if (pool.isEmpty()) return emptyList()

        val notAbove = pool.filter { it.height <= cfg.videoHeight }
        val ordered = (if (notAbove.isNotEmpty()) notAbove else pool.reversed()).distinctBy { it.height }.take(3)

        val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
        val range = pickRange(ranges, cfg.fps)
        val yuv = map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        if (yuv.isEmpty()) return emptyList()

        val facing = if (front) "أمامية" else "خلفية"
        return ordered.map { v ->
            val a = pickAnalysis(yuv, v, cfg.analysisWidth)
            CapturePlan(
                cameraId = cameraId, front = front, sensorOrientation = sensor,
                videoSize = v, analysisSize = a, fpsRange = range, fps = range.upper, realtimeTs = realtime,
                description = "${v.width}×${v.height} @${range.upper} · تحليل ${a.width}×${a.height} · كاميرا $facing"
            )
        }
    }

    private fun pickRange(ranges: List<Range<Int>>, fps: Int): Range<Int> {
        val exact = ranges.filter { it.upper == fps }.minByOrNull { it.lower }
        if (exact != null) return exact
        val below = ranges.filter { it.upper < fps }.maxByOrNull { it.upper }
        if (below != null) return below
        return ranges.minByOrNull { abs(it.upper - fps) } ?: Range(fps, fps)
    }

    private fun pickAnalysis(yuv: List<Size>, video: Size, targetW: Int): Size {
        val vr = ar(video)
        val same = yuv.filter { abs(ar(it) - vr) < 0.03f && it.width <= video.width && it.width >= 160 }
        val pool = if (same.isNotEmpty()) same else yuv.sortedBy { abs(ar(it) - vr) }.take(6)
        return pool.minByOrNull { abs(it.width - targetW) } ?: yuv.first()
    }

    /** وصف قدرات الكاميرات لشاشة التشخيص. */
    fun report(ctx: Context): String {
        return try {
            val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val sb = StringBuilder()
            for (id in mgr.cameraIdList) {
                val c = mgr.getCameraCharacteristics(id)
                val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "أمامية"
                    CameraCharacteristics.LENS_FACING_BACK -> "خلفية"
                    else -> "خارجية"
                }
                val level = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                    else -> "?"
                }
                sb.append("كاميرا $id: $facing · $level\n")
            }
            sb.toString().trim()
        } catch (t: Throwable) {
            "تعذّر قراءة الكاميرات: ${t.message}"
        }
    }
}

/**
 * جلسة كاميرا واحدة: Camera2 تغذّي مسارين في آن واحد:
 * مرمّز الفيديو (للتسجيل) و ImageReader (للتحليل).
 */
class CaptureRig(
    private val ctx: Context,
    val plan: CapturePlan,
    private val cfg: Config,
    private val sink: Sink
) {
    interface Sink {
        fun onFormat(rig: CaptureRig, f: MediaFormat)
        fun onSample(rig: CaptureRig, s: EncodedSample)
        fun onImage(rig: CaptureRig, image: Image)
        fun onStreaming(rig: CaptureRig)
        fun onFailure(rig: CaptureRig, stage: String, t: Throwable?, configFailure: Boolean)
    }

    val useHevc: Boolean = cfg.codec == 1 &&
        EncoderCaps.supports(MediaFormat.MIMETYPE_VIDEO_HEVC, plan.videoSize.width, plan.videoSize.height, plan.fps)

    private val thread = HandlerThread(
        "cam-rig",
        if (cfg.maxPerf) Process.THREAD_PRIORITY_URGENT_DISPLAY else Process.THREAD_PRIORITY_DEFAULT
    ).also { it.start() }
    private val handler = Handler(thread.looper)
    private var encoder: VideoEncoder? = null
    private var reader: ImageReader? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    @Volatile private var closed = false
    @Volatile private var reported = false
    @Volatile private var streaming = false

    private fun bitrate(): Int {
        val bpp = floatArrayOf(0.07f, 0.10f, 0.15f)[cfg.quality.coerceIn(0, 2)]
        var br = (plan.videoSize.width * plan.videoSize.height * plan.fps * bpp).toInt()
        if (useHevc) br = (br * 0.65f).toInt()
        return br.coerceIn(500_000, 20_000_000)
    }

    private fun fail(stage: String, t: Throwable?, configFailure: Boolean) {
        if (closed || reported) return
        reported = true
        AppLog.w("Rig", "فشل ($stage) ${plan.description}", t)
        sink.onFailure(this, stage, t, configFailure)
    }

    @SuppressLint("MissingPermission")
    fun start() {
        try {
            val enc = VideoEncoder(
                plan.videoSize.width, plan.videoSize.height, plan.fps, bitrate(), useHevc,
                { f -> sink.onFormat(this, f) },
                { s -> sink.onSample(this, s) },
                { t -> fail("encoder", t, false) },
                cfg.maxPerf
            )
            enc.start()
            encoder = enc
            val rd = ImageReader.newInstance(plan.analysisSize.width, plan.analysisSize.height, ImageFormat.YUV_420_888, 3)
            rd.setOnImageAvailableListener({ r -> onImageAvailable(r) }, handler)
            reader = rd
            val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            mgr.openCamera(plan.cameraId, deviceCallback, handler)
        } catch (t: Throwable) {
            fail("start", t, false)
        }
    }

    private fun onImageAvailable(r: ImageReader) {
        var img: Image? = null
        try {
            img = r.acquireLatestImage()
            if (img == null) return
            if (!streaming) {
                streaming = true
                sink.onStreaming(this)
            }
            sink.onImage(this, img)
        } catch (t: Throwable) {
            AppLog.w("Rig", "خطأ أثناء معالجة إطار", t)
        } finally {
            try { img?.close() } catch (_: Throwable) { }
        }
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            if (closed) {
                try { camera.close() } catch (_: Throwable) { }
                return
            }
            device = camera
            createSession(camera)
        }

        override fun onDisconnected(camera: CameraDevice) {
            try { camera.close() } catch (_: Throwable) { }
            fail("disconnected", null, false)
        }

        override fun onError(camera: CameraDevice, error: Int) {
            try { camera.close() } catch (_: Throwable) { }
            fail("camera-error-$error", null, false)
        }
    }

    private fun createSession(camera: CameraDevice) {
        try {
            val encSurface = encoder?.inputSurface ?: throw IllegalStateException("لا يوجد سطح للمرمّز")
            val rdSurface = reader?.surface ?: throw IllegalStateException("لا يوجد قارئ صور")
            val outputs = listOf(OutputConfiguration(encSurface), OutputConfiguration(rdSurface))
            val exec = Executor { r -> handler.post(r) }
            val cb = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (closed) {
                        try { s.close() } catch (_: Throwable) { }
                        return
                    }
                    session = s
                    startRepeating(camera, s, encSurface, rdSurface)
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    fail("configure", null, true)
                }
            }
            camera.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, exec, cb))
        } catch (t: Throwable) {
            fail("session", t, true)
        }
    }

    private fun startRepeating(camera: CameraDevice, s: CameraCaptureSession, encSurface: Surface, rdSurface: Surface) {
        try {
            val rb = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            rb.addTarget(encSurface)
            rb.addTarget(rdSurface)
            rb.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            rb.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, plan.fpsRange)
            val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val af = mgr.getCameraCharacteristics(plan.cameraId).get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            if (af != null && af.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
                rb.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }
            s.setRepeatingRequest(rb.build(), null, handler)
        } catch (t: Throwable) {
            fail("repeating", t, false)
        }
    }

    fun stop() {
        closed = true
        try { session?.stopRepeating() } catch (_: Throwable) { }
        try { session?.close() } catch (_: Throwable) { }
        try { device?.close() } catch (_: Throwable) { }
        try {
            reader?.setOnImageAvailableListener(null, null)
            reader?.close()
        } catch (_: Throwable) { }
        try { encoder?.stop() } catch (_: Throwable) { }
        try { thread.quitSafely() } catch (_: Throwable) { }
        session = null
        device = null
        reader = null
        encoder = null
    }
}
