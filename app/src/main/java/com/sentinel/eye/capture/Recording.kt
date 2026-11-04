package com.sentinel.eye.capture

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import com.sentinel.eye.core.AppLog
import com.sentinel.eye.core.EncodedSample
import com.sentinel.eye.core.EventStore
import com.sentinel.eye.core.SampleRing
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * يستقبل العينات المضغوطة باستمرار ويحتفظ بآخر N ثانية في ذاكرة دائرية (Pre-roll).
 * عند بدء حدث يكتب الذاكرة الدائرية ثم يتابع الكتابة حتى الإيقاف.
 */
class EventRecorder(private val store: EventStore) {

    class Finished(val id: String, val file: File, val durationUs: Long, val bytes: Long, val ok: Boolean)

    private class Session(val id: String, val file: File, val muxer: MediaMuxer, val track: Int, val basePts: Long) {
        var lastPts = -1L
        var count = 0
        var broken = false
        var audioTrack = -1
        var lastAudioPts = -1L
    }

    private val lock = Any()
    private val ring = SampleRing(6_000_000L)
    private val audioRing = SampleRing(6_000_000L)
    @Volatile private var audioFormat: MediaFormat? = null
    @Volatile private var format: MediaFormat? = null
    @Volatile private var orientationHint = 0
    private var session: Session? = null
    private var pendingRotateId: String? = null
    private val finisher = Executors.newSingleThreadExecutor()
    private val info = MediaCodec.BufferInfo()

    @Volatile
    var onFinished: ((Finished) -> Unit)? = null

    fun configure(preRollSec: Int, orientationHintDeg: Int) {
        ring.windowUs = preRollSec * 1_000_000L
        audioRing.windowUs = preRollSec * 1_000_000L
        orientationHint = orientationHintDeg
    }

    fun setFormat(f: MediaFormat) { format = f }

    fun setAudioFormat(f: MediaFormat) { audioFormat = f }

    fun clearAudio() {
        synchronized(lock) {
            audioRing.clear()
            audioFormat = null
        }
    }

    fun onAudioSample(s: EncodedSample) {
        synchronized(lock) {
            audioRing.add(s)
            val ss = session ?: return
            if (ss.audioTrack >= 0) writeAudio(ss, s)
        }
    }

    fun shutdown() { finisher.shutdown() }

    fun bufferSeconds(): Int = (ring.spanUs() / 1_000_000L).toInt()

    fun isRecording(): Boolean = synchronized(lock) { session != null }

    /** عند إعادة تشغيل الكاميرا/المرمّز: ينهي أي تسجيل ويفرّغ الذاكرة. */
    fun reset() {
        synchronized(lock) {
            stopLocked()
            ring.clear()
            audioRing.clear()
            format = null
        }
    }

    fun onSample(s: EncodedSample) {
        synchronized(lock) { onSampleLocked(s) }
    }

    private fun onSampleLocked(s: EncodedSample) {
        ring.add(s)
        val cur = session ?: return
        val rid = pendingRotateId
        if (rid != null && s.isKey) {
            detach(cur)
            pendingRotateId = null
            val ns = open(rid, s.ptsUs)
            session = ns
            if (ns != null) write(ns, s)
        } else {
            write(cur, s)
        }
    }

    fun start(id: String): Boolean = synchronized(lock) { startLocked(id) }

    private fun startLocked(id: String): Boolean {
        if (session != null) return true
        if (format == null) return false
        val snap = ring.snapshot()
        if (snap.isEmpty()) return false
        val ss = open(id, snap.first().ptsUs) ?: return false
        session = ss
        val aSnap = if (ss.audioTrack >= 0) audioRing.snapshot().filter { it.ptsUs >= snap.first().ptsUs } else emptyList()
        var ai = 0
        for (v in snap) {
            while (ai < aSnap.size && aSnap[ai].ptsUs <= v.ptsUs) {
                writeAudio(ss, aSnap[ai])
                ai++
            }
            write(ss, v)
        }
        while (ai < aSnap.size) {
            writeAudio(ss, aSnap[ai])
            ai++
        }
        return true
    }

    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        val cur = session
        session = null
        pendingRotateId = null
        if (cur != null) detach(cur)
    }

    /** يبدّل إلى ملف جديد عند أقرب إطار مفتاحي دون فقدان أي إطار. */
    fun rotate(newId: String) {
        synchronized(lock) { if (session != null) pendingRotateId = newId }
    }

    private fun open(id: String, basePts: Long): Session? {
        val f = format ?: return null
        val file = store.videoFile(id)
        return try {
            val m = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                m.setOrientationHint(orientationHint)
                val t = m.addTrack(f)
                var at = -1
                val af = audioFormat
                if (af != null) {
                    at = try { m.addTrack(af) } catch (e: Throwable) {
                        AppLog.w("Recorder", "تعذّر إضافة مسار الصوت", e)
                        -1
                    }
                }
                m.start()
                val sess = Session(id, file, m, t, basePts)
                sess.audioTrack = at
                sess
            } catch (e: Throwable) {
                try { m.release() } catch (_: Throwable) { }
                throw e
            }
        } catch (t: Throwable) {
            AppLog.e("Recorder", "تعذّر فتح ملف التسجيل", t)
            file.delete()
            null
        }
    }

    private fun write(ss: Session, s: EncodedSample) {
        if (ss.broken) return
        var pts = s.ptsUs - ss.basePts
        if (pts <= ss.lastPts) pts = ss.lastPts + 1
        if (pts < 0) pts = 0
        info.set(0, s.data.size, pts, if (s.isKey) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        try {
            ss.muxer.writeSampleData(ss.track, ByteBuffer.wrap(s.data), info)
            ss.lastPts = pts
            ss.count++
        } catch (t: Throwable) {
            ss.broken = true
            AppLog.e("Recorder", "فشلت كتابة عينة", t)
        }
    }

    private fun writeAudio(ss: Session, s: EncodedSample) {
        if (ss.broken || ss.audioTrack < 0) return
        var pts = s.ptsUs - ss.basePts
        if (pts < 0) return
        if (pts <= ss.lastAudioPts) pts = ss.lastAudioPts + 1
        info.set(0, s.data.size, pts, 0)
        try {
            ss.muxer.writeSampleData(ss.audioTrack, ByteBuffer.wrap(s.data), info)
            ss.lastAudioPts = pts
        } catch (t: Throwable) {
            AppLog.w("Recorder", "فشلت كتابة عينة صوت", t)
            ss.audioTrack = -1
        }
    }

    private fun detach(ss: Session) {
        finisher.execute { finalizeSession(ss) }
    }

    private fun finalizeSession(ss: Session) {
        var ok = !ss.broken && ss.count > 0
        try {
            ss.muxer.stop()
        } catch (t: Throwable) {
            ok = false
            AppLog.w("Recorder", "فشل إنهاء الملف", t)
        }
        try { ss.muxer.release() } catch (_: Throwable) { }
        val f = Finished(ss.id, ss.file, ss.lastPts.coerceAtLeast(0L), ss.file.length(), ok)
        try {
            onFinished?.invoke(f)
        } catch (t: Throwable) {
            AppLog.e("Recorder", "خطأ في معالجة الحدث المنتهي", t)
        }
    }
}
