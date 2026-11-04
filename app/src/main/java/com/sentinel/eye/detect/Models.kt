package com.sentinel.eye.detect

import android.content.Context
import android.net.Uri
import com.sentinel.eye.BuildConfig
import com.sentinel.eye.core.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class EngineType { YOLO, MEDIAPIPE, MLKIT }

enum class Accel(val id: Int, val title: String) {
    CPU(0, "CPU"),
    XNNPACK(1, "XNNPACK"),
    GPU_NNAPI(2, "GPU / NNAPI");

    companion object {
        fun of(id: Int): Accel = values().firstOrNull { it.id == id } ?: CPU
    }
}

data class ModelSpec(
    val id: String,
    val engine: EngineType,
    val title: String,
    val subtitle: String,
    val fileName: String,
    val url: String,
    val inputSize: Int,
    val approxMb: Int,
    val minBytes: Long
) {
    /** نموذج مستورد أو محوَّل من ملف المستخدم (أصنافه من الملف نفسه لا من COCO). */
    val isCustom: Boolean get() = id.startsWith(CustomModels.PREFIX)
}

object ModelCatalog {
    /** مصدر النماذج: إعداد المستخدم (owner/repo أو رابط كامل) ثم قيمة وقت البناء. */
    fun baseUrl(repoSetting: String): String {
        val r = repoSetting.trim()
        return when {
            r.startsWith("http://") || r.startsWith("https://") -> r.trimEnd('/')
            Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(r) -> "https://github.com/$r/releases/download/models"
            else -> BuildConfig.MODELS_BASE_URL.trimEnd('/')
        }
    }

    fun urlOf(spec: ModelSpec, repoSetting: String): String {
        if (spec.url.isNotEmpty()) return spec.url
        val b = baseUrl(repoSetting)
        return if (b.isEmpty()) "" else b + "/" + spec.fileName
    }

    private fun yolo(id: String, title: String, sub: String, size: Int, mb: Int, minMb: Int) = ModelSpec(
        id = id, engine = EngineType.YOLO, title = title, subtitle = sub,
        fileName = "$id.onnx",
        url = "",
        inputSize = size, approxMb = mb, minBytes = minMb * 1_000_000L
    )

    val all: List<ModelSpec> by lazy {
        listOf(
            yolo("yolo11n-320", "YOLO11 Nano · 320", "الأسرع، مناسب للهواتف المتوسطة", 320, 10, 4),
            yolo("yolo11n-640", "YOLO11 Nano · 640", "سريع مع تفاصيل أدق للأشخاص البعيدين", 640, 10, 4),
            yolo("yolo11s-320", "YOLO11 Small · 320", "متوازن بين السرعة والدقة", 320, 37, 15),
            yolo("yolo11s-640", "YOLO11 Small · 640", "دقة جيدة، أبطأ على المعالجات المتوسطة", 640, 37, 15),
            yolo("yolo11m-640", "YOLO11 Medium · 640", "دقة عالية، يحتاج جهازاً أقوى", 640, 80, 30),
            yolo("yolo11l-640", "YOLO11 Large · 640", "دقة عالية جداً، بطيء على هاتفك", 640, 100, 40),
            yolo("yolo11x-640", "YOLO11 XLarge · 640", "الأعلى دقة، بطيء جداً على الهواتف", 640, 228, 90),
            ModelSpec(
                id = "mlkit-pose", engine = EngineType.MLKIT,
                title = "ML Kit · الوضعية", subtitle = "مدمج بلا تنزيل · يؤكد جسماً بشرياً (شخص واحد)",
                fileName = "", url = "", inputSize = 256, approxMb = 0, minBytes = 0L
            ),
            ModelSpec(
                id = "mlkit-face", engine = EngineType.MLKIT,
                title = "ML Kit · الوجه", subtitle = "مدمج بلا تنزيل · الأخف، للوجوه الظاهرة فقط",
                fileName = "", url = "", inputSize = 256, approxMb = 0, minBytes = 0L
            ),
            ModelSpec(
                id = "mp-effdet-lite0", engine = EngineType.MEDIAPIPE,
                title = "EfficientDet-Lite0", subtitle = "خفيف ومستقر من Google (MediaPipe)",
                fileName = "efficientdet_lite0.tflite",
                url = "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float32/1/efficientdet_lite0.tflite",
                inputSize = 320, approxMb = 14, minBytes = 3_000_000L
            ),
            ModelSpec(
                id = "mp-effdet-lite2", engine = EngineType.MEDIAPIPE,
                title = "EfficientDet-Lite2", subtitle = "أدق من Lite0 وأبطأ (MediaPipe)",
                fileName = "efficientdet_lite2.tflite",
                url = "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite2/float32/1/efficientdet_lite2.tflite",
                inputSize = 448, approxMb = 24, minBytes = 6_000_000L
            )
        )
    }

