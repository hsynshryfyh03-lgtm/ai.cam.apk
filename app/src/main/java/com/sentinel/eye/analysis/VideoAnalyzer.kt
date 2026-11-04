package com.sentinel.eye.analysis

import android.content.Context
import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import com.sentinel.eye.core.AppLog
import com.sentinel.eye.core.FrameConverter
import com.sentinel.eye.detect.Detector
import com.sentinel.eye.detect.Frame
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class Progress(val doneSec: Int, val totalSec: Int, val hits: Int, val frames: Int)

class AnalysisResult(
    val durationSec: Int,
    val hits: BooleanArray,
    val labels: Set<Int>,
    val frames: Int,
    val elapsedMs: Long,
    val usedFallback: Boolean
)

class CancelledException : RuntimeException("أُلغي التحليل")

class Probe(val durationUs: Long, val rotation: Int, val width: Int, val height: Int, val fps: Int) {
    val durationSec: Int get() = max(1, ceil(durationUs / 1_000_000.0).toInt())
}

/**
 * محلّل الفيديو الموفّر للطاقة: فكّ ترميز تسلسلي بالعتاد (بلا قفزات)،
 * مع تحويل 3 إطارات فقط في الثانية إلى صورة وتحليلها، وتخطّي بقية الثانية عند أول إصابة.
 */
class VideoAnalyzer(private val ctx: Context) {

