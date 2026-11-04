package com.sentinel.eye.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sentinel.eye.alerts.Telegram
import com.sentinel.eye.alerts.TgInput
import com.sentinel.eye.core.ConfigStore
import com.sentinel.eye.shizuku.ShizukuHelper
import com.sentinel.eye.shizuku.ShzStatus
import kotlinx.coroutines.delay

@Composable
private fun BackButton(onBack: () -> Unit) {
    val c = Ios.c
    Box(
        Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onBack)
            .padding(8.dp)
    ) { Glyph(Ic.ChevR, c.blue, 22.dp) }
}

// ───────────── Shizuku ─────────────

@Composable
fun ShizukuScreen(onBack: () -> Unit) {
    val c = Ios.c
    val ctx = LocalContext.current
    val st by ShizukuHelper.status.collectAsState()
    val log by ShizukuHelper.log.collectAsState()
    val cfg by ConfigStore.flow.collectAsState()
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        ShizukuHelper.init()
        while (true) {
            ShizukuHelper.refresh()
            delay(1500)
        }
    }
    val applied = cfg.shzApplied.split(",").filter { it.isNotBlank() }.toSet()
    val tweaks = remember { ShizukuHelper.tweaks(ctx.packageName) }
    val ready = st == ShzStatus.READY

    LargeTitleScreen("Shizuku", leading = { BackButton(onBack) }) {
        item {
            when (st) {
                ShzStatus.NOT_RUNNING -> Callout(
                    Ic.Warning, c.orange, "Shizuku لا يعمل",
                    "ثبّت تطبيق Shizuku وشغّله من «خيارات المطوّر ← التصحيح اللاسلكي»، ثم ارجع إلى هنا.",
                    appear = 1
                ) { SmallButton(if (ShizukuHelper.managerInstalled(ctx)) "فتح Shizuku" else "تنزيل Shizuku", { ShizukuHelper.openManager(ctx) }) }
                ShzStatus.NO_PERMISSION -> Callout(
                    Ic.Shield, c.blue, "مطلوب إذن Shizuku",
                    "اسمح للتطبيق باستخدام Shizuku ليتمكن من تطبيق التحسينات.", appear = 1
                ) { SmallButton("منح الإذن", { ShizukuHelper.requestPermission() }) }
                ShzStatus.READY -> Callout(Ic.Check, c.green, "Shizuku جاهز", "يعمل والإذن ممنوح. يمكنك تطبيق التحسينات الآن.", appear = 1)
            }
        }
        item {
            Callout(
                Ic.Info, c.gray, "ما الذي يفعله Shizuku وما لا يفعله",
                "يحسّن بقاء التطبيق في الخلفية وأولويته: استثناء البطارية، رفع قيود الخلفية، إيقاف موفّر الطاقة وغيرها. " +
                    "لا يرفع ترددات المعالج ولا يتجاوز تخفيض الحرارة؛ هذا يحتاج root. " +
                    "بعد إعادة تشغيل الهاتف يلزم تشغيل Shizuku من جديد ما لم يكن الجهاز مجذّراً.",
                appear = 2
            )
        }
        item {
            IosSection(
                "التحسينات",
                footer = "كل مفتاح ينفّذ أوامر shell حقيقية، وتظهر نتيجتها في السجل أدناه.",
                appear = 3
            ) {
                for (t in tweaks) {
                    row {
                        IosRow(
                            title = t.title, subtitle = t.desc,
                            trailing = {
                                IosSwitch(
                                    checked = t.id in applied, enabled = ready && !busy,
                                    onChange = { on ->
                                        busy = true
                                        ShizukuHelper.run(ctx, setOf(t.id), !on) { busy = false }
                                    }
                                )
                            }
                        )
                    }
                }
            }
        }
        item {
            IosSection("إجراءات", appear = 4) {
                row {
                    Box(Modifier.fillMaxWidth().padding(12.dp)) {
                        IosButton("تطبيق الأساسيات الآن", {
                            busy = true
                            ShizukuHelper.run(ctx, null, false) { busy = false }
                        }, enabled = ready && !busy, icon = Ic.Bolt)
                    }
                }
                row {
                    SwitchRow(
                        "تطبيق تلقائي عند بدء المراقبة", cfg.shzAuto,
                        { v -> ConfigStore.update { it.copy(shzAuto = v) } },
                        "يطبّق الأساسيات كلما بدأت المراقبة وShizuku جاهز"
                    )
                }
                row {
                    IosRow(
                        title = "استعادة كل ما طُبِّق", destructive = true,
                        onClick = {
                            if (ready && !busy && applied.isNotEmpty()) {
                                busy = true
                                ShizukuHelper.run(ctx, applied, true) { busy = false }
                            }
                        }
                    )
                }
            }
        }
        item {
            IosSection("السجل", appear = 5) {
                row {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Txt(
                            log.ifEmpty { "لا توجد عمليات بعد" },
                            TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 14.sp), c.secondary
                        )
                    }
                }
                row { IosRow(title = "مسح السجل", onClick = { ShizukuHelper.clearLog() }) }
            }
        }
    }
}

