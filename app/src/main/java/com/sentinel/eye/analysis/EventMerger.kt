package com.sentinel.eye.analysis

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.provider.MediaStore
import com.sentinel.eye.core.AppLog
import com.sentinel.eye.core.EventMeta
import com.sentinel.eye.core.Storage
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * يدمج عدة أحداث مسجَّلة في فيديو واحد دون إعادة ترميز: سريع، بلا فقد جودة، وموفّر للبطارية.
 * الدمج دون إعادة ترميز يتطلب أن تتطابق المقاطع في الترميز والدقة والدوران وإعدادات الترميز (SPS/PPS)،
 * لذلك يُفحص التوافق أولاً وتُجمَّع المقاطع المتوافقة معاً، ولا يُنتَج ملف تالف أبداً.
 */
object EventMerger {

    /** نتيجة فحص التوافق: مجموعات متوافقة (كل مجموعة مرتبة زمنياً)، وعدد المقاطع المفقودة. */
    class Analysis(val groups: List<List<EventMeta>>, val missing: Int) {
        /** المجموعة الأطول مدة (وعند التعادل الأكثر أحداثاً). */
        fun best(): List<EventMeta> =
            groups.maxWithOrNull(compareBy<List<EventMeta>>({ g -> g.sumOf { it.durationMs } }, { g -> g.size })) ?: emptyList()
    }

    class Result(val file: File, val count: Int, val durationMs: Long, val note: String)

    private class Sig(val video: String, val rotation: Int, val hasAudio: Boolean, val audio: String)

    private fun csd(f: MediaFormat, key: String): String {
        if (!f.containsKey(key)) return ""
        val b = f.getByteBuffer(key) ?: return ""
        val d = b.duplicate()
        d.rewind()
        val arr = ByteArray(d.remaining())
        d.get(arr)
        return arr.contentHashCode().toString(16) + ":" + arr.size
    }

    private fun intOf(f: MediaFormat, key: String): Int = if (f.containsKey(key)) f.getInteger(key) else 0

