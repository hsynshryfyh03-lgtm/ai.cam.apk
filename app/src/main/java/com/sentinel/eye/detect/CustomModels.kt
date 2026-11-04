package com.sentinel.eye.detect

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.net.Uri
import com.sentinel.eye.core.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** حالة نافذة الاستيراد. */
sealed class ImportUi {
    object Idle : ImportUi()
    data class Working(val text: String) : ImportUi()
    data class Done(val spec: ModelSpec, val note: String) : ImportUi()
    data class Pt(val report: PtReport) : ImportUi()
    data class Failed(val message: String) : ImportUi()
}

/** نموذج محوَّل جاهز للتنزيل من مستودع المستخدم (custom-index.json). */
class RemoteCustom(
    val id: String, val title: String, val file: String, val size: Long,
    val task: String, val imgsz: Int, val names: List<String>, val end2end: Boolean
)

/**
 * النماذج المخصّصة: كل نموذج ملف ONNX وملف JSON مرافق (الأصناف، المهمة، الحجم) داخل models/custom.
 * يدعم أي نموذج YOLO مُصدَّر إلى ONNX. أما ملفات .pt فتُفحَص بأمان ثم تُحوَّل عبر سير عمل GitHub.
 */
object CustomModels {
    const val PREFIX = "custom-"

    private var dir: File? = null
    private val state = MutableStateFlow<List<ModelSpec>>(emptyList())
    private val metas = ConcurrentHashMap<String, YoloMeta>()
    private val ui = MutableStateFlow<ImportUi>(ImportUi.Idle)
    private val pool = Executors.newSingleThreadExecutor()

    private val remote = MutableStateFlow<List<RemoteCustom>>(emptyList())
    private val remoteStatus = MutableStateFlow("")
    private val remoteById = ConcurrentHashMap<String, RemoteCustom>()

    val specs: StateFlow<List<ModelSpec>> get() = state
    val importUi: StateFlow<ImportUi> get() = ui
    val remoteList: StateFlow<List<RemoteCustom>> get() = remote
    val remoteMessage: StateFlow<String> get() = remoteStatus

    @Synchronized
    fun init(modelsDir: File) {
        if (dir != null) return
        val d = File(modelsDir, "custom")
        d.mkdirs()
        dir = d
        reload()
    }

    fun find(id: String): ModelSpec? = state.value.firstOrNull { it.id == id }
    fun metaOf(id: String): YoloMeta? = metas[id]
    fun dismiss() {
        ui.value = ImportUi.Idle
    }

    private fun taskAr(t: String): String = when (t) {
        "segment" -> "تقسيم"
        "pose" -> "وضعية"
        "obb" -> "كشف مائل"
        else -> "كشف"
    }

    private fun specOf(id: String, title: String, task: String, nc: Int, imgsz: Int, size: Long): ModelSpec = ModelSpec(
        id = id, engine = EngineType.YOLO, title = title,
        subtitle = "مخصص · " + taskAr(task) + " · " + nc + " صنف · " + imgsz,
        fileName = "custom/$id.onnx", url = "", inputSize = imgsz,
        approxMb = ((size + 999_999L) / 1_000_000L).toInt().coerceAtLeast(1), minBytes = 1L
    )

    @Synchronized
    private fun reload() {
        val d = dir ?: return
        metas.clear()
        val found = ArrayList<Pair<Long, ModelSpec>>()
        val files = d.listFiles() ?: emptyArray()
        for (f in files) {
            if (!f.name.endsWith(".json")) continue
            try {
                val j = JSONObject(f.readText())
                val id = j.getString("id")
                val onnx = File(d, "$id.onnx")
                if (!onnx.exists()) continue
                val names = ArrayList<String>()
                val arr = j.optJSONArray("names")
                if (arr != null) for (i in 0 until arr.length()) names.add(arr.optString(i))
                val task = j.optString("task", "detect")
                val imgsz = j.optInt("imgsz", 640)
                metas[id] = YoloMeta(names, task, imgsz, j.optBoolean("end2end", false))
                found.add(Pair(j.optLong("added", 0L), specOf(id, j.optString("title", id), task, names.size, imgsz, onnx.length())))
            } catch (t: Throwable) {
                AppLog.w("Custom", "تعذّرت قراءة ${f.name}", t)
            }
        }
        found.sortByDescending { it.first }
        state.value = found.map { it.second }
    }

