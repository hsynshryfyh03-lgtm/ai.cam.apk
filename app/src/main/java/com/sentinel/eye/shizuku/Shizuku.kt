package com.sentinel.eye.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import com.sentinel.eye.BuildConfig
import com.sentinel.eye.IShellService
import com.sentinel.eye.core.AppLog
import com.sentinel.eye.core.ConfigStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** يعمل داخل عملية Shizuku بصلاحيات shell (uid 2000). */
class ShellService : IShellService.Stub() {
    override fun destroy() {
        exitProcess(0)
    }

    override fun exec(command: String?): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", (command ?: "") + " 2>&1"))
            val out = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            "exit=$code\n$out"
        } catch (t: Throwable) {
            "exit=-1\nexception: ${t.message}"
        }
    }
}

enum class ShzStatus { NOT_RUNNING, NO_PERMISSION, READY }

class Tweak(
    val id: String,
    val title: String,
    val desc: String,
    val applyCmds: List<String>,
    val restoreCmds: List<String>,
    val essential: Boolean
)

object ShizukuHelper {
    private const val REQ_CODE = 7001
    private val st = MutableStateFlow(ShzStatus.NOT_RUNNING)
    val status: StateFlow<ShzStatus> get() = st
    private val logState = MutableStateFlow("")
    val log: StateFlow<String> get() = logState

    private var inited = false
    private var service: IShellService? = null
    private var latch: CountDownLatch? = null
    private val worker = Executors.newSingleThreadExecutor()

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = if (binder != null && binder.pingBinder()) IShellService.Stub.asInterface(binder) else null
            latch?.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private fun userArgs(): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, ShellService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(false)
            .tag("sentinel-shell")
            .version(BuildConfig.VERSION_CODE)

    @Synchronized
    fun init() {
        if (inited) return
        inited = true
        try {
            Shizuku.addBinderReceivedListenerSticky { refresh() }
            Shizuku.addBinderDeadListener {
                service = null
                refresh()
            }
            Shizuku.addRequestPermissionResultListener { _, _ -> refresh() }
        } catch (t: Throwable) {
            AppLog.w("Shizuku", "تعذّر تسجيل المستمعين", t)
        }
        refresh()
    }

    fun refresh() {
        st.value = try {
            when {
                !Shizuku.pingBinder() -> ShzStatus.NOT_RUNNING
                Shizuku.isPreV11() -> ShzStatus.NOT_RUNNING
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShzStatus.NO_PERMISSION
                else -> ShzStatus.READY
            }
        } catch (_: Throwable) {
            ShzStatus.NOT_RUNNING
        }
    }

    fun requestPermission() {
        try {
            if (Shizuku.pingBinder() && !Shizuku.isPreV11()) Shizuku.requestPermission(REQ_CODE)
        } catch (t: Throwable) {
            AppLog.w("Shizuku", "تعذّر طلب الإذن", t)
        }
    }

