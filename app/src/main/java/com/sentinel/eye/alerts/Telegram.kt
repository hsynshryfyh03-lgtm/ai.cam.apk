package com.sentinel.eye.alerts

import android.media.MediaMetadataRetriever
import com.sentinel.eye.core.AppLog
import com.sentinel.eye.core.Config
import com.sentinel.eye.core.ConfigStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * إرسال تنبيهات إلى Telegram عبر Bot API.
 * - الرسائل والصور على خيط مستقل عن رفع الفيديو، فلا يتأخر التنبيه الفوري بسبب مقطع كبير قيد الرفع.
 * - إعادة محاولة ذكية: تُعاد المحاولة لأخطاء الشبكة و5xx و429 فقط، ولا تُعاد لأخطاء التوكن/المحادثة.
 * - كل خطأ يُسجَّل بسببه الحقيقي ويظهر في شاشة Telegram.
 */
object Telegram {
    private val fast: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val bulk: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private const val MAX_VIDEO = 49L * 1024 * 1024
    private val WAITS = longArrayOf(6L, 20L, 60L)

    private val last = MutableStateFlow("")

    /** آخر نتيجة إرسال تلقائي (نجاح أو سبب الفشل) لعرضها في الواجهة. */
    val lastResult: StateFlow<String> get() = last

    private class Job(
        val label: String,
        val cfg: Config,
        val method: String,
        val fields: Map<String, String>,
        val fileField: String?,
        val file: File?,
        val mime: String?
    )

    fun ready(cfg: Config): Boolean =
        cfg.tgEnabled && TgInput.tokenOk(TgInput.token(cfg.tgToken)) && TgInput.chatOk(TgInput.chat(cfg.tgChat))