    private fun saveSidecar(
        id: String, title: String, task: String, imgsz: Int, names: List<String>, end2end: Boolean, source: String
    ) {
        val d = dir ?: return
        val j = JSONObject()
        j.put("id", id)
        j.put("title", title)
        j.put("task", task)
        j.put("imgsz", imgsz)
        j.put("end2end", end2end)
        val arr = JSONArray()
        for (n in names) arr.put(n)
        j.put("names", arr)
        j.put("source", source)
        j.put("added", System.currentTimeMillis())
        File(d, "$id.json").writeText(j.toString())
    }

    fun delete(id: String) {
        val d = dir ?: return
        File(d, "$id.onnx").delete()
        File(d, "$id.onnx.part").delete()
        File(d, "$id.json").delete()
        reload()
    }

    /** يُستدعى بعد اكتمال تنزيل نموذج محوَّل من المستودع لتسجيله بأصنافه. */
    fun onDownloaded(spec: ModelSpec) {
        if (!spec.isCustom) return
        val r = remoteById[spec.id] ?: return
        saveSidecar(r.id, r.title, r.task, r.imgsz, r.names, r.end2end, "github")
        reload()
    }

    // ───────────── الاستيراد من ملف ─────────────

    private fun slug(name: String): String {
        val base = name.substringBeforeLast('.', name).lowercase()
        val s = base.replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)
        return if (s.isEmpty()) "model-" + (name.hashCode() and 0xFFFF).toString(16) else s
    }

    private fun humanTitle(name: String): String = name.substringBeforeLast('.', name).trim().ifEmpty { "نموذج مخصص" }

    /** يستورد ملفاً يختاره المستخدم: ONNX يُسجَّل مباشرة، و.pt يُفحَص ويُعرَض تقريره. */
    fun importFile(ctx: Context, uri: Uri, displayName: String) {
        val d = dir
        if (d == null) {
            ui.value = ImportUi.Failed("مدير النماذج غير جاهز بعد، أعد المحاولة.")
            return
        }
        ui.value = ImportUi.Working("جارٍ نسخ الملف…")
        pool.execute {
            val tmp = File(d, ".import-" + System.currentTimeMillis() + ".tmp")
            try {
                ctx.contentResolver.openInputStream(uri).use { ins ->
                    if (ins == null) throw IOException("تعذّر فتح الملف")
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        var done = 0L
                        var lastUi = 0L
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - lastUi > 300L) {
                                lastUi = now
                                ui.value = ImportUi.Working("جارٍ نسخ الملف… " + (done / 1_000_000L) + " MB")
                            }
                        }
                    }
                }
                val lower = displayName.lowercase()
                val head = ByteArray(4)
                val hn = tmp.inputStream().use { it.read(head) }
                val zip = hn >= 2 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()
                if (zip || lower.endsWith(".pt") || lower.endsWith(".pth")) {
                    ui.value = ImportUi.Working("جارٍ فحص الملف بأمان (دون تشغيل أي شيفرة)…")
                    ui.value = ImportUi.Pt(PtInspector.inspect(tmp, displayName))
                    return@execute
                }
                if (lower.endsWith(".tflite") || lower.endsWith(".engine") || lower.endsWith(".mlmodel") ||
                    lower.endsWith(".torchscript") || lower.endsWith(".pb") || lower.endsWith(".bin")
                ) {
                    throw IOException("هذه الصيغة غير مدعومة للنماذج المخصّصة. المدعوم: ONNX مباشرة، و.pt عبر التحويل.")
                }
                ui.value = ImportUi.Working("جارٍ التحقق من النموذج وتشغيله تجريبياً…")
                val p = probe(tmp)
                val id = PREFIX + slug(displayName)
                val target = File(d, "$id.onnx")
                target.delete()
                if (!tmp.renameTo(target)) throw IOException("تعذّر حفظ الملف")
                saveSidecar(id, humanTitle(displayName), p.meta.task.ifEmpty { "detect" }, p.imgsz, p.meta.names, p.meta.end2end, "import")
                reload()
                com.sentinel.eye.detect.ModelManager.notifyChanged()
                val spec = find(id)
                if (spec == null) throw IOException("تعذّر تسجيل النموذج")
                ui.value = ImportUi.Done(spec, p.note)
            } catch (t: Throwable) {
                AppLog.w("Custom", "فشل الاستيراد", t)
                ui.value = ImportUi.Failed(t.message ?: t.javaClass.simpleName)
            } finally {
                tmp.delete()
            }
        }
    }

    private class Probe(val meta: YoloMeta, val imgsz: Int, val note: String)

    /** يفتح النموذج ويشغّله على مدخل صفري للتأكد أنه يعمل وأن مخرجاته بصيغة كشف YOLO. */
    private fun probe(file: File): Probe {
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions()
        val sess: OrtSession = try {
            opts.setIntraOpNumThreads(2)
            env.createSession(file.absolutePath, opts)
        } catch (t: Throwable) {
            val m = (t.message ?: t.javaClass.simpleName)
            throw IOException("الملف ليس نموذج ONNX صالحاً أو يستخدم عمليات لا يدعمها ONNX Runtime في التطبيق. " + m.take(140))
        } finally {
            opts.close()
        }
        try {
            val inName = sess.inputNames.iterator().next()
            val ishape = (sess.inputInfo[inName]?.info as? TensorInfo)?.shape
                ?: throw IOException("تعذّر قراءة شكل مدخل النموذج")
            if (ishape.size != 4) throw IOException("ليس نموذج صور: يجب أن يكون المدخل بأربعة أبعاد (1×3×H×W).")
            if (ishape[1] > 0L && ishape[1] != 3L) throw IOException("النموذج يتوقع " + ishape[1] + " قناة لونية بدل 3.")
            val md: Map<String, String> = try {
                sess.metadata.customMetadata
            } catch (t: Throwable) {
                emptyMap()
            }
            val meta = YoloMeta.from(md)
            val dyn = if (meta.imgsz > 0) meta.imgsz else 640
            val h = if (ishape[2] > 0L) ishape[2].toInt() else dyn
            val w = if (ishape[3] > 0L) ishape[3].toInt() else dyn

            val fb = ByteBuffer.allocateDirect(3 * h * w * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            val tensor = OnnxTensor.createTensor(env, fb, longArrayOf(1L, 3L, h.toLong(), w.toLong()))
            var a = 0
            var b = 0
            try {
                sess.run(mapOf(inName to tensor)).use { res ->
                    val out = res.get(0) as? OnnxTensor ?: throw IOException("مخرجات النموذج ليست مصفوفة أرقام")
                    val sh = out.info.shape
                    if (sh.size != 3) {
                        throw IOException("مخرجات النموذج ليست بصيغة كشف YOLO (المتوقع [1, 4+أصناف, N]). إن كان نموذج تصنيف فلا ينتج صناديق.")
                    }
                    a = sh[1].toInt()
                    b = sh[2].toInt()
                }
            } finally {
                tensor.close()
            }

            val e2e = YoloDecodeEx.isEnd2End(a, b, meta)
            val channels = if (a < b) a else b
            var names: List<String> = meta.names
            var note = ""
            if (names.isEmpty()) {
                val guess = if (e2e) 80 else channels - 4
                if (guess <= 0) throw IOException("تعذّر استنتاج عدد الأصناف من شكل المخرجات.")
                names = if (guess == Coco.names.size) Coco.names.toList() else List(guess) { k -> "class $k" }
                note = if (guess == Coco.names.size) {
                    "لم نجد أسماء أصناف داخل الملف فافترضنا أنها أصناف COCO الـ 80."
                } else {
                    "لم نجد أسماء أصناف داخل الملف، لذا سُمِّيت class 0 … class " + (guess - 1) + "."
                }
            } else if (!e2e && channels < names.size + 4) {
                throw IOException("عدد قنوات المخرجات ($channels) لا يتوافق مع عدد الأصناف (" + names.size + ").")
            }
            val task = if (meta.task.isNotEmpty()) meta.task else "detect"
            val shown = YoloMeta(names, task, maxOf(h, w), meta.end2end || e2e)
            return Probe(shown, maxOf(h, w), note)
        } finally {
            try { sess.close() } catch (_: Throwable) { }
        }
    }

    // ───────────── النماذج المحوَّلة في مستودع المستخدم ─────────────

    fun remoteSpec(r: RemoteCustom, repoSetting: String): ModelSpec {
        remoteById[r.id] = r
        val base = ModelCatalog.baseUrl(repoSetting)
        return ModelSpec(
            id = r.id, engine = EngineType.YOLO, title = r.title,
            subtitle = "مخصص · " + taskAr(r.task) + " · " + r.names.size + " صنف · " + r.imgsz,
            fileName = "custom/${r.id}.onnx", url = "$base/${r.file}", inputSize = r.imgsz,
            approxMb = ((r.size + 999_999L) / 1_000_000L).toInt().coerceAtLeast(1), minBytes = 1L
        )
    }

    /** يجلب custom-index.json الذي يكتبه سير عمل التحويل في إصدار models. */
    fun refreshRemote(repoSetting: String) {
        val base = ModelCatalog.baseUrl(repoSetting)
        if (base.isEmpty()) {
            remoteStatus.value = "مصدر النماذج غير مضبوط. أدخل اسم مستودعك (owner/repo) في «مصدر النماذج»."
            return
        }
        remoteStatus.value = "جارٍ تحديث القائمة…"
        pool.execute {
            try {
                val c = ModelManager.open("$base/custom-index.json", 0L)
                val code = c.responseCode
                if (code == 404) {
                    remote.value = emptyList()
                    remoteStatus.value = "لا توجد نماذج محوّلة في مستودعك بعد. شغّل سير عمل «Convert custom YOLO» ثم حدّث."
                    c.disconnect()
                    return@execute
                }
                if (code != 200) {
                    c.disconnect()
                    throw IOException("خطأ HTTP $code")
                }
                val text = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                c.disconnect()
                val arr = JSONObject(text).optJSONArray("models") ?: JSONArray()
                val list = ArrayList<RemoteCustom>()
                for (i in 0 until arr.length()) {
                    val j = arr.optJSONObject(i) ?: continue
                    val names = ArrayList<String>()
                    val na = j.optJSONArray("names")
                    if (na != null) for (k in 0 until na.length()) names.add(na.optString(k))
                    val id = j.optString("id", "")
                    val file = j.optString("file", "")
                    if (id.isEmpty() || file.isEmpty() || !id.startsWith(PREFIX)) continue
                    list.add(
                        RemoteCustom(
                            id, j.optString("title", id), file, j.optLong("size", 0L),
                            j.optString("task", "detect"), j.optInt("imgsz", 640), names, j.optBoolean("end2end", false)
                        )
                    )
                }
                for (r in list) remoteById[r.id] = r
                remote.value = list
                remoteStatus.value = if (list.isEmpty()) "لا توجد نماذج محوّلة بعد." else "تم التحديث: " + list.size + " نموذج."
            } catch (t: Throwable) {
                AppLog.w("Custom", "تعذّر جلب الفهرس", t)
                remoteStatus.value = "تعذّر تحديث القائمة: " + (t.message ?: t.javaClass.simpleName)
            }
        }
    }
}
