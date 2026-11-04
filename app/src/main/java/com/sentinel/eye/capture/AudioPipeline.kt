package com.sentinel.eye.capture

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import com.sentinel.eye.core.AppLog
import com.sentinel.eye.core.EncodedSample
import kotlin.math.max

/**
 * التقاط صوت من الميكروفون وترميزه AAC باستمرار.
 * الطوابع الزمنية تُحسب بساعة BOOTTIME ثم تُحوَّل إلى ساعة الفيديو عبر [offsetProvider]
 * (0 إن كانت ساعة الكاميرا REALTIME، أو إزاحة تُقدَّر عند أول عينة فيديو).
 */
class AudioPipeline(
    private val offsetProvider: () -> Long?,
    private val onFormat: (MediaFormat) -> Unit,
    private val onSample: (EncodedSample) -> Unit,
    private val onError: (Throwable) -> Unit
) {
    private val sampleRate = 44100
    private var rec: AudioRecord? = null
    private var codec: MediaCodec? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) throw IllegalStateException("الميكروفون غير متاح")
        val bufSize = max(minBuf * 2, 8192)
        val ar = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .build()
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            throw IllegalStateException("تعذّر تهيئة الميكروفون")
        }
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1)
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufSize)
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
        } catch (t: Throwable) {
            try { c.release() } catch (_: Throwable) { }
            ar.release()
            throw t
        }
        rec = ar
        codec = c
        running = true
        val th = Thread({ loop(ar, c) }, "audio-pipe")
        thread = th
        th.start()
    }

    private fun baseMicros(ar: AudioRecord, framesBefore: Long, n: Int): Long {
        val ts = AudioTimestamp()
        val ok = try {
            ar.getTimestamp(ts, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS
        } catch (_: Throwable) {
            false
        }
        return if (ok) {
            ts.nanoTime / 1000L - ts.framePosition * 1_000_000L / sampleRate
        } else {
            SystemClock.elapsedRealtimeNanos() / 1000L - (framesBefore + n / 2) * 1_000_000L / sampleRate
        }
    }

    private fun loop(ar: AudioRecord, c: MediaCodec) {
        try {
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Throwable) { }
            val buf = ByteArray(4096)
            val bi = MediaCodec.BufferInfo()
            ar.startRecording()
            var frames = 0L
            var base = Long.MIN_VALUE
            while (running) {
                val n = ar.read(buf, 0, buf.size)
                if (n <= 0) {
                    if (n < 0 && running) throw IllegalStateException("خطأ قراءة الميكروفون ($n)")
                    continue
                }
                if (base == Long.MIN_VALUE) base = baseMicros(ar, frames, n)
                val ptsBoot = base + frames * 1_000_000L / sampleRate
                frames += n / 2
                val ii = c.dequeueInputBuffer(20_000)
                if (ii >= 0) {
                    val ib = c.getInputBuffer(ii)
                    if (ib != null) {
                        ib.clear()
                        ib.put(buf, 0, n)
                        c.queueInputBuffer(ii, 0, n, ptsBoot, 0)
                    }
                }
                drain(c, bi)
            }
        } catch (t: Throwable) {
            if (running) {
                AppLog.w("Audio", "توقف مسار الصوت", t)
                onError(t)
            }
        }
    }

    private fun drain(c: MediaCodec, bi: MediaCodec.BufferInfo) {
        while (true) {
            val oi = c.dequeueOutputBuffer(bi, 0)
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                onFormat(c.outputFormat)
            } else if (oi >= 0) {
                if (bi.size > 0 && (bi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    val ob = c.getOutputBuffer(oi)
                    val off = offsetProvider()
                    if (ob != null && off != null) {
                        ob.position(bi.offset)
                        ob.limit(bi.offset + bi.size)
                        val d = ByteArray(bi.size)
                        ob.get(d)
                        onSample(EncodedSample(d, bi.presentationTimeUs + off, true))
                    }
                }
                c.releaseOutputBuffer(oi, false)
            } else {
                break
            }
        }
    }

    fun stop() {
        running = false
        try { thread?.join(1500) } catch (_: InterruptedException) { }
        try { rec?.stop() } catch (_: Throwable) { }
        try { rec?.release() } catch (_: Throwable) { }
        try { codec?.stop() } catch (_: Throwable) { }
        try { codec?.release() } catch (_: Throwable) { }
        rec = null
        codec = null
    }
}
