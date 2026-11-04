package com.sentinel.eye.analysis

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import com.sentinel.eye.core.AppLog
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * يقصّ المقاطع المحددة ويدمجها في ملف واحد دون إعادة ترميز (سريع، بلا فقد جودة، وموفّر للطاقة).
 * القص يبدأ من أقرب إطار مفتاحي سابق، فقد يبدأ المقطع قبل لحظته بجزء من ثانية.
 */
object ClipExporter {

    fun export(
        ctx: Context, uri: Uri, segments: List<Segment>, out: File,
        cancel: AtomicBoolean, onProgress: (Float) -> Unit
    ): Boolean {
        if (segments.isEmpty()) return false
        val ex = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            ex.setDataSource(ctx, uri, null)
            val m = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            val trackMap = HashMap<Int, Int>()
            var videoSrc = -1
            var audioSrc = -1
            var maxSize = 1 shl 20
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                val isVideo = mime.startsWith("video/")
                val isAudio = mime.startsWith("audio/")
                if ((isVideo && videoSrc < 0) || (isAudio && audioSrc < 0)) {
                    try {
                        val mi = m.addTrack(f)
                        trackMap[i] = mi
                        ex.selectTrack(i)
                        if (isVideo) videoSrc = i else audioSrc = i
                        if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                            maxSize = maxOf(maxSize, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                        }
                    } catch (t: Throwable) {
                        AppLog.w("Exporter", "تخطّي مسار غير مدعوم في الحاوية: $mime", t)
                    }
                }
            }
            if (videoSrc < 0) throw IllegalStateException("لا يوجد مسار فيديو قابل للتصدير")

            var rot = 0
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(ctx, uri)
                rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            } catch (_: Throwable) {
            } finally {
                try { mmr.release() } catch (_: Throwable) { }
            }
            m.setOrientationHint(((rot % 360) + 360) % 360)
            m.start()
            started = true

            var buf = ByteBuffer.allocateDirect(maxOf(maxSize, 2 shl 20))
            val info = MediaCodec.BufferInfo()
            var outBase = 0L
            var written = 0
            for ((si, seg) in segments.withIndex()) {
                if (cancel.get()) throw CancelledException()
                val startUs = seg.startSec * 1_000_000L
                val endUs = seg.endSec * 1_000_000L
                ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                var segStart = -1L
                while (true) {
                    val ti = ex.sampleTrackIndex
                    if (ti < 0) break
                    val t = ex.sampleTime
                    if (t >= endUs) break
                    if (ti == videoSrc && segStart < 0) segStart = t
                    if (segStart < 0 || t < segStart) {
                        ex.advance()
                        continue
                    }
                    val need = ex.sampleSize
                    if (need > buf.capacity()) buf = ByteBuffer.allocateDirect((need * 3 / 2).toInt())
                    val size = ex.readSampleData(buf, 0)
                    if (size < 0) break
                    val dst = trackMap[ti]
                    if (dst != null) {
                        info.set(0, size, outBase + (t - segStart), ex.sampleFlags)
                        m.writeSampleData(dst, buf, info)
                        written++
                    }
                    ex.advance()
                }
                if (segStart >= 0) outBase += (endUs - segStart)
                onProgress((si + 1).toFloat() / segments.size)
            }
            if (written == 0) throw IllegalStateException("لم تُكتب أي عينة")
            return true
        } catch (c: CancelledException) {
            throw c
        } catch (t: Throwable) {
            AppLog.e("Exporter", "فشل التصدير", t)
            return false
        } finally {
            try { if (started) muxer?.stop() } catch (_: Throwable) { }
            try { muxer?.release() } catch (_: Throwable) { }
            try { ex.release() } catch (_: Throwable) { }
        }
    }
}
