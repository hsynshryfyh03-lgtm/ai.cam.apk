package com.sentinel.eye.alerts

import com.sentinel.eye.core.AppLog
import com.sentinel.eye.core.Config
import org.json.JSONObject
import java.net.Authenticator
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** نتيجة طلب واحد إلى Bot API، أو خطأ شبكة إن لم يصل الطلب أصلاً. */
class TgResult(
    val ok: Boolean,
    val http: Int,
    val code: Int,
    val description: String,
    val retryAfter: Int,
    val migrateTo: String?,
    val json: JSONObject?,
    val net: Throwable?
) {
    /** خطأ دائم لا تفيد معه إعادة المحاولة (توكن/محادثة/صلاحيات خاطئة). */
    val permanent: Boolean
        get() = net == null && !ok && http in 400..499 && http != 429 && http != 408
}

/**
 * تنظيف وتحقق مدخلات المستخدم. مصدر شائع للفشل مع أن القيم «تبدو صحيحة»:
 * أرقام عربية-هندية من لوحة المفاتيح العربية، علامات اتجاه خفية (RLM/LRM) تأتي مع النسخ،
 * بادئة bot أو رابط API كامل، شرطة سالبة بصيغة يونيكود، أو مسافات داخل التوكن.
 */
object TgInput {
    private val INVISIBLE = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\u2066-\\u2069\\uFEFF\\u00AD\\u061C]")
    private val TOKEN_FIND = Regex("(\\d{5,}):([A-Za-z0-9_-]{20,})")
    private val TOKEN_FULL = Regex("^\\d{5,}:[A-Za-z0-9_-]{20,}\$")
    private val CHAT_NUM = Regex("^-?\\d{4,}\$")
    private val CHAT_USER = Regex("^@[A-Za-z][A-Za-z0-9_]{4,31}\$")
    private val CHAT_BARE = Regex("^[A-Za-z][A-Za-z0-9_]{4,31}\$")
    private val CHAT_LINK = Regex("^(?:https?://)?(?:t|telegram)\\.me/([A-Za-z][A-Za-z0-9_]{4,31})/?\$", RegexOption.IGNORE_CASE)
    private val DASHES = charArrayOf('\u2212', '\u2013', '\u2014', '\u2010', '\u2011', '\u2012', '\uFE63', '\uFF0D')

    private fun asciiDigits(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            if (ch.isDigit() && ch !in '0'..'9') {
                val d = Character.digit(ch, 10)
                if (d >= 0) sb.append('0' + d) else sb.append(ch)
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun base(raw: String): String {
        val noInvisible = INVISIBLE.replace(raw, "")
        return asciiDigits(noInvisible).filter { !it.isWhitespace() }
    }

    /** يستخرج التوكن من أي نص لصقه المستخدم (توكن مجرّد، bot123:ABC، أو رابط API كامل). */
    fun token(raw: String): String {
        val s = base(raw)
        val m = TOKEN_FIND.find(s)
        return if (m != null) m.value else s
    }

    fun chat(raw: String): String {
        var s = base(raw)
        for (d in DASHES) s = s.replace(d, '-')
        val link = CHAT_LINK.find(s)
        if (link != null) return "@" + link.groupValues[1]
        if (s.startsWith("@")) return s
        if (CHAT_NUM.matches(s)) return s
        if (CHAT_BARE.matches(s)) return "@$s"
        return s
    }

    fun tokenOk(t: String): Boolean = TOKEN_FULL.matches(t)
    fun chatOk(c: String): Boolean = CHAT_NUM.matches(c) || CHAT_USER.matches(c)

    fun tokenProblem(t: String): String? = when {
        t.isEmpty() -> "أدخل توكن البوت أولاً."
        !tokenOk(t) -> "صيغة التوكن غير صحيحة. التوكن الصحيح يشبه 123456789:AAH… (أرقام ثم نقطتان رأسيتان ثم حروف وأرقام بلا مسافات). انسخه من @BotFather كما هو."
        else -> null
    }

    fun chatProblem(c: String): String? = when {
        c.isEmpty() -> "أدخل Chat ID أولاً."
        !chatOk(c) -> "صيغة Chat ID غير صحيحة. يجب أن يكون رقماً مثل 123456789 (أو -100… للقنوات والمجموعات)، أو @اسم_القناة."
        else -> null
    }

    fun describeChat(c: String): String = when {
        c.startsWith("@") -> "قناة أو مجموعة عامة"
        c.startsWith("-100") -> "قناة أو مجموعة خارقة"
        c.startsWith("-") -> "مجموعة"
        else -> "محادثة خاصة"
    }

    /** إخفاء التوكن في السجل: يُترك رقم البوت وآخر 4 محارف فقط. */
    fun mask(t: String): String {
        val i = t.indexOf(':')
        return if (i <= 0 || t.length < i + 6) "***" else t.substring(0, i) + ":***" + t.takeLast(4)
    }
}

/** إعدادات الاتصال: عنوان API (مباشر أو بديل) ووكيل HTTP/SOCKS اختياري. */
object TgNet {
    const val DEFAULT_BASE = "https://api.telegram.org"

    @Volatile private var authUser: String = ""
    @Volatile private var authPass: CharArray = CharArray(0)
    @Volatile private var authInstalled = false

    private val authenticator = object : Authenticator() {
        override fun getPasswordAuthentication(): PasswordAuthentication? {
            if (authUser.isEmpty()) return null
            val proto = getRequestingProtocol() ?: ""
            val isProxy = getRequestorType() == Authenticator.RequestorType.PROXY || proto.startsWith("SOCKS", ignoreCase = true)
            return if (isProxy) PasswordAuthentication(authUser, authPass) else null
        }
    }

    fun base(cfg: Config): String {
        val b = cfg.tgApiBase.trim().trimEnd('/')
        return if (b.isEmpty()) DEFAULT_BASE else b
    }

    fun url(cfg: Config, token: String, method: String): String = base(cfg) + "/bot" + token + "/" + method

    /** رسالة مشكلة في إعداد الشبكة، أو null إن كان سليماً. */
    fun configProblem(cfg: Config): String? {
        val b = base(cfg)
        if (!b.startsWith("https://")) {
            return "عنوان API البديل يجب أن يبدأ بـ https:// (التطبيق يمنع الاتصال غير المشفّر)."
        }
        try {
            URL(b)
        } catch (t: Throwable) {
            return "عنوان API البديل غير صالح."
        }
        if (cfg.tgProxy.isNotBlank() && proxyOf(cfg.tgProxy) == null) {
            return "صيغة الوكيل غير صحيحة. أمثلة: socks5://127.0.0.1:1080  أو  http://host:8080  أو  socks5://user:pass@host:1080"
        }
        return null
    }

    fun open(cfg: Config, url: String): HttpURLConnection {
        val p = proxyOf(cfg.tgProxy)
        val u = URL(url)
        val c = if (p != null) u.openConnection(p) else u.openConnection()
        return c as HttpURLConnection
    }

    fun proxyOf(raw: String): Proxy? {
        val s = raw.trim()
        if (s.isEmpty()) {
            authUser = ""
            return null
        }
        return try {
            val withScheme = if (s.contains("://")) s else "http://$s"
            val u = URI(withScheme)
            val host = u.host
            if (host == null || host.isEmpty()) return null
            val scheme = (u.scheme ?: "http").lowercase()
            val socks = scheme.startsWith("socks")
            val port = if (u.port > 0) u.port else if (socks) 1080 else 8080
            val ui = u.rawUserInfo
            if (ui != null && ui.contains(":")) {
                authUser = URLDecoder.decode(ui.substringBefore(':'), "UTF-8")
                authPass = URLDecoder.decode(ui.substringAfter(':'), "UTF-8").toCharArray()
                if (!authInstalled) {
                    Authenticator.setDefault(authenticator)
                    authInstalled = true
                }
            } else {
                authUser = ""
            }
            val addr = InetSocketAddress(host, port)
            if (addr.isUnresolved) return null
            Proxy(if (socks) Proxy.Type.SOCKS else Proxy.Type.HTTP, addr)
        } catch (t: Throwable) {
            AppLog.w("Telegram", "صيغة الوكيل غير صالحة أو تعذّر حل عنوانه", t)
            null
        }
    }

    fun form(fields: Map<String, String>): ByteArray {
        val sb = StringBuilder()
        for ((k, v) in fields) {
            if (sb.isNotEmpty()) sb.append('&')
            sb.append(URLEncoder.encode(k, "UTF-8")).append('=').append(URLEncoder.encode(v, "UTF-8"))
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    fun parse(http: Int, text: String): TgResult {
        var j: JSONObject? = null
        try {
            if (text.isNotBlank()) j = JSONObject(text)
        } catch (_: Throwable) {
        }
        val ok = j?.optBoolean("ok", false) ?: false
        val params = j?.optJSONObject("parameters")
        var mig: String? = null
        if (params != null && params.has("migrate_to_chat_id")) mig = params.optLong("migrate_to_chat_id").toString()
        val desc = if (j != null) j.optString("description", "") else text.take(160)
        return TgResult(
            ok = ok && http in 200..299,
            http = http,
            code = j?.optInt("error_code", 0) ?: 0,
            description = desc,
            retryAfter = params?.optInt("retry_after", 0) ?: 0,
            migrateTo = mig,
            json = j,
            net = null
        )
    }
}

/** تحويل نتيجة Telegram إلى رسالة عربية تدلّ على السبب الحقيقي وعلى الحل. */
object TgExplain {
    fun explain(r: TgResult): String {
        val n = r.net
        if (n != null) return network(n)
        if (r.json == null) {
            return "وصل ردّ غير متوقع (HTTP ${r.http}) ولم يكن من Telegram؛ غالباً الشبكة تعترض الاتصال (بوابة تسجيل دخول Wi‑Fi أو حجب من المزوّد). جرّب VPN أو وكيلاً من «الشبكة المتقدمة». " + r.description.take(80)
        }
        val code = if (r.code != 0) r.code else r.http
        val d = r.description
        val dl = d.lowercase()
        return when {
            code == 401 -> "رفض Telegram التوكن (401 Unauthorized): التوكن مُلغى أو ناقص أو من بوت آخر. أنشئ توكناً جديداً من @BotFather بالأمر /token وانسخه كاملاً."
            code == 404 -> "لم يتعرّف Telegram على التوكن (404): الصيغة خاطئة غالباً. تأكد أنك لم تنسخ جزءاً منه فقط."
            code == 409 -> "يوجد تعارض (409): البوت مرتبط بـ Webhook أو يعمل على جهاز آخر عبر getUpdates. احذف الـ Webhook أو أدخل Chat ID يدوياً."
            code == 429 -> "تجاوزت حدّ الإرسال (429). حاول بعد ${if (r.retryAfter > 0) r.retryAfter else 30} ثانية."
            code == 413 || dl.contains("too large") -> "الملف أكبر من حدّ Telegram للبوتات (50MB)."
            dl.contains("chat not found") -> "المحادثة غير موجودة (chat not found): إمّا أن Chat ID خاطئ، أو أنك لم تضغط Start على البوت في Telegram، أو أن البوت ليس عضواً في القناة/المجموعة. أرسل أي رسالة إلى البوت ثم استخدم «اكتشاف Chat ID»."
            dl.contains("blocked by the user") -> "حظرتَ البوت من حسابك. افتح محادثته في Telegram وأعد تفعيله (Unblock) ثم اضغط Start."
            dl.contains("can't initiate") || dl.contains("cannot initiate") -> "لا يستطيع البوت بدء المحادثة معك. افتح البوت في Telegram واضغط Start أولاً."
            dl.contains("not a member") || dl.contains("not enough rights") || dl.contains("have no rights") || dl.contains("administrator") ->
                "البوت لا يملك صلاحية الكتابة هنا. أضِفه إلى القناة/المجموعة كمشرف مع إذن نشر الرسائل."
            dl.contains("deactivated") -> "الحساب المستهدف مُعطّل."
            dl.contains("wrong file") || dl.contains("wrong type") || dl.contains("failed to get http url content") -> "رفض Telegram الملف (صيغة غير مدعومة): $d"
            code in 500..599 -> "خطأ مؤقت من خوادم Telegram ($code). ستُعاد المحاولة تلقائياً."
            else -> "رفض Telegram الطلب ($code): $d"
        }
    }

    private fun network(t: Throwable): String {
        var e: Throwable? = t
        var depth = 0
        while (e != null && depth < 5) {
            when (e) {
                is UnknownHostException ->
                    return "تعذّر الوصول إلى خادم Telegram (فشل DNS). غالباً الموقع محجوب لدى مزوّد الإنترنت أو لا يوجد اتصال. جرّب VPN، أو حدّد وكيلاً أو عنواناً بديلاً من «الشبكة المتقدمة»."
                is SocketTimeoutException ->
                    return "انتهت مهلة الاتصال بـ Telegram. الشبكة بطيئة أو الخادم محجوب. جرّب VPN أو وكيلاً، ثم أعد المحاولة."
                is ConnectException, is NoRouteToHostException ->
                    return "رُفض الاتصال بخادم Telegram. الشبكة تحجب الوصول أو الوكيل المحدَّد لا يعمل. جرّب VPN أو غيّر الوكيل."
                is SSLException ->
                    return "فشل الاتصال الآمن (TLS). تحقق من ضبط التاريخ والوقت في الهاتف، وأن شبكتك لا تعترض الاتصال المشفّر."
            }
            e = e.cause
            depth++
        }
        val m = t.message
        return "خطأ شبكة: " + t.javaClass.simpleName + (if (m != null) " — $m" else "")
    }
}