    private fun rotationOf(file: File): Int {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            val r = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            ((r % 360) + 360) % 360
        } catch (_: Throwable) {
            0
        } finally {
            try { mmr.release() } catch (_: Throwable) { }
        }
    }

    private fun signature(file: File): Sig? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.absolutePath)
            var video: String? = null
            var hasAudio = false
            var audio = ""
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && video == null) {
                    video = mime + "|" + intOf(f, MediaFormat.KEY_WIDTH) + "x" + intOf(f, MediaFormat.KEY_HEIGHT) +
                        "|" + csd(f, "csd-0") + "|" + csd(f, "csd-1")
                } else if (mime.startsWith("audio/") && !hasAudio) {
                    hasAudio = true
                    audio = mime + "|" + intOf(f, MediaFormat.KEY_SAMPLE_RATE) + "|" + intOf(f, MediaFormat.KEY_CHANNEL_COUNT) +
                        "|" + csd(f, "csd-0")
                }
            }
            val v = video ?: return null
            return Sig(v, rotationOf(file), hasAudio, audio)
        } catch (t: Throwable) {
            AppLog.w("Merger", "تعذّرت قراءة ${file.name}", t)
            return null
        } finally {
            try { ex.release() } catch (_: Throwable) { }
        }
    }

    /** يجمّع الأحداث المحددة إلى مجموعات يمكن دمج كل منها دون إعادة ترميز، بترتيب زمني تصاعدي. */
    fun analyze(events: List<EventMeta>): Analysis {
        val sorted = events.sortedBy { it.startMs }
        val keys = ArrayList<String>()
        val buckets = HashMap<String, ArrayList<EventMeta>>()
        var missing = 0
        for (m in sorted) {
            val f = Storage.events.videoFile(m.id)
            if (!f.exists() || f.length() == 0L) {
                missing++
                continue
            }
            val s = signature(f)
            if (s == null) {
                missing++
                continue
            }
            val k = s.video + "|r" + s.rotation
            if (!buckets.containsKey(k)) {
                buckets[k] = ArrayList()
                keys.add(k)
            }
            buckets[k]!!.add(m)
        }
        return Analysis(keys.map { buckets[it]!! as List<EventMeta> }, missing)
    }

    private fun pruneCache(dir: File) {
        val now = System.currentTimeMillis()
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (now - f.lastModified() > 24L * 3600_000L) f.delete()
        }
    }

    /**
     * يدمج مجموعة متوافقة (يُفترض أنها من [analyze]) في ملف MP4 واحد داخل ذاكرة التخزين المؤقت.
     * يرمي استثناءً برسالة عربية عند الفشل، و[CancelledException] عند الإلغاء، ويحذف الملف الناقص.
     */
    fun merge(ctx: Context, events: List<EventMeta>, cancel: AtomicBoolean, onProgress: (Float) -> Unit): Result {
        val list = events.sortedBy { it.startMs }
        if (list.size < 2) throw IllegalStateException("اختر حدثين على الأقل للدمج.")
        val files = list.map { Storage.events.videoFile(it.id) }
        for (f in files) if (!f.exists()) throw IllegalStateException("أحد المقاطع المحددة لم يعد موجوداً.")

        // التحقق من التوافق وقرار الصوت.
        val sigs = files.map { signature(it) ?: throw IllegalStateException("تعذّرت قراءة أحد المقاطع.") }
        val first = sigs[0]
        for (s in sigs) {
            if (s.video != first.video || s.rotation != first.rotation) {
                throw IllegalStateException("اختلفت إعدادات التسجيل بين المقاطع (الدقة أو الترميز أو الاتجاه)، فلا يمكن دمجها دون إعادة ترميز.")
            }
        }
        val audioKinds = sigs.filter { it.hasAudio }.map { it.audio }.distinct()
        val includeAudio = audioKinds.size == 1
        var note = ""
        if (audioKinds.size > 1) note = "حُذف الصوت لأن إعدادات الصوت اختلفت بين المقاطع."

        val dir = File(ctx.cacheDir, "merged")
        dir.mkdirs()
        pruneCache(dir)
        val out = File(dir, "merged_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4")

        var muxer: MediaMuxer? = null
        var started = false
        var ok = false
        try {
            val m = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m

            // مسارات الإخراج من تنسيقات أول ملف.
            var outVideo = -1
            var outAudio = -1
            var maxSize = 2 shl 20
            run {
                val ex0 = MediaExtractor()
                try {
                    ex0.setDataSource(files[0].absolutePath)
                    for (i in 0 until ex0.trackCount) {
                        val f = ex0.getTrackFormat(i)
                        val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                        if (mime.startsWith("video/")) {
                            outVideo = m.addTrack(f)
                            if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxSize = maxOf(maxSize, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                            break
                        }
                    }
                } finally {
                    try { ex0.release() } catch (_: Throwable) { }
                }
                // تنسيق الصوت من أول ملف يحويه (قد لا يكون الأول).
                val ai = if (includeAudio) sigs.indexOfFirst { it.hasAudio } else -1
                if (ai >= 0) {
                    val exA = MediaExtractor()
                    try {
                        exA.setDataSource(files[ai].absolutePath)
                        for (i in 0 until exA.trackCount) {
                            val f = exA.getTrackFormat(i)
                            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                            if (mime.startsWith("audio/")) {
                                outAudio = m.addTrack(f)
                                if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxSize = maxOf(maxSize, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                                break
                            }
                        }
                    } finally {
                        try { exA.release() } catch (_: Throwable) { }
                    }
                }
            }
            if (outVideo < 0) throw IllegalStateException("لا يوجد مسار فيديو.")
            m.setOrientationHint(first.rotation)
            m.start()
            started = true

            var buf = ByteBuffer.allocateDirect(maxSize)
            val info = MediaCodec.BufferInfo()
            var base = 0L
            var written = 0
            for ((fi, file) in files.withIndex()) {
                if (cancel.get()) throw CancelledException()
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(file.absolutePath)
                    var vIn = -1
                    var aIn = -1
                    for (i in 0 until ex.trackCount) {
                        val f = ex.getTrackFormat(i)
                        val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                        if (mime.startsWith("video/") && vIn < 0) {
                            vIn = i
                            ex.selectTrack(i)
                        } else if (includeAudio && mime.startsWith("audio/") && aIn < 0) {
                            aIn = i
                            ex.selectTrack(i)
                        }
                    }
                    if (vIn < 0) throw IllegalStateException("لا يوجد مسار فيديو في أحد المقاطع.")
                    var t0 = ex.sampleTime
                    if (t0 < 0) throw IllegalStateException("مقطع فارغ.")
                    val spanUs = maxOf(1L, list[fi].durationMs * 1000L)
                    var sawSync = false
                    var maxV = t0
                    var vCount = 0
                    var lastReport = 0L
                    while (true) {
                        if (cancel.get()) throw CancelledException()
                        val ti = ex.sampleTrackIndex
                        if (ti < 0) break
                        val t = ex.sampleTime
                        val isVideo = ti == vIn
                        // لا نبدأ الفيديو إلا من إطار مفتاحي حتى لا يتشوّه الانتقال بين المقاطع.
                        if (isVideo && !sawSync) {
                            if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) == 0) {
                                ex.advance()
                                continue
                            }
                            sawSync = true
                            // زمن أول إطار مفتاحي هو مرجع هذا المقطع، فلا تنشأ فجوة عند الوصلة.
                            t0 = t
                            maxV = t
                        }
                        if (!isVideo && (!sawSync || t < t0)) {
                            ex.advance()
                            continue
                        }
                        val need = ex.sampleSize
                        if (need > buf.capacity()) buf = ByteBuffer.allocateDirect((need * 3 / 2).toInt())
                        buf.clear()
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) break
                        val dst = if (isVideo) outVideo else outAudio
                        if (dst >= 0) {
                            val rel = if (t - t0 < 0) 0L else t - t0
                            info.set(0, size, base + rel, ex.sampleFlags)
                            m.writeSampleData(dst, buf, info)
                            written++
                        }
                        if (isVideo) {
                            if (t > maxV) maxV = t
                            vCount++
                        }
                        ex.advance()
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 250L) {
                            lastReport = now
                            val inFile = ((t - t0).toFloat() / spanUs.toFloat()).coerceIn(0f, 1f)
                            onProgress(((fi + inFile) / files.size).coerceIn(0f, 0.99f))
                        }
                    }
                    val avg = if (vCount > 1) (maxV - t0) / (vCount - 1) else 33_333L
                    val frame = avg.coerceIn(8_000L, 200_000L)
                    base += (maxV - t0) + frame
                } finally {
                    try { ex.release() } catch (_: Throwable) { }
                }
                onProgress(((fi + 1).toFloat() / files.size).coerceAtMost(0.99f))
            }
            if (written == 0) throw IllegalStateException("لم تُكتب أي عينة.")
            m.stop()
            started = false
            ok = true
            onProgress(1f)
            return Result(out, files.size, base / 1000L, note)
        } catch (c: CancelledException) {
            throw c
        } catch (e: IllegalStateException) {
            AppLog.e("Merger", "فشل الدمج", e)
            throw e
        } catch (t: Throwable) {
            AppLog.e("Merger", "فشل الدمج", t)
            throw IllegalStateException("تعذّر الدمج: " + (t.message ?: t.javaClass.simpleName))
        } finally {
            try { if (started) muxer?.stop() } catch (_: Throwable) { }
            try { muxer?.release() } catch (_: Throwable) { }
            if (!ok) out.delete()
        }
    }

    /** يحفظ نسخة من الفيديو المدموج في معرض الهاتف (Movies/SentinelAI). يعيد المسار أو null عند الفشل. */
    fun saveToGallery(ctx: Context, file: File): String? {
        val name = "Sentinel_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues()
        values.put(MediaStore.Video.Media.DISPLAY_NAME, name)
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SentinelAI")
        values.put(MediaStore.Video.Media.IS_PENDING, 1)
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri).use { os ->
                if (os == null) throw IOException("تعذّر فتح وجهة الحفظ")
                file.inputStream().use { it.copyTo(os, 256 * 1024) }
            }
            val done = ContentValues()
            done.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, done, null, null)
            "Movies/SentinelAI/$name"
        } catch (t: Throwable) {
            AppLog.e("Merger", "فشل الحفظ في المعرض", t)
            try { resolver.delete(uri, null, null) } catch (_: Throwable) { }
            null
        }
    }
}