// ───────────── Telegram ─────────────

@Composable
fun TelegramScreen(onBack: () -> Unit) {
    val c = Ios.c
    val cfg by ConfigStore.flow.collectAsState()
    val lastSend by Telegram.lastResult.collectAsState()
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showToken by remember { mutableStateOf(false) }
    var fieldVer by remember { mutableStateOf(0) }

    // يوحّد القيم المخزّنة (أرقام عربية، رموز خفية، بادئة bot…) قبل أي اتصال ويعيد تعبئة الحقول.
    fun normalize(): com.sentinel.eye.core.Config {
        ConfigStore.update {
            it.copy(
                tgToken = TgInput.token(it.tgToken), tgChat = TgInput.chat(it.tgChat),
                tgApiBase = it.tgApiBase.trim(), tgProxy = it.tgProxy.trim()
            )
        }
        fieldVer += 1
        return ConfigStore.current
    }

    LargeTitleScreen("Telegram", leading = { BackButton(onBack) }) {
        item {
            Callout(
                Ic.Bell, c.blue, "تنبيهات على هاتفك الآخر",
                "1) افتح @BotFather في Telegram وأنشئ بوتاً بالأمر /newbot وانسخ التوكن.\n" +
                    "2) افتح البوت واضغط Start وأرسل له أي رسالة.\n" +
                    "3) اضغط «اكتشاف Chat ID» ثم «رسالة تجريبية». عند الفشل ستظهر المرحلة والسبب الحقيقي.",
                appear = 1
            )
        }
        item {
            IosSection("الحساب", appear = 2) {
                row { SwitchRow("تفعيل الإرسال", cfg.tgEnabled, { v -> ConfigStore.update { it.copy(tgEnabled = v) } }) }
                row {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Txt("توكن البوت", Type.subhead, c.secondary, Modifier.weight(1f))
                            SmallButton(if (showToken) "إخفاء" else "إظهار", { showToken = !showToken })
                        }
                        VSpace(6.dp)
                        IosInputField(
                            cfg.tgToken, { v -> ConfigStore.update { it.copy(tgToken = v) } },
                            "123456789:AAH…", secret = !showToken, resetKey = fieldVer
                        )
                        if (cfg.tgToken.isNotEmpty()) {
                            val t = TgInput.token(cfg.tgToken)
                            val ok = TgInput.tokenOk(t)
                            val note = if (ok) {
                                "✓ صيغة التوكن صحيحة (رقم البوت " + t.substringBefore(':') + ")" +
                                    (if (t != cfg.tgToken) " · سيُنظَّف النص تلقائياً" else "")
                            } else {
                                "⚠ الصيغة غير صحيحة: يجب أن تكون أرقاماً ثم : ثم حروفاً وأرقاماً بلا مسافات"
                            }
                            Txt(note, Type.footnote, if (ok) c.green else c.orange, Modifier.padding(top = 6.dp))
                        }
                    }
                }
                row {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Txt("Chat ID", Type.subhead, c.secondary)
                        VSpace(6.dp)
                        IosInputField(
                            cfg.tgChat, { v -> ConfigStore.update { it.copy(tgChat = v) } },
                            "مثال: 123456789", keyboard = KeyboardType.Text, resetKey = fieldVer
                        )
                        if (cfg.tgChat.isNotEmpty()) {
                            val t = TgInput.chat(cfg.tgChat)
                            val ok = TgInput.chatOk(t)
                            val note = if (ok) {
                                "✓ " + TgInput.describeChat(t) + (if (t != cfg.tgChat) " · سيُصحَّح النص تلقائياً (أرقام/رموز)" else "")
                            } else {
                                "⚠ الصيغة غير صحيحة: رقم مثل 123456789 أو -100… أو @اسم_القناة"
                            }
                            Txt(note, Type.footnote, if (ok) c.green else c.orange, Modifier.padding(top = 6.dp))
                        }
                    }
                }
                row {
                    IosRow(
                        title = "اكتشاف Chat ID تلقائياً", icon = Ic.Eye, chevron = true,
                        onClick = {
                            if (!busy) {
                                val snap = normalize()
                                busy = true
                                status = "جارٍ البحث…"
                                Telegram.discoverChat(snap) { id, msg ->
                                    if (id != null) {
                                        ConfigStore.update { it.copy(tgChat = id) }
                                        fieldVer += 1
                                    }
                                    status = msg
                                    busy = false
                                }
                            }
                        }
                    )
                }
                row {
                    IosRow(
                        title = "إرسال رسالة تجريبية", icon = Ic.Share, iconBg = c.green, chevron = true,
                        onClick = {
                            if (!busy) {
                                val snap = normalize()
                                busy = true
                                status = "جارٍ الاختبار…"
                                Telegram.test(snap) { msg ->
                                    status = msg
                                    busy = false
                                }
                            }
                        }
                    )
                }
            }
        }
        item {
            if (status.isNotEmpty()) {
                val good = status.startsWith("✓")
                Txt(
                    status, Type.footnote, if (good) c.green else c.secondary,
                    Modifier.padding(horizontal = 32.dp).padding(top = 10.dp)
                )
            }
        }
        item {
            IosSection(
                "ما يُرسَل", appear = 3,
                footer = "المقطع الكامل يُرسَل بحد أقصى 50MB ويستهلك بيانات الإنترنت. تُعاد المحاولة تلقائياً عند فشل الشبكة فقط، أما أخطاء التوكن أو المحادثة فتظهر هنا فوراً."
            ) {
                row { SwitchRow("رسالة فورية عند الرصد", cfg.tgInstant, { v -> ConfigStore.update { it.copy(tgInstant = v) } }) }
                row { SwitchRow("صورة اللقطة بعد انتهاء الحدث", cfg.tgPhoto, { v -> ConfigStore.update { it.copy(tgPhoto = v) } }) }
                row { SwitchRow("المقطع الكامل", cfg.tgVideo, { v -> ConfigStore.update { it.copy(tgVideo = v) } }) }
            }
        }
        item {
            if (lastSend.isNotEmpty()) {
                Txt(
                    "آخر إرسال تلقائي: " + lastSend, Type.footnote,
                    if (lastSend.startsWith("✓")) c.green else c.orange,
                    Modifier.padding(horizontal = 32.dp).padding(top = 10.dp)
                )
            }
        }
        item {
            IosSection(
                "الشبكة (متقدم)", appear = 4,
                footer = "إن كان Telegram محجوباً لدى مزوّد الإنترنت: شغّل VPN، أو حدّد وكيل HTTP/SOCKS5 هنا، أو عنوان خادم وسيط https يمرّر طلبات Bot API (مثل Cloudflare Worker). اتركهما فارغين للاتصال المباشر."
            ) {
                row {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Txt("عنوان API بديل (اختياري)", Type.subhead, c.secondary)
                        VSpace(6.dp)
                        IosInputField(
                            cfg.tgApiBase, { v -> ConfigStore.update { it.copy(tgApiBase = v) } },
                            "https://api.telegram.org", resetKey = fieldVer
                        )
                    }
                }
                row {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Txt("وكيل Proxy (اختياري)", Type.subhead, c.secondary)
                        VSpace(6.dp)
                        IosInputField(
                            cfg.tgProxy, { v -> ConfigStore.update { it.copy(tgProxy = v) } },
                            "socks5://127.0.0.1:1080", resetKey = fieldVer
                        )
                    }
                }
            }
        }
    }
}