    fun byId(id: String): ModelSpec? = all.firstOrNull { it.id == id } ?: CustomModels.find(id)
}

sealed class DlState {
    object Idle : DlState()
    data class Progress(val done: Long, val total: Long) : DlState()
    data class Failed(val message: String) : DlState()
}

/** تنزيل النماذج مع الاستئناف والتحقق، وإدارة الملفات المثبّتة. */
object ModelManager {
    private lateinit var dir: File
    private val pool = Executors.newFixedThreadPool(2)
    private val cancels = ConcurrentHashMap<String, AtomicBoolean>()
    private val active = ConcurrentHashMap<String, Boolean>()
    private val dl = MutableStateFlow<Map<String, DlState>>(emptyMap())
    private val ver = MutableStateFlow(0)

    val states: StateFlow<Map<String, DlState>> get() = dl
    /** يتغير كلما تغيّرت الملفات المثبّتة، لتحديث الواجهة. */
    val version: StateFlow<Int> get() = ver

    @Synchronized
    fun init(ctx: Context) {
        if (::dir.isInitialized) return
        dir = File(ctx.applicationContext.filesDir, "models")
        dir.mkdirs()
        CustomModels.init(dir)
    }

    fun fileOf(spec: ModelSpec): File = File(dir, spec.fileName.ifEmpty { "none" })
    fun isInstalled(spec: ModelSpec): Boolean =
        spec.engine == EngineType.MLKIT || fileOf(spec).let { it.exists() && it.length() >= spec.minBytes }
    fun installedBytes(spec: ModelSpec): Long = fileOf(spec).let { if (it.exists()) it.length() else 0L }

    private fun setState(id: String, s: DlState?) {
        synchronized(this) {
            val m = dl.value.toMutableMap()
            if (s == null) m.remove(id) else m[id] = s
            dl.value = m
        }
    }

    private fun bump() { synchronized(this) { ver.value = ver.value + 1 } }

    /** يُبلَّغ به عند تغيّر النماذج المخصّصة لتحديث الواجهات. */
    fun notifyChanged() = bump()

    fun isDownloading(id: String): Boolean = active.containsKey(id)

    fun download(spec: ModelSpec) {
        if (active.putIfAbsent(spec.id, true) != null) return
        cancels[spec.id] = AtomicBoolean(false)
        setState(spec.id, DlState.Progress(0, -1))
        pool.execute {
            try {
                doDownload(spec)
            } catch (t: Throwable) {
                AppLog.w("Models", "فشل تنزيل ${spec.id}", t)
                val msg = if (cancels[spec.id]?.get() == true) null else (t.message ?: t.javaClass.simpleName)
                setState(spec.id, if (msg == null) null else DlState.Failed(msg))
            } finally {
                active.remove(spec.id)
                cancels.remove(spec.id)
                bump()
            }
        }
    }

    fun cancel(id: String) { cancels[id]?.set(true) }

    fun delete(spec: ModelSpec) {
        cancel(spec.id)
        if (spec.isCustom) {
            CustomModels.delete(spec.id)
            setState(spec.id, null)
            bump()
            return
        }
        fileOf(spec).delete()
        File(dir, spec.fileName + ".part").delete()
        setState(spec.id, null)
        bump()
    }