    private fun stamp(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    // ───────────── الطلب الأساسي ─────────────

    private fun call(
        cfg: Config, token: String, method: String, fields: Map<String, String>,
        fileField: String?, file: File?, mime: String?
    ): TgResult {
        var conn: java.net.HttpURLConnection? = null
        try {
            val c = TgNet.open(cfg, TgNet.url(cfg, token, method))
            conn = c
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 20_000
            c.readTimeout = if (file != null) 180_000 else 40_000
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "SentinelAI/2.1")
            if (file == null) {
                val body = TgNet.form(fields)
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            } else {
                val boundary = "----SentinelBoundary" + java.lang.Long.toHexString(System.nanoTime())
                val mp = MultipartBody(boundary, fields, fileField ?: "file", file, mime ?: "application/octet-stream")
                c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                c.setFixedLengthStreamingMode(mp.length)
                c.outputStream.use { mp.writeTo(it) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return TgNet.parse(code, text)
        } catch (t: Throwable) {
            return TgResult(false, 0, 0, "", 0, null, null, t)
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) { }
        }
    }

    // ───────────── الطابور وإعادة المحاولة ─────────────

    private fun submit(ex: ScheduledExecutorService, job: Job, attempt: Int, delaySec: Long) {
        val r = Runnable { runJob(ex, job, attempt) }
        if (delaySec <= 0L) ex.execute(r) else ex.schedule(r, delaySec, TimeUnit.SECONDS)
    }

    private fun runJob(ex: ScheduledExecutorService, job: Job, attempt: Int) {
        val token = TgInput.token(job.cfg.tgToken)
        val res = try {
            call(job.cfg, token, job.method, job.fields, job.fileField, job.file, job.mime)
        } catch (t: Throwable) {
            TgResult(false, 0, 0, "", 0, null, null, t)
        }
        if (res.ok) {
            AppLog.i("Telegram", "أُرسل: ${job.label}")
            last.value = "✓ " + job.label + " · " + stamp()
            return
        }
        // تحوّلت المجموعة إلى مجموعة خارقة: يتغيّر معرّفها. نحدّثه ونعيد فوراً.
        val mig = res.migrateTo
        if (mig != null && attempt < 3) {
            ConfigStore.update { it.copy(tgChat = mig) }
            val f = job.fields.toMutableMap()
            f["chat_id"] = mig
            submit(ex, Job(job.label, ConfigStore.current, job.method, f, job.fileField, job.file, job.mime), attempt + 1, 0L)
            return
        }
        val why = TgExplain.explain(res)
        AppLog.w(
            "Telegram",
            "فشل (${job.label}) المحاولة ${attempt + 1}: $why | HTTP ${res.http} | ${res.description} | token=${TgInput.mask(token)}",
            res.net
        )
        last.value = "✗ " + job.label + " · " + stamp() + "\n" + why
        // فشل الفيديو بسبب الصيغة: نحاول إرساله كملف عادي حتى يصل المقطع على أي حال.
        val f0 = job.file
        if (job.method == "sendVideo" && res.http == 400 && f0 != null && !res.description.lowercase().contains("chat")) {
            val doc = job.fields.filterKeys { it == "chat_id" || it == "caption" }
            submit(ex, Job(job.label + " (كملف)", job.cfg, "sendDocument", doc, "document", f0, "video/mp4"), 2, 0L)
            return
        }
        if (res.permanent) return
        if (attempt < WAITS.size) {
            val wait = if (res.retryAfter > 0) res.retryAfter.toLong() + 1L else WAITS[attempt]
            submit(ex, job, attempt + 1, wait)
        }
    }

    private fun capText(s: String): String = if (s.length > 1000) s.substring(0, 1000) else s

    // ───────────── الإرسال ─────────────

    fun sendText(cfg: Config, text: String) {
        if (!ready(cfg)) return
        val fields = mapOf("chat_id" to TgInput.chat(cfg.tgChat), "text" to text)
        submit(fast, Job("نص", cfg, "sendMessage", fields, null, null, null), 0, 0L)
    }

    fun sendPhoto(cfg: Config, file: File, caption: String) {
        if (!ready(cfg) || !file.exists()) return
        val fields = mapOf("chat_id" to TgInput.chat(cfg.tgChat), "caption" to capText(caption))
        submit(fast, Job("صورة", cfg, "sendPhoto", fields, "photo", file, "image/jpeg"), 0, 0L)
    }

    fun sendVideo(cfg: Config, file: File, caption: String) {
        if (!ready(cfg) || !file.exists()) return
        if (file.length() > MAX_VIDEO) {
            sendText(cfg, caption + "\n(المقطع أكبر من حد Telegram للبوتات 50MB ولم يُرفق)")
            return
        }
        val fields = LinkedHashMap<String, String>()
        fields["chat_id"] = TgInput.chat(cfg.tgChat)
        fields["caption"] = capText(caption)
        fields["supports_streaming"] = "true"
        val p = probe(file)
        if (p != null) {
            fields["width"] = p[0].toString()
            fields["height"] = p[1].toString()
            fields["duration"] = p[2].toString()
        }
        submit(bulk, Job("فيديو", cfg, "sendVideo", fields, "video", file, "video/mp4"), 0, 0L)
    }

    /** أبعاد الفيديو بعد التدوير ومدته بالثواني، حتى يعرضه Telegram بنسبة صحيحة قبل التنزيل. */
    private fun probe(file: File): IntArray? {
        val m = MediaMetadataRetriever()
        return try {
            m.setDataSource(file.absolutePath)
            var w = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            var h = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            val rot = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val dur = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (rot == 90 || rot == 270) {
                val t = w
                w = h
                h = t
            }
            intArrayOf(w, h, ((dur + 500L) / 1000L).toInt())
        } catch (_: Throwable) {
            null
        } finally {
            try { m.release() } catch (_: Throwable) { }
        }
    }

    // ───────────── الاختبار والاكتشاف ─────────────

    /** اختبار على مراحل: صيغة المدخلات ← الشبكة ← التوكن ← الإرسال، ويذكر المرحلة التي فشلت وسببها. */
    fun test(cfg: Config, onResult: (String) -> Unit) {
        fast.execute {
            val msg = try {
                runTest(cfg)
            } catch (t: Throwable) {
                AppLog.e("Telegram", "خطأ غير متوقع في الاختبار", t)
                "خطأ غير متوقع أثناء الاختبار: " + (t.message ?: t.javaClass.simpleName)
            }
            onResult(msg)
        }
    }

    private fun runTest(cfg: Config): String {
        val token = TgInput.token(cfg.tgToken)
        val chat = TgInput.chat(cfg.tgChat)
        val p1 = TgInput.tokenProblem(token)
        if (p1 != null) return p1
        val p2 = TgInput.chatProblem(chat)
        if (p2 != null) return p2
        val p3 = TgNet.configProblem(cfg)
        if (p3 != null) return p3

        val me = call(cfg, token, "getMe", emptyMap(), null, null, null)
        if (!me.ok) {
            AppLog.w("Telegram", "اختبار getMe فشل: HTTP ${me.http} ${me.description} token=${TgInput.mask(token)}", me.net)
            return "فشلت المرحلة 1 (التحقق من التوكن).\n" + TgExplain.explain(me)
        }
        val botUser = me.json?.optJSONObject("result")?.optString("username", "") ?: ""
        val bot = if (botUser.isNotEmpty()) "@$botUser" else "البوت"

        val text = "✅ اختبار من الحارس الذكي: الاتصال يعمل (" + stamp() + ")"
        var r = call(cfg, token, "sendMessage", mapOf("chat_id" to chat, "text" to text), null, null, null)
        var usedChat = chat
        val mig = r.migrateTo
        if (!r.ok && mig != null) {
            ConfigStore.update { it.copy(tgChat = mig) }
            usedChat = mig
            r = call(cfg, token, "sendMessage", mapOf("chat_id" to mig, "text" to text), null, null, null)
        }
        if (!r.ok) {
            AppLog.w("Telegram", "اختبار sendMessage فشل: HTTP ${r.http} ${r.description}", r.net)
            return "التوكن سليم ($bot) لكن فشل الإرسال إلى المحادثة (المرحلة 2).\n" + TgExplain.explain(r)
        }
        val sb = StringBuilder("✓ تم الإرسال بنجاح عبر $bot إلى ${TgInput.describeChat(usedChat)}. تحقق من Telegram.")
        if (usedChat != chat) sb.append("\nتحوّلت المجموعة إلى مجموعة خارقة وحُدِّث Chat ID تلقائياً.")
        if (!cfg.tgEnabled) sb.append("\nملاحظة: مفتاح «تفعيل الإرسال» مغلق، فلن تُرسَل تنبيهات تلقائية قبل تشغيله.")
        return sb.toString()
    }

    /** يستخرج Chat ID من آخر رسالة أُرسلت للبوت (يفضّل المحادثة الخاصة). */
    fun discoverChat(cfg: Config, onResult: (String?, String) -> Unit) {
        fast.execute {
            try {
                val token = TgInput.token(cfg.tgToken)
                val p1 = TgInput.tokenProblem(token)
                if (p1 != null) {
                    onResult(null, p1)
                    return@execute
                }
                val p3 = TgNet.configProblem(cfg)
                if (p3 != null) {
                    onResult(null, p3)
                    return@execute
                }
                val r = call(cfg, token, "getUpdates", mapOf("limit" to "100", "timeout" to "0"), null, null, null)
                if (!r.ok) {
                    AppLog.w("Telegram", "getUpdates فشل: HTTP ${r.http} ${r.description}", r.net)
                    onResult(null, TgExplain.explain(r))
                    return@execute
                }
                val arr = r.json?.optJSONArray("result")
                var bestId: Long? = null
                var bestName = ""
                var bestType = ""
                if (arr != null) {
                    for (i in arr.length() - 1 downTo 0) {
                        val u = arr.optJSONObject(i) ?: continue
                        val m = u.optJSONObject("message")
                            ?: u.optJSONObject("edited_message")
                            ?: u.optJSONObject("channel_post")
                            ?: u.optJSONObject("my_chat_member")
                            ?: u.optJSONObject("callback_query")?.optJSONObject("message")
                        val chat = m?.optJSONObject("chat") ?: continue
                        if (!chat.has("id")) continue
                        val type = chat.optString("type", "")
                        val first = bestId == null
                        if (first || (type == "private" && bestType != "private")) {
                            bestId = chat.getLong("id")
                            bestType = type
                            val title = chat.optString("title", "")
                            val fn = (chat.optString("first_name", "") + " " + chat.optString("last_name", "")).trim()
                            bestName = if (title.isNotEmpty()) title else if (fn.isNotEmpty()) fn else chat.optString("username", "")
                        }
                        if (bestType == "private") break
                    }
                }
                val id = bestId
                if (id == null) {
                    onResult(null, "لا توجد رسائل بعد. افتح البوت في Telegram واضغط Start وأرسل أي رسالة، ثم أعد المحاولة (تُحفَظ الرسائل 24 ساعة فقط).")
                } else {
                    val kind = when (bestType) {
                        "private" -> "محادثة خاصة"
                        "group", "supergroup" -> "مجموعة"
                        "channel" -> "قناة"
                        else -> "محادثة"
                    }
                    onResult(id.toString(), "✓ وُجدت $kind: $bestName  (ID: $id)")
                }
            } catch (t: Throwable) {
                AppLog.e("Telegram", "اكتشاف المحادثة فشل", t)
                onResult(null, "تعذّر اكتشاف المحادثة: " + (t.message ?: t.javaClass.simpleName))
            }
        }
    }
}