    fun probe(uri: Uri): Probe {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(ctx, uri)
            val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val fps = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()?.toInt() ?: 0
            if (durMs <= 0L) throw IllegalStateException("تعذّر قراءة مدة الفيديو")
            return Probe(durMs * 1000L, ((rot % 360) + 360) % 360, w, h, fps)
        } finally {
            try { mmr.release() } catch (_: Throwable) { }
        }
    }

    fun analyze(
        uri: Uri, det: Detector, allowed: IntArray, minScore: Float, threeFrames: Boolean,
        targetW: Int, cancel: AtomicBoolean, onProgress: (Progress) -> Unit
    ): AnalysisResult {
        val t0 = SystemClock.elapsedRealtime()
        val p = probe(uri)
        return try {
            decodePath(uri, p, det, allowed, minScore, threeFrames, targetW, cancel, onProgress, t0)
        } catch (c: CancelledException) {
            throw c
        } catch (t: Throwable) {
            AppLog.w("Analyzer", "فشل مسار فكّ الترميز، التحويل إلى مسار الاستخراج الزمني", t)
            retrieverPath(uri, p, det, allowed, minScore, threeFrames, targetW, cancel, onProgress, t0)
        }
    }

    private fun readFps(f: MediaFormat, probeFps: Int): Int {
        var v = 0
        try {
            v = f.getInteger(MediaFormat.KEY_FRAME_RATE)
        } catch (_: Throwable) {
            try { v = f.getFloat(MediaFormat.KEY_FRAME_RATE).toInt() } catch (_: Throwable) { }
        }
        if (v <= 0) v = probeFps
        if (v <= 0) v = 30
        return v.coerceIn(5, 120)
    }

    private fun copyPlane(buf: ByteBuffer, cur: ByteArray): ByteArray {
        val n = buf.remaining()
        val arr = if (cur.size >= n) cur else ByteArray(n)
        buf.duplicate().get(arr, 0, n)
        return arr
    }

    private inner class Run(
        val det: Detector, val allowed: IntArray, val minScore: Float, val three: Boolean,
        val targetW: Int, val rotation: Int, fps: Int, val totalSec: Int,
        val onProgress: (Progress) -> Unit
    ) {
        val sampler = SecondSampler(fps, three)
        val hits = BooleanArray(totalSec + 2)
        val seen = HashSet<Int>()
        var frames = 0
        var hitCount = 0
        private var scratch = IntArray(0)
        private var tail = IntArray(0)
        private var tailW = 0
        private var tailH = 0
        private var tailValid = false
        private var yA = ByteArray(0)
        private var uA = ByteArray(0)
        private var vA = ByteArray(0)
        private var step = 0
        private var cw = 0
        private var ch = 0

        private fun convertTo(image: Image, dst: IntArray): IntArray {
            val pl = image.planes
            yA = copyPlane(pl[0].buffer, yA)
            uA = copyPlane(pl[1].buffer, uA)
            vA = copyPlane(pl[2].buffer, vA)
            val w = image.width
            val h = image.height
            if (step == 0) step = max(1, max(w, h) / max(160, targetW))
            val ow = FrameConverter.outWidth(w, h, rotation, step)
            val oh = FrameConverter.outHeight(w, h, rotation, step)
            val arr = if (dst.size >= ow * oh) dst else IntArray(ow * oh)
            FrameConverter.convert(
                yA, pl[0].rowStride, pl[0].pixelStride, uA, vA, pl[1].rowStride, pl[1].pixelStride,
                w, h, rotation, false, arr, step
            )
            cw = ow
            ch = oh
            return arr
        }

        private fun hitIn(arr: IntArray, w: Int, h: Int): Boolean {
            val r = det.detect(Frame(arr, w, h, 0L), minScore, allowed)
            for (d in r) seen.add(d.classId)
            return r.isNotEmpty()
        }

        private fun mark(sec: Int) {
            if (sec in hits.indices && !hits[sec]) {
                hits[sec] = true
                hitCount++
            }
            sampler.decided = true
        }

        fun beforeFrame(relUs: Long): SecondSampler.Act {
            if (sampler.wouldChange(relUs)) finishSecond()
            return sampler.onFrame(relUs)
        }

        fun handle(act: SecondSampler.Act, image: Image) {
            if (act == SecondSampler.Act.ANALYZE) {
                scratch = convertTo(image, scratch)
                frames++
                if (hitIn(scratch, cw, ch)) mark(sampler.curSec)
            } else if (act == SecondSampler.Act.TAIL) {
                tail = convertTo(image, tail)
                tailW = cw
                tailH = ch
                tailValid = true
            }
        }

        fun finishSecond() {
            val sec = sampler.curSec
            if (sec >= 0 && three && !sampler.decided && tailValid) {
                frames++
                if (hitIn(tail, tailW, tailH)) mark(sec)
            }
            tailValid = false
            if (sec >= 0) onProgress(Progress(min(sec + 1, totalSec), totalSec, hitCount, frames))
        }
    }

    private fun decodePath(
        uri: Uri, p: Probe, det: Detector, allowed: IntArray, minScore: Float, three: Boolean,
        targetW: Int, cancel: AtomicBoolean, onProgress: (Progress) -> Unit, t0: Long
    ): AnalysisResult {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("video/")) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) throw IllegalStateException("لا يوجد مسار فيديو")
            ex.selectTrack(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: throw IllegalStateException("نوع الفيديو مجهول")
            val fps = readFps(fmt, p.fps)
            var rot = p.rotation
            if (rot == 0 && fmt.containsKey(MediaFormat.KEY_ROTATION)) {
                rot = try { fmt.getInteger(MediaFormat.KEY_ROTATION) } catch (_: Throwable) { 0 }
            }
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()

            val run = Run(det, allowed, minScore, three, targetW, rot, fps, p.durationSec, onProgress)
            val bi = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            var base = -1L
            var idle = 0
            while (!outDone) {
                if (cancel.get()) throw CancelledException()
                if (!inDone) {
                    val ii = c.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = c.getInputBuffer(ii)
                        val size = if (buf == null) -1 else ex.readSampleData(buf, 0)
                        if (size < 0) {
                            c.queueInputBuffer(ii, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            c.queueInputBuffer(ii, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = c.dequeueOutputBuffer(bi, 10_000)
                if (oi >= 0) {
                    idle = 0
                    if (bi.size > 0) {
                        val pts = bi.presentationTimeUs
                        if (base < 0) base = pts
                        val rel = (pts - base).coerceAtLeast(0L)
                        val act = run.beforeFrame(rel)
                        if (act != SecondSampler.Act.SKIP) {
                            val img = c.getOutputImage(oi) ?: throw IllegalStateException("لا توجد صورة من المفكّك")
                            try {
                                if (img.format != ImageFormat.YUV_420_888) throw IllegalStateException("صيغة ألوان غير مدعومة")
                                run.handle(act, img)
                            } finally {
                                img.close()
                            }
                        }
                    }
                    c.releaseOutputBuffer(oi, false)
                    if ((bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
                } else if (oi == MediaCodec.INFO_TRY_AGAIN_LATER && inDone) {
                    idle++
                    if (idle > 800) outDone = true
                }
            }
            run.finishSecond()
            return AnalysisResult(p.durationSec, run.hits, run.seen, run.frames, SystemClock.elapsedRealtime() - t0, false)
        } finally {
            try { codec?.stop() } catch (_: Throwable) { }
            try { codec?.release() } catch (_: Throwable) { }
            try { ex.release() } catch (_: Throwable) { }
        }
    }

    /** مسار بديل أبطأ: استخراج إطارات بالزمن عبر MediaMetadataRetriever. */
    private fun retrieverPath(
        uri: Uri, p: Probe, det: Detector, allowed: IntArray, minScore: Float, three: Boolean,
        targetW: Int, cancel: AtomicBoolean, onProgress: (Progress) -> Unit, t0: Long
    ): AnalysisResult {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(ctx, uri)
            val total = p.durationSec
            val hits = BooleanArray(total + 2)
            val seen = HashSet<Int>()
            var frames = 0
            var hc = 0
            val rotated = p.rotation == 90 || p.rotation == 270
            val dispW = if (rotated) p.height else p.width
            val dispH = if (rotated) p.width else p.height
            val longSide = max(dispW, dispH).coerceAtLeast(1)
            val scale = min(1f, max(160, targetW).toFloat() / longSide)
            val dw = max(16, (dispW * scale).toInt())
            val dh = max(16, (dispH * scale).toInt())
            for (s in 0 until total) {
                if (cancel.get()) throw CancelledException()
                val base = s * 1_000_000L
                val times = if (three) longArrayOf(base, base + 500_000L, base + 900_000L) else longArrayOf(base)
                var hit = false
                for (t in times) {
                    if (t >= p.durationUs) continue
                    val bmp = mmr.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST, dw, dh) ?: continue
                    val w = bmp.width
                    val h = bmp.height
                    val arr = IntArray(w * h)
                    bmp.getPixels(arr, 0, w, 0, 0, w, h)
                    bmp.recycle()
                    frames++
                    val r = det.detect(Frame(arr, w, h, 0L), minScore, allowed)
                    if (r.isNotEmpty()) {
                        for (d in r) seen.add(d.classId)
                        hit = true
                        break
                    }
                }
                if (hit) {
                    hits[s] = true
                    hc++
                }
                onProgress(Progress(s + 1, total, hc, frames))
            }
            return AnalysisResult(total, hits, seen, frames, SystemClock.elapsedRealtime() - t0, true)
        } finally {
            try { mmr.release() } catch (_: Throwable) { }
        }
    }
}