    /** استيراد نموذج من ملف يختاره المستخدم (SAF). */
    fun importFrom(ctx: Context, spec: ModelSpec, uri: Uri) {
        if (active.putIfAbsent(spec.id, true) != null) return
        setState(spec.id, DlState.Progress(0, -1))
        pool.execute {
            val part = File(dir, spec.fileName + ".part")
            try {
                ctx.contentResolver.openInputStream(uri).use { ins ->
                    if (ins == null) throw IOException("تعذّر فتح الملف")
                    FileOutputStream(part, false).use { out ->
                        val buf = ByteArray(256 * 1024)
                        var done = 0L
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            setState(spec.id, DlState.Progress(done, -1))
                        }
                    }
                }
                finish(spec, part)
                setState(spec.id, null)
            } catch (t: Throwable) {
                AppLog.w("Models", "فشل استيراد ${spec.id}", t)
                part.delete()
                setState(spec.id, DlState.Failed(t.message ?: "فشل الاستيراد"))
            } finally {
                active.remove(spec.id)
                bump()
            }
        }
    }

    private fun finish(spec: ModelSpec, part: File) {
        if (part.length() < spec.minBytes) throw IOException("الملف أصغر من المتوقع لهذا النموذج")
        if (spec.engine == EngineType.MEDIAPIPE) {
            part.inputStream().use { ins ->
                val head = ByteArray(8)
                val n = ins.read(head)
                val ok = n == 8 && head[4] == 'T'.code.toByte() && head[5] == 'F'.code.toByte() &&
                    head[6] == 'L'.code.toByte() && head[7] == '3'.code.toByte()
                if (!ok) throw IOException("الملف ليس نموذج TFLite صالحاً")
            }
        }
        val target = fileOf(spec)
        target.delete()
        if (!part.renameTo(target)) throw IOException("تعذّر حفظ الملف")
    }

    internal fun open(start: String, rangeFrom: Long): HttpURLConnection {
        var u = start
        var hops = 0
        while (true) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "SentinelAI/1.0")
            if (rangeFrom > 0) c.setRequestProperty("Range", "bytes=$rangeFrom-")
            val code = c.responseCode
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                val loc = c.getHeaderField("Location") ?: throw IOException("إعادة توجيه بلا عنوان")
                u = URL(URL(u), loc).toString()
                c.disconnect()
                if (++hops > 6) throw IOException("إعادة توجيه كثيرة")
                continue
            }
            return c
        }
    }

    private fun doDownload(spec: ModelSpec) {
        val url = ModelCatalog.urlOf(spec, com.sentinel.eye.core.ConfigStore.current.modelsRepo)
        if (url.isEmpty()) throw IOException("مصدر النماذج غير مضبوط. من تبويب النماذج ← «مصدر النماذج» أدخل اسم مستودعك (owner/repo)، أو استخدم الاستيراد من ملف.")
        val cancelled = cancels[spec.id] ?: AtomicBoolean(false)
        val part = File(dir, spec.fileName + ".part")
        var existing = if (part.exists()) part.length() else 0L
        var c = open(url, existing)
        if (c.responseCode == 416) {
            part.delete(); existing = 0; c.disconnect(); c = open(url, 0)
        }
        val code = c.responseCode
        if (code != 200 && code != 206) {
            c.disconnect()
            throw IOException(if (code == 404) "النموذج غير موجود على الرابط (404). تأكد من تشغيل Export YOLO models في مستودعك ومن أن المستودع عام." else "خطأ HTTP $code")
        }
        val append = code == 206 && existing > 0
        if (!append) existing = 0
        val len = c.contentLengthLong
        val total = if (len > 0) existing + len else -1L
        var done = existing
        var lastReport = 0L
        c.inputStream.use { ins ->
            FileOutputStream(part, append).use { out ->
                val buf = ByteArray(128 * 1024)
                while (true) {
                    if (cancelled.get()) throw IOException("أُلغي التنزيل")
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val now = System.currentTimeMillis()
                    if (now - lastReport > 250) {
                        lastReport = now
                        setState(spec.id, DlState.Progress(done, total))
                    }
                }
            }
        }
        c.disconnect()
        if (total > 0 && part.length() != total) throw IOException("التنزيل غير مكتمل، أعد المحاولة للاستئناف")
        try {
            finish(spec, part)
        } catch (t: Throwable) {
            part.delete()
            throw t
        }
        CustomModels.onDownloaded(spec)
        setState(spec.id, null)
    }
}