    fun managerInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        true
    } catch (_: Throwable) {
        false
    }

    /** يفتح تطبيق Shizuku إن كان مثبّتاً، وإلا صفحة التنزيل. */
    fun openManager(ctx: Context) {
        try {
            val i = ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    private fun ensureService(): IShellService? {
        val s = service
        if (s != null && s.asBinder().pingBinder()) return s
        service = null
        val l = CountDownLatch(1)
        latch = l
        try {
            Shizuku.bindUserService(userArgs(), conn)
        } catch (t: Throwable) {
            AppLog.w("Shizuku", "تعذّر ربط الخدمة", t)
            return null
        }
        l.await(8, TimeUnit.SECONDS)
        return service
    }

    /** ينفّذ أمراً بصلاحيات shell. يُستدعى من خيط خلفي فقط. */
    fun exec(cmd: String): String {
        if (st.value != ShzStatus.READY) return "exit=-1\nShizuku غير جاهز"
        val s = ensureService() ?: return "exit=-1\nتعذّر الاتصال بخدمة shell"
        return try {
            s.exec(cmd)
        } catch (t: Throwable) {
            "exit=-1\n${t.message}"
        }
    }

    fun tweaks(pkg: String): List<Tweak> = listOf(
        Tweak(
            "doze_whitelist", "استثناء من تحسين البطارية", "يمنع Doze من تعليق التطبيق",
            listOf("dumpsys deviceidle whitelist +$pkg"), listOf("dumpsys deviceidle whitelist -$pkg"), true
        ),
        Tweak(
            "bg_ops", "السماح بالعمل في الخلفية", "يرفع قيود الخلفية عبر AppOps",
            listOf("cmd appops set $pkg RUN_IN_BACKGROUND allow", "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow"),
            listOf("cmd appops set $pkg RUN_IN_BACKGROUND default", "cmd appops set $pkg RUN_ANY_IN_BACKGROUND default"), true
        ),
        Tweak(
            "bucket", "فئة الخمول: نشط", "يضع التطبيق في أعلى فئة أولوية للمهام",
            listOf("am set-standby-bucket $pkg active"), emptyList(), true
        ),
        Tweak(
            "perms", "منح الأذونات تلقائياً", "الكاميرا والإشعارات والميكروفون وإعدادات الأمان",
            listOf(
                "pm grant $pkg android.permission.CAMERA", "pm grant $pkg android.permission.RECORD_AUDIO",
                "pm grant $pkg android.permission.POST_NOTIFICATIONS", "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS"
            ), emptyList(), true
        ),
        Tweak(
            "battery_saver", "إيقاف موفّر الطاقة", "يعطّل وضع توفير الطاقة الذي يقيّد المعالج",
            listOf("settings put global low_power 0"), emptyList(), false
        ),
        Tweak(
            "adaptive", "إيقاف البطارية التكيفية", "يمنع النظام من تقييد التطبيقات تدريجياً",
            listOf("settings put global adaptive_battery_management_enabled 0"),
            listOf("settings put global adaptive_battery_management_enabled 1"), false
        ),
        Tweak(
            "stay_awake", "إبقاء الجهاز مستيقظاً أثناء الشحن", "لا تنطفئ الشاشة والجهاز عند توصيل الشاحن",
            listOf("settings put global stay_on_while_plugged_in 3"), listOf("settings put global stay_on_while_plugged_in 0"), false
        ),
        Tweak(
            "doze_off", "تعطيل Doze على مستوى النظام", "متقدّم: يرفع استهلاك البطارية. يعود تلقائياً بعد إعادة التشغيل",
            listOf("dumpsys deviceidle disable"), listOf("dumpsys deviceidle enable"), false
        ),
        Tweak(
            "fixed_perf", "وضع الأداء الثابت", "إن دعمه عتاد الجهاز (قد يُرفض)",
            listOf("cmd power set-fixed-performance-mode-enabled true"), listOf("cmd power set-fixed-performance-mode-enabled false"), false
        )
    )

    private fun appendLog(s: String) {
        logState.value = (logState.value + s + "\n").takeLast(4000)
    }

    fun clearLog() { logState.value = "" }

    /** يطبّق أو يستعيد مجموعة تحسينات في خيط خلفي، ويحدّث الإعداد بما طُبِّق. */
    fun run(ctx: Context, ids: Set<String>?, restore: Boolean, done: () -> Unit) {
        val pkg = ctx.packageName
        worker.execute {
            try {
                val all = tweaks(pkg)
                val selected = if (ids == null) all.filter { it.essential } else all.filter { it.id in ids }
                val applied = ConfigStore.current.shzApplied.split(",").filter { it.isNotBlank() }.toMutableSet()
                for (t in selected) {
                    val cmds = if (restore) t.restoreCmds else t.applyCmds
                    var ok = true
                    for (c in cmds) {
                        val r = exec(c)
                        val code = r.substringBefore('\n').removePrefix("exit=").trim().toIntOrNull() ?: -1
                        appendLog((if (code == 0) "✓ " else "✗ ") + c + (if (code != 0) "\n   " + r.substringAfter('\n').trim().take(160) else ""))
                        if (code != 0) ok = false
                    }
                    if (cmds.isEmpty()) ok = true
                    if (ok && !restore) applied.add(t.id)
                    if (restore) applied.remove(t.id)
                }
                ConfigStore.update { it.copy(shzApplied = applied.joinToString(",")) }
            } catch (t: Throwable) {
                AppLog.w("Shizuku", "فشل تطبيق التحسينات", t)
                appendLog("خطأ: " + t.message)
            } finally {
                done()
            }
        }
    }
}
