package com.sentinel.eye.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.sentinel.eye.capture.CapturePlanner
import com.sentinel.eye.core.AppLog
import com.sentinel.eye.core.BiometricHelper
import com.sentinel.eye.core.Config
import com.sentinel.eye.core.ConfigStore
import com.sentinel.eye.core.Storage
import com.sentinel.eye.BuildConfig
import com.sentinel.eye.detect.Accel
import com.sentinel.eye.detect.AutoTune
import com.sentinel.eye.detect.CustomModels
import com.sentinel.eye.detect.DetectorFactory
import com.sentinel.eye.detect.DlState
import com.sentinel.eye.detect.EngineType
import com.sentinel.eye.detect.Frame
import com.sentinel.eye.detect.ImportUi
import com.sentinel.eye.detect.ModelCatalog
import com.sentinel.eye.detect.ModelManager
import com.sentinel.eye.detect.ModelSpec
import com.sentinel.eye.detect.PtKind
import com.sentinel.eye.monitor.MonitorBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

// ───────────── شاشة النماذج ─────────────

private suspend fun runBenchmark(ctx: Context, spec: ModelSpec, cfg: Config): String = withContext(Dispatchers.Default) {
    try {
        val t0 = SystemClock.elapsedRealtime()
        val det = DetectorFactory.create(ctx, spec, ModelManager.fileOf(spec), cfg)
        val loadMs = SystemClock.elapsedRealtime() - t0
        try {
            val w = 640
            val h = 360
            val argb = IntArray(w * h) { i ->
                val x = i % w
                val y = i / w
                (0xFF shl 24) or (((x * 255) / w) shl 16) or (((y * 255) / h) shl 8) or 128
            }
            val frame = Frame(argb, w, h, 0L)
            det.detect(frame, 0.5f, intArrayOf(0))
            val n = 12
            val s = SystemClock.elapsedRealtime()
            for (i in 0 until n) det.detect(frame, 0.5f, intArrayOf(0))
            val avg = (SystemClock.elapsedRealtime() - s) / n.toFloat()
            "المحرك: ${det.name}\nزمن التحميل: $loadMs ms\nمتوسط الاستدلال: %.0f ms\nالسرعة القصوى: %.1f إطار/ث".format(avg, 1000f / avg.coerceAtLeast(1f))
        } finally {
            det.close()
        }
    } catch (t: Throwable) {
        AppLog.w("Bench", "فشل اختبار الأداء", t)
        "فشل الاختبار: ${t.message}"
    }
}

@Composable
private fun ModelRow(
    spec: ModelSpec, st: DlState?, installed: Boolean, isActive: Boolean,
    onSelect: () -> Unit, onDownload: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit
) {
    val c = Ios.c
    Column {
        IosRow(
            title = spec.title,
            subtitle = spec.subtitle + " · ~" + spec.approxMb + " MB",
            onClick = {
                if (installed) onSelect() else if (st == null || st is DlState.Failed) onDownload()
            },
            trailing = {
                if (st is DlState.Progress) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val pct = if (st.total > 0) "${st.done * 100 / st.total}%" else "${st.done / 1_000_000} MB"
                        Txt(pct, Type.footnote, c.secondary)
                        HSpace(8.dp)
                        Box(
                            Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCancel)
                                .padding(4.dp)
                        ) { Glyph(Ic.Close, c.red, 18.dp) }
                    }
                } else if (installed) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AnimatedVisibility(
                            visible = isActive,
                            enter = scaleIn(spring(dampingRatio = 0.45f, stiffness = 500f)) + fadeIn(),
                            exit = scaleOut() + fadeOut()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Glyph(Ic.Check, c.blue, 22.dp)
                                HSpace(10.dp)
                            }
                        }
                        if (spec.engine != EngineType.MLKIT) {
                            Box(
                                Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDelete)
                                    .padding(4.dp)
                            ) { Glyph(Ic.Trash, c.gray, 20.dp) }
                        }
                    }
                } else {
                    Glyph(Ic.Download, c.blue, 22.dp)
                }
            }
        )
        if (st is DlState.Progress && st.total > 0) {
            ProgressBar(st.done.toFloat() / st.total.toFloat(), Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp))
        }
        if (st is DlState.Failed) {
            Txt(st.message, Type.footnote, c.red, Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp))
        }
    }
}

private fun repoSlug(baseOrSetting: String): String? {
    val r = baseOrSetting.trim()
    if (Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(r)) return r
    val m = Regex("github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)").find(r)
    return if (m != null) m.groupValues[1] + "/" + m.groupValues[2] else null
}

private fun openUrl(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Throwable) {
    }
}

private fun namesPreview(names: List<String>): String {
    if (names.isEmpty()) return "—"
    val head = names.take(12).joinToString("، ")
    return if (names.size > 12) head + " … (+" + (names.size - 12) + ")" else head
}

private fun taskArOf(t: String): String = when (t) {
    "segment" -> "تقسيم"
    "pose" -> "وضعية"
    "obb" -> "كشف مائل"
    "classify" -> "تصنيف"
    else -> "كشف"
}

@Composable
fun ModelsScreen(onImport: (ModelSpec) -> Unit) {
    val c = Ios.c
    val ctx = LocalContext.current
    val cfg by ConfigStore.flow.collectAsState()
    val states by ModelManager.states.collectAsState()
    val ver by ModelManager.version.collectAsState()
    var deleteSpec by remember { mutableStateOf<ModelSpec?>(null) }
    var showImport by remember { mutableStateOf(false) }
    var showBench by remember { mutableStateOf(false) }
    var benchText by remember { mutableStateOf("") }
    var benchRunning by remember { mutableStateOf(false) }
    var showSource by remember { mutableStateOf(false) }
    var repoText by remember { mutableStateOf(cfg.modelsRepo) }
    var showTune by remember { mutableStateOf(false) }
    var tuneRunning by remember { mutableStateOf(false) }
    var tuneLabel by remember { mutableStateOf("") }
    var tuneFrac by remember { mutableStateOf(0f) }
    var tuneResult by remember { mutableStateOf("") }
    val tuneCancel = remember { AtomicBoolean(false) }
    val scope = rememberCoroutineScope()
    val active = ModelCatalog.byId(cfg.modelId)
    val activeInstalled = active != null && ver >= 0 && ModelManager.isInstalled(active)
    val widths = listOf(320, 640, 960, 1280)
    val widthIdx = widths.indexOf(cfg.analysisWidth).let { if (it < 0) 1 else it }
    val snapshotStates = states
    val snapshotVer = ver
    val customSpecs by CustomModels.specs.collectAsState()
    val remoteList by CustomModels.remoteList.collectAsState()
    val remoteMsg by CustomModels.remoteMessage.collectAsState()
    val importUi by CustomModels.importUi.collectAsState()
    val shownUi = remember { mutableStateOf<ImportUi>(ImportUi.Idle) }
    LaunchedEffect(importUi) { if (importUi !is ImportUi.Idle) shownUi.value = importUi }
    val pickCustom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            var nm = "model"
            try {
                ctx.contentResolver.query(uri, null, null, null, null)?.use { cur ->
                    val i = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && cur.moveToFirst()) nm = cur.getString(i) ?: nm
                }
            } catch (_: Throwable) {
            }
            CustomModels.importFile(ctx.applicationContext, uri, nm)
        }
    }
    LaunchedEffect(cfg.modelsRepo) {
        if (ModelCatalog.baseUrl(cfg.modelsRepo).isNotEmpty()) CustomModels.refreshRemote(cfg.modelsRepo)
    }

    LargeTitleScreen("النماذج") {
        item {
            val base = ModelCatalog.baseUrl(cfg.modelsRepo)
            IosSection(
                "مصدر النماذج",
                footer = "نماذج YOLO تُحمَّل من إصدار «models» في مستودعك على GitHub (يجب أن يكون عاماً) بعد تشغيل Export YOLO models مرة واحدة.",
                appear = 1
            ) {
                row {
                    IosRow(
                        title = "المستودع", icon = Ic.Download, chevron = true,
                        subtitle = if (base.isEmpty()) "غير مضبوط · اضغط لإدخال owner/repo" else base,
                        onClick = { repoText = cfg.modelsRepo; showSource = true }
                    )
                }
            }
        }
        item {
            IosSection("النموذج النشط", footer = "يُطبَّق التغيير فوراً حتى أثناء المراقبة.", appear = 2) {
                row {
                    IosRow(
                        title = active?.title ?: "لم يُحدَّد", subtitle = if (active == null) null else if (activeInstalled) "جاهز للعمل" else "غير مثبّت بعد",
                        icon = Ic.Cpu, iconBg = if (activeInstalled) c.green else c.orange
                    )
                }
            }
        }
        item {
            IosSection(
                "نماذجك المخصّصة",
                footer = "استورد أي نموذج YOLO مُصدَّر إلى ONNX وستعمل أصنافه كما هي (v5 وv8 وv9 وv10 وv11 وv12، كشف أو تقسيم أو وضعية). ملفات .pt تُفحَص هنا بأمان دون تشغيل أي شيفرة ثم تُحوَّل إلى ONNX عبر مستودعك على GitHub.",
                appear = 3
            ) {
                for (sp in customSpecs) {
                    row {
                        ModelRow(
                            spec = sp, st = snapshotStates[sp.id], installed = snapshotVer >= 0 && ModelManager.isInstalled(sp),
                            isActive = cfg.modelId == sp.id,
                            onSelect = { ConfigStore.update { it.copy(modelId = sp.id) } },
                            onDownload = { }, onCancel = { ModelManager.cancel(sp.id) },
                            onDelete = { deleteSpec = sp }
                        )
                    }
                }
                val installedIds = customSpecs.map { it.id }.toSet()
                for (r in remoteList) {
                    if (installedIds.contains(r.id)) continue
                    val sp = CustomModels.remoteSpec(r, cfg.modelsRepo)
                    row {
                        ModelRow(
                            spec = sp, st = snapshotStates[sp.id], installed = false, isActive = false,
                            onSelect = { }, onDownload = { ModelManager.download(sp) },
                            onCancel = { ModelManager.cancel(sp.id) }, onDelete = { }
                        )
                    }
                }
                row {
                    IosRow(
                        title = "استيراد نموذج YOLO (.onnx / .pt)", subtitle = "اختر الملف من التخزين",
                        icon = Ic.Plus, iconBg = c.blue, chevron = true,
                        onClick = { pickCustom.launch(arrayOf("*/*")) }
                    )
                }
                row {
                    IosRow(
                        title = "تحديث النماذج المحوّلة من مستودعك",
                        subtitle = if (remoteMsg.isEmpty()) null else remoteMsg,
                        icon = Ic.Download, iconBg = c.gray, chevron = true,
                        onClick = { CustomModels.refreshRemote(cfg.modelsRepo) }
                    )
                }
            }
        }
        item {
            IosSection(
                "YOLO · الأعلى دقة",
                footer = "اضغط على النموذج لتنزيله أو لاختياره. النماذج الكبيرة بطيئة على معالج هاتفك لكنها متاحة.",
                appear = 3
            ) {
                for (sp in ModelCatalog.all.filter { it.engine == EngineType.YOLO }) {
                    row {
                        ModelRow(
                            spec = sp, st = snapshotStates[sp.id], installed = snapshotVer >= 0 && ModelManager.isInstalled(sp),
                            isActive = cfg.modelId == sp.id,
                            onSelect = { ConfigStore.update { it.copy(modelId = sp.id) } },
                            onDownload = { ModelManager.download(sp) },
                            onCancel = { ModelManager.cancel(sp.id) },
                            onDelete = { deleteSpec = sp }
                        )
                    }
                }
            }
        }
        item {
            IosSection(
                "ML Kit · مدمج بلا تنزيل",
                footer = "نماذج Google مدمجة تؤكد وجود شخص عبر الوضعية أو الوجه. أخف من YOLO وأقل دقة، وتناسب الأجهزة الضعيفة.",
                appear = 4
            ) {
                for (sp in ModelCatalog.all.filter { it.engine == EngineType.MLKIT }) {
                    row {
                        ModelRow(
                            spec = sp, st = null, installed = true, isActive = cfg.modelId == sp.id,
                            onSelect = { ConfigStore.update { it.copy(modelId = sp.id) } },
                            onDownload = { }, onCancel = { }, onDelete = { }
                        )
                    }
                }
            }
        }
        item {
            IosSection("MediaPipe · خفيف ومستقر", appear = 4) {
                for (sp in ModelCatalog.all.filter { it.engine == EngineType.MEDIAPIPE }) {
                    row {
                        ModelRow(
                            spec = sp, st = snapshotStates[sp.id], installed = snapshotVer >= 0 && ModelManager.isInstalled(sp),
                            isActive = cfg.modelId == sp.id,
                            onSelect = { ConfigStore.update { it.copy(modelId = sp.id) } },
                            onDownload = { ModelManager.download(sp) },
                            onCancel = { ModelManager.cancel(sp.id) },
                            onDelete = { deleteSpec = sp }
                        )
                    }
                }
            }
        }
        item {
            IosSection("التشغيل", footer = "XNNPACK و NNAPI قد يسرّعان بعض النماذج. إن فشل المسرّع يعود التطبيق إلى CPU تلقائياً.") {
                row {
                    SegmentRow("المسرّع", listOf("CPU", "XNNPACK", "NNAPI / GPU"), cfg.accel) { i -> ConfigStore.update { it.copy(accel = i) } }
                }
                row {
                    StepperRow("عدد الخيوط", cfg.threads, 1, 8, { v -> ConfigStore.update { it.copy(threads = v) } })
                }
                row {
                    SegmentRow("دقة إطار التحليل", widths.map { it.toString() }, widthIdx) { i ->
                        ConfigStore.update { it.copy(analysisWidth = widths[i]) }
                    }
                }
            }
        }
        item {
            IosSection("أدوات", appear = 5) {
                row {
                    IosRow(
                        title = "ضبط تلقائي للأداء", subtitle = "يجرّب المسرّعات وعدد الخيوط ويختار الأسرع لجهازك",
                        icon = Ic.Bolt, iconBg = c.green, chevron = true,
                        onClick = {
                            val sp = ModelCatalog.byId(cfg.modelId)
                            if (sp != null && sp.engine != EngineType.MLKIT && ModelManager.isInstalled(sp) && !tuneRunning) {
                                tuneRunning = true
                                tuneCancel.set(false)
                                tuneResult = ""
                                tuneFrac = 0f
                                tuneLabel = "جارٍ التحضير…"
                                showTune = true
                                scope.launch {
                                    val trials = withContext(Dispatchers.Default) {
                                        AutoTune.run(ctx, sp, ConfigStore.current, tuneCancel) { d, t, l ->
                                            tuneFrac = if (t > 0) d.toFloat() / t else 0f
                                            tuneLabel = l
                                        }
                                    }
                                    if (trials.isNotEmpty()) {
                                        val best = trials[0]
                                        ConfigStore.update { it.copy(accel = best.accel, threads = best.threads) }
                                        tuneResult = "تم اختيار: " + best.label + "  (" + "%.0f".format(best.ms) + " ms)\n\n" +
                                            trials.take(5).joinToString("\n") { it.label + " — " + "%.0f".format(it.ms) + " ms" }
                                    } else {
                                        tuneResult = "لم تنجح أي تجربة. تأكد من تثبيت النموذج."
                                    }
                                    tuneRunning = false
                                }
                            } else if (!tuneRunning) {
                                tuneResult = if (sp != null && sp.engine == EngineType.MLKIT) "الضبط التلقائي غير متاح لنماذج ML Kit." else "ثبّت النموذج النشط أولاً."
                                showTune = true
                            }
                        }
                    )
                }
                row { IosRow(title = "استيراد نموذج من ملف", icon = Ic.Download, chevron = true, onClick = { showImport = true }) }
                row {
                    IosRow(
                        title = "اختبار أداء النموذج النشط", icon = Ic.Bolt, iconBg = c.orange, chevron = true,
                        onClick = {
                            val sp = ModelCatalog.byId(cfg.modelId)
                            if (sp != null && ModelManager.isInstalled(sp) && !benchRunning) {
                                benchRunning = true
                                benchText = "جارٍ القياس… قد يستغرق بضع ثوانٍ."
                                showBench = true
                                scope.launch {
                                    benchText = runBenchmark(ctx, sp, ConfigStore.current)
                                    benchRunning = false
                                }
                            } else if (!benchRunning) {
                                benchText = "ثبّت النموذج النشط أولاً."
                                showBench = true
                            }
                        }
                    )
                }
            }
        }
    }

    IosSheet(visible = showSource, onDismiss = {
        showSource = false
        ConfigStore.update { it.copy(modelsRepo = repoText.trim()) }
    }, title = "مصدر النماذج") {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Txt("اسم المستودع على GitHub بصيغة owner/repo، أو رابط كامل لمجلد النماذج.", Type.subhead, c.secondary)
            VSpace(10.dp)
            IosTextField(repoText, { repoText = it }, "مثال: hussein/sentinel-ai")
            VSpace(10.dp)
            Txt(
                "الرابط الناتج: " + ModelCatalog.baseUrl(repoText).ifEmpty { "—" },
                Type.footnote, c.secondary
            )
            VSpace(14.dp)
            IosButton("حفظ", {
                ConfigStore.update { it.copy(modelsRepo = repoText.trim()) }
                showSource = false
            })
        }
    }
    IosSheet(visible = showTune, onDismiss = { if (!tuneRunning) showTune = false }, title = "الضبط التلقائي") {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            if (tuneRunning) {
                Txt("جارٍ اختبار: " + tuneLabel, Type.body, c.label)
                VSpace(10.dp)
                ProgressBar(tuneFrac)
                VSpace(14.dp)
                IosButton("إلغاء", { tuneCancel.set(true) }, style = BtnStyle.Destructive)
            } else {
                Txt(tuneResult.ifEmpty { "—" }, Type.body, c.label)
                VSpace(8.dp)
                Txt("أوقف المراقبة قبل الاختبار للحصول على أدق النتائج.", Type.footnote, c.secondary)
            }
        }
    }
    IosSheet(visible = showImport, onDismiss = { showImport = false }, title = "استيراد من ملف") {
        IosSection(footer = "اختر النموذج الذي تريد استيراده، ثم حدد ملفه من التخزين.") {
            for (sp in ModelCatalog.all) {
                row {
                    IosRow(title = sp.title, subtitle = sp.fileName, chevron = true, onClick = {
                        showImport = false
                        onImport(sp)
                    })
                }
            }
        }
    }
    IosSheet(visible = showBench, onDismiss = { if (!benchRunning) showBench = false }, title = "اختبار الأداء") {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Txt(benchText, Type.body, c.label)
            VSpace(8.dp)
            Txt("أوقف المراقبة قبل الاختبار للحصول على رقم دقيق.", Type.footnote, c.secondary)
        }
    }
    val ui = shownUi.value
    IosSheet(
        visible = importUi !is ImportUi.Idle,
        onDismiss = { if (importUi !is ImportUi.Working) CustomModels.dismiss() },
        title = "استيراد نموذج"
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            when (ui) {
                is ImportUi.Working -> {
                    Txt(ui.text, Type.body, c.label)
                    VSpace(10.dp)
                    ProgressBar(0f)
                }
                is ImportUi.Failed -> {
                    Txt("تعذّر الاستيراد", Type.headline, c.red)
                    VSpace(8.dp)
                    Txt(ui.message, Type.body, c.label)
                    VSpace(14.dp)
                    IosButton("إغلاق", { CustomModels.dismiss() })
                }
                is ImportUi.Done -> {
                    val meta = CustomModels.metaOf(ui.spec.id)
                    Txt("✓ تم استيراد «" + ui.spec.title + "»", Type.headline, c.green)
                    VSpace(8.dp)
                    if (meta != null) {
                        Txt(
                            "المهمة: " + taskArOf(meta.task) + " · حجم المدخل: " + ui.spec.inputSize + " · " + meta.names.size + " صنف",
                            Type.subhead, c.secondary
                        )
                        VSpace(6.dp)
                        Txt("الأصناف: " + namesPreview(meta.names), Type.subhead, c.label)
                        if (meta.task == "segment" || meta.task == "pose") {
                            VSpace(6.dp)
                            Txt("يُستخدم صندوق الكشف فقط؛ الأقنعة والنقاط لا تُرسم.", Type.footnote, c.secondary)
                        }
                    }
                    if (ui.note.isNotEmpty()) {
                        VSpace(6.dp)
                        Txt(ui.note, Type.footnote, c.orange)
                    }
                    VSpace(6.dp)
                    Txt("أصناف النموذج التي ليست أشخاصاً أو مركبات أو حيوانات تعمل دائماً، أما هذه الثلاثة فتتبع مفاتيحها في الإعدادات.", Type.footnote, c.secondary)
                    VSpace(14.dp)
                    IosButton("تفعيل هذا النموذج الآن", {
                        ConfigStore.update { it.copy(modelId = ui.spec.id) }
                        CustomModels.dismiss()
                    })
                    VSpace(8.dp)
                    IosButton("لاحقاً", { CustomModels.dismiss() }, style = BtnStyle.Plain)
                }
                is ImportUi.Pt -> {
                    val r = ui.report
                    val mb = (r.sizeBytes / 1_000_000L).coerceAtLeast(1L)
                    when (r.kind) {
                        PtKind.ULTRALYTICS, PtKind.YOLOV5_LEGACY -> {
                            val fam = if (r.family.isNotEmpty()) r.family else if (r.kind == PtKind.YOLOV5_LEGACY) "YOLOv5/v7" else "YOLO"
                            Txt("ملف PyTorch · " + fam + " · " + taskArOf(r.task), Type.headline, c.label)
                            VSpace(6.dp)
                            Txt(
                                r.fileName + " · " + mb + " MB" + (if (r.imgsz > 0) " · حجم التدريب " + r.imgsz else ""),
                                Type.footnote, c.secondary
                            )
                            VSpace(6.dp)
                            Txt("الأصناف (" + r.names.size + "): " + namesPreview(r.names), Type.subhead, c.label)
                            VSpace(10.dp)
                            if (r.risks.isNotEmpty()) {
                                Txt("⚠ الملف يحتوي استدعاءات خطيرة: " + r.risks.take(3).joinToString("، "), Type.subhead, c.red)
                                VSpace(6.dp)
                                Txt("لن نحوّله. ملفات .pt قد تنفّذ شيفرة عند فتحها؛ لا تفتحه في أي مكان ولا تثق بمصدره.", Type.footnote, c.secondary)
                            } else {
                                Txt("✓ فحص الأمان: لا استدعاءات مشبوهة (فُحص الملف دون تشغيل أي شيفرة).", Type.subhead, c.green)
                                VSpace(8.dp)
                                Txt(
                                    "ONNX Runtime لا يشغّل .pt مباشرة، فهو كائن PyTorch يحتاج Python. يُحوَّل مرة واحدة إلى ONNX بمُصدِّر Ultralytics الرسمي ثم يُنزَّل هنا بأصنافه الخاصة:",
                                    Type.footnote, c.secondary
                                )
                                VSpace(8.dp)
                                Txt(
                                    "١) افتح صفحة الإصدارات في مستودعك، أنشئ إصداراً بوسم pt-inbox وأرفق هذا الملف (حتى 2GB) ثم انشره.\n" +
                                        "٢) يعمل سير عمل «Convert custom YOLO» تلقائياً (نحو 3–6 دقائق).\n" +
                                        "٣) ارجع هنا واضغط «تحديث النماذج المحوّلة»، ثم نزّل النموذج.",
                                    Type.subhead, c.label
                                )
                                VSpace(14.dp)
                                val slug = repoSlug(ModelCatalog.baseUrl(cfg.modelsRepo))
                                if (slug != null) {
                                    IosButton("فتح صفحة الإصدارات في GitHub", {
                                        openUrl(ctx, "https://github.com/" + slug + "/releases/new?tag=pt-inbox&title=pt-inbox&prerelease=1")
                                    })
                                } else {
                                    Txt("حدّد مستودعك أولاً من «مصدر النماذج» (owner/repo).", Type.footnote, c.orange)
                                }
                            }
                            VSpace(8.dp)
                            IosButton("إغلاق", { CustomModels.dismiss() }, style = BtnStyle.Plain)
                        }
                        PtKind.TORCHSCRIPT -> {
                            Txt("ملف TorchScript", Type.headline, c.label)
                            VSpace(8.dp)
                            Txt("لا يمكن تحويل TorchScript إلى ONNX بمُصدِّر Ultralytics. صدّر النموذج من ملف الأوزان الأصلي (best.pt) إلى ONNX، ثم استورد ملف .onnx.", Type.body, c.label)
                            VSpace(14.dp)
                            IosButton("إغلاق", { CustomModels.dismiss() })
                        }
                        PtKind.OTHER_TORCH -> {
                            Txt("ملف PyTorch غير مدعوم للتحويل", Type.headline, c.label)
                            VSpace(8.dp)
                            Txt("ليس نقطة ضبط YOLO من Ultralytics أو YOLOv5. إن كان YOLOv7 أو YOLOX أو YOLO-NAS فصدّره إلى ONNX من مستودعه الأصلي ثم استورد ملف .onnx.", Type.body, c.label)
                            VSpace(14.dp)
                            IosButton("إغلاق", { CustomModels.dismiss() })
                        }
                        PtKind.NOT_TORCH -> {
                            Txt("الملف ليس نقطة ضبط PyTorch صالحة", Type.headline, c.red)
                            VSpace(8.dp)
                            Txt("قد يكون تالفاً أو بصيغة أخرى.", Type.body, c.label)
                            VSpace(14.dp)
                            IosButton("إغلاق", { CustomModels.dismiss() })
                        }
                    }
                }
                else -> {
                }
            }
        }
    }
    val del = deleteSpec
    if (del != null) {
        IosAlert(
            title = "حذف ${del.title}؟", message = "يمكنك تنزيله مجدداً لاحقاً.", confirmText = "حذف", destructive = true,
            onConfirm = { ModelManager.delete(del); deleteSpec = null },
            onDismiss = { deleteSpec = null }
        )
    }
}

// ───────────── شاشة الإعدادات ─────────────

@Composable
fun SettingsScreen(onOpenDiagnostics: () -> Unit, onOpenShizuku: () -> Unit, onOpenTelegram: () -> Unit) {
    val c = Ios.c
    val ctx = LocalContext.current
    val cfg by ConfigStore.flow.collectAsState()
    val changed by MonitorBus.eventsChanged.collectAsState()
    var confirmWipe by remember { mutableStateOf(false) }
    val evTotal by produceState(0L, changed) { value = withContext(Dispatchers.IO) { Storage.events.totalBytes() } }
    val upd: ((Config) -> Config) -> Unit = { f -> ConfigStore.update(f) }
    var pinFlow by remember { mutableStateOf<PinFlow?>(null) }
    val bioAvail = remember { BiometricHelper.available(ctx) }
    val lockTimeouts = listOf(0, 30, 60, 300)
    val audioPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) upd { it.copy(audio = true) }
    }
    val oriValues = listOf(0, 90, 270)
    val heights = listOf(480, 720, 1080)
    val fpsValues = listOf(15, 24, 30)

    LargeTitleScreen("الإعدادات") {
        item {
            IosSection(
                "الأداء",
                footer = "يرفع أولوية خيوط التحليل ويفعّل تلميحات ADPF وإشارة «طور اللعب» للنظام، والتطبيق مصنّف كلعبة ليحصل على أعلى أداء ممكن. يزيد استهلاك البطارية والحرارة.",
                appear = 1
            ) {
                row { SwitchRow("وضع الأداء الأقصى", cfg.maxPerf, { v -> upd { it.copy(maxPerf = v) } }) }
                row {
                    IosRow(
                        title = "Game Turbo (HyperOS)", subtitle = "أضف التطبيق إلى قائمة الألعاب في تطبيق الأمان لأقصى أداء",
                        icon = Ic.Bolt, iconBg = c.orange, chevron = true,
                        onClick = { openGameTurbo(ctx) }
                    )
                }
            }
        }
        item {
            IosSection("الكشف", footer = "الحساسية الأعلى تلتقط أهدافاً أكثر لكنها قد تزيد الإنذارات الكاذبة.", appear = 2) {
                row { SwitchRow("أشخاص", cfg.detectPersons, { v -> upd { it.copy(detectPersons = v) } }) }
                row { SwitchRow("مركبات", cfg.detectVehicles, { v -> upd { it.copy(detectVehicles = v) } }) }
                row { SwitchRow("حيوانات", cfg.detectAnimals, { v -> upd { it.copy(detectAnimals = v) } }) }
                row {
                    SliderRow("حد الثقة", "${(cfg.confidence * 100).toInt()}%", cfg.confidence, 0.2f..0.9f, { v -> upd { it.copy(confidence = v) } })
                }
                row {
                    StepperRow("أصغر حجم للهدف", cfg.minHeightPct, 0, 60, { v -> upd { it.copy(minHeightPct = v) } }, 2, "نسبة من ارتفاع الإطار") { "$it%" }
                }
                row {
                    StepperRow("إطارات التأكيد", cfg.confirmFrames, 1, 8, { v -> upd { it.copy(confirmFrames = v) } }, 1, "عدد الكشوف المتتالية قبل بدء التسجيل")
                }
                row {
                    StepperRow("معدل التحليل", cfg.analysisFps, 0, 30, { v -> upd { it.copy(analysisFps = v) } }, 1, "«الأقصى» يحلل كل إطار تتيحه الكاميرا") { if (it == 0) "الأقصى" else "$it/ث" }
                }
                row {
                    SwitchRow("بوابة الحركة", cfg.motionGate, { v -> upd { it.copy(motionGate = v) } }, "تخطّي التحليل عند سكون المشهد لتوفير المعالج")
                }
            }
        }
        item {
            IosSection("التسجيل", footer = "تغيير إعدادات الفيديو يعيد تشغيل الكاميرا تلقائياً.", appear = 3) {
                row {
                    StepperRow("قبل الظهور (Pre-roll)", cfg.preRollSec, 0, 30, { v -> upd { it.copy(preRollSec = v) } }, 1, null) { "$it ث" }
                }
                row {
                    StepperRow("بعد الاختفاء", cfg.postRollSec, 2, 120, { v -> upd { it.copy(postRollSec = v) } }, 1, null) { "$it ث" }
                }
                row {
                    StepperRow("أقصى مدة للملف", cfg.maxEventMin, 1, 60, { v -> upd { it.copy(maxEventMin = v) } }, 1, null) { "$it د" }
                }
                row {
                    SwitchRow(
                        "تسجيل الصوت", cfg.audio,
                        { v ->
                            if (!v) {
                                upd { it.copy(audio = false) }
                            } else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                upd { it.copy(audio = true) }
                            } else {
                                audioPerm.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        "يشمل الصوت قبل الظهور أيضاً · يُطبَّق عند إعادة تشغيل المراقبة"
                    )
                }
                row {
                    SegmentRow("الدقة", listOf("480p", "720p", "1080p"), heights.indexOf(cfg.videoHeight).coerceAtLeast(0)) { i ->
                        upd { it.copy(videoHeight = heights[i]) }
                    }
                }
                row {
                    SegmentRow("الإطارات في الثانية", listOf("15", "24", "30"), fpsValues.indexOf(cfg.fps).coerceAtLeast(0)) { i ->
                        upd { it.copy(fps = fpsValues[i]) }
                    }
                }
                row {
                    SegmentRow("الجودة", listOf("اقتصادي", "متوازن", "عالٍ"), cfg.quality) { i -> upd { it.copy(quality = i) } }
                }
                row {
                    SegmentRow("الترميز", listOf("H.264", "H.265"), cfg.codec) { i -> upd { it.copy(codec = i) } }
                }
                row {
                    SegmentRow("الكاميرا", listOf("خلفية", "أمامية"), if (cfg.useFront) 1 else 0) { i -> upd { it.copy(useFront = i == 1) } }
                }
                row {
                    SegmentRow("اتجاه الهاتف", listOf("طولي", "عرضي", "عرضي معكوس"), oriValues.indexOf(cfg.orientation).coerceAtLeast(0)) { i ->
                        upd { it.copy(orientation = oriValues[i]) }
                    }
                }
            }
        }
        item {
            IosSection("التخزين", footer = "تُحفظ التسجيلات في مجلد التطبيق الخاص وتُحذف عند إزالة التطبيق. يُحذف الأقدم تلقائياً عند بلوغ الحد.") {
                row { IosRow(title = "المستخدم حالياً", value = fmtSize(evTotal)) }
                row { IosRow(title = "المساحة الحرة", value = fmtSize(Storage.freeBytes())) }
                row {
                    StepperRow("الحد الأقصى", cfg.maxStorageGb, 1, 200, { v -> upd { it.copy(maxStorageGb = v) } }, 1, null) { "$it GB" }
                }
                row {
                    StepperRow("الحذف التلقائي بعد", cfg.maxDays, 0, 90, { v -> upd { it.copy(maxDays = v) } }, 1, "0 يعني بلا حد زمني") { if (it == 0) "—" else "$it يوم" }
                }
                row { IosRow(title = "حذف كل التسجيلات", destructive = true, onClick = { confirmWipe = true }) }
            }
        }
        item {
            IosSection("التنبيهات") {
                row { SwitchRow("إشعار عند رصد حدث", cfg.notify, { v -> upd { it.copy(notify = v) } }) }
                row { SwitchRow("منبّه صوتي", cfg.alarm, { v -> upd { it.copy(alarm = v) } }, "يُشغَّل بصوت المنبّه عند بدء حدث") }
            }
        }
        item {
            IosSection("المظهر") {
                row { SegmentRow("السمة", listOf("تلقائي", "فاتح", "داكن"), cfg.appearance) { i -> upd { it.copy(appearance = i) } } }
            }
        }
        item {
            IosSection(
                "الأمان",
                footer = "يُطلب الرمز عند فتح التطبيق وبعد البقاء في الخلفية للمدة المحددة. المراقبة تستمر في الخلفية بلا قفل.",
                appear = 4
            ) {
                row { SwitchRow("قفل التطبيق", cfg.lockEnabled, { v -> pinFlow = if (v) PinFlow.Enable else PinFlow.Disable }) }
                if (cfg.lockEnabled) {
                    row { IosRow(title = "تغيير الرمز", icon = Ic.Shield, chevron = true, onClick = { pinFlow = PinFlow.Change }) }
                    if (bioAvail) {
                        row { SwitchRow("فتح بالبصمة", cfg.lockBio, { v -> upd { it.copy(lockBio = v) } }) }
                    }
                    row {
                        SegmentRow("القفل بعد", listOf("فوراً", "30 ث", "دقيقة", "5 د"), lockTimeouts.indexOf(cfg.lockTimeout).let { if (it < 0) 1 else it }) { i ->
                            upd { it.copy(lockTimeout = lockTimeouts[i]) }
                        }
                    }
                    row { SwitchRow("إخفاء المعاينة في التطبيقات الأخيرة", cfg.lockHidePreview, { v -> upd { it.copy(lockHidePreview = v) } }, "ويمنع لقطات الشاشة") }
                }
            }
        }
        item {
            IosSection("المزايا المتقدمة", appear = 5) {
                row { IosRow(title = "Shizuku", subtitle = "لوحة الأداء الأقصى والاستمرارية", icon = Ic.Bolt, iconBg = c.orange, chevron = true, onClick = onOpenShizuku) }
                row {
                    IosRow(
                        title = "Telegram", subtitle = if (cfg.tgEnabled) "مفعّل" else "تنبيهات وصور ومقاطع على هاتفك الآخر",
                        icon = Ic.Share, iconBg = c.blue, chevron = true, onClick = onOpenTelegram
                    )
                }
            }
        }
        item {
            IosSection("النظام") {
                row { IosRow(title = "التشخيص والسجل", icon = Ic.Doc, iconBg = c.gray, chevron = true, onClick = onOpenDiagnostics) }
            }
        }
    }
    PinFlowSheet(pinFlow) { pinFlow = null }
    if (confirmWipe) {
        IosAlert(
            title = "حذف كل التسجيلات؟", message = "لا يمكن التراجع عن هذا الإجراء.", confirmText = "حذف", destructive = true,
            onConfirm = {
                confirmWipe = false
                Storage.events.deleteAll()
                MonitorBus.eventsChanged.value = System.currentTimeMillis()
            },
            onDismiss = { confirmWipe = false }
        )
    }
}

// ───────────── التشخيص ─────────────

@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val c = Ios.c
    val ctx = LocalContext.current
    var logText by remember { mutableStateOf(AppLog.tail(150)) }
    val camReport = remember { CapturePlanner.report(ctx) }
    LargeTitleScreen(
        title = "التشخيص",
        leading = {
            Box(
                Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onBack)
                    .padding(8.dp)
            ) { Glyph(Ic.ChevR, c.blue, 22.dp) }
        }
    ) {
        item {
            IosSection("الجهاز") {
                row { IosRow(title = "الطراز", value = Build.MANUFACTURER + " " + Build.MODEL) }
                row { IosRow(title = "أندرويد", value = Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")") }
                row { IosRow(title = "المعمارية", value = Build.SUPPORTED_ABIS.firstOrNull() ?: "?") }
                row { IosRow(title = "المعالج", value = Build.HARDWARE) }
            }
        }
        item {
            IosSection("الكاميرات") {
                row { IosRow(title = camReport) }
            }
        }
        item {
            IosSection("السجل", footer = "أرسل هذا السجل عند حدوث أي خلل ليسهل تشخيصه.") {
                row {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Txt(
                            logText.ifEmpty { "السجل فارغ" },
                            TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 14.sp),
                            c.secondary
                        )
                    }
                }
                row { IosRow(title = "تحديث", onClick = { logText = AppLog.tail(150) }) }
                row {
                    IosRow(title = "مشاركة ملف السجل", icon = Ic.Share, onClick = {
                        val f = AppLog.logFile()
                        if (f != null && f.exists()) shareFile(ctx, f, "text/plain")
                    })
                }
                row { IosRow(title = "مسح السجل", destructive = true, onClick = { AppLog.clear(); logText = "" }) }
            }
        }
    }
}

// ───────────── الإعداد الأول ─────────────

private fun openIntentSafe(ctx: Context, intent: Intent): Boolean {
    return try {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Throwable) {
        false
    }
}

/** يحاول فتح Game Turbo في شاومي، وإلا يفتح تطبيق الأمان ليضيف المستخدم التطبيق يدوياً. */
private fun openGameTurbo(ctx: Context): Boolean {
    val tries = listOf<Intent?>(
        Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.gamebooster.ui.GameBoosterRealMainActivity")),
        Intent("miui.intent.action.GAMEBOOSTER"),
        ctx.packageManager.getLaunchIntentForPackage("com.miui.securitycenter")
    )
    for (i in tries) {
        if (i != null && openIntentSafe(ctx, i)) return true
    }
    return false
}

private fun appDetails(ctx: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))

@Composable
fun OnboardingScreen() {
    val c = Ios.c
    val ctx = LocalContext.current
    val cfg by ConfigStore.flow.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }
    val camOk = remember(tick) { ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED }
    val notifOk = remember(tick) {
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val batteryOk = remember(tick) {
        try {
            ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
        } catch (_: Throwable) {
            false
        }
    }
    val camLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LargeTitleScreen("مرحباً بك") {
        item {
            Callout(
                Ic.Shield, c.blue, "الحارس الذكي",
                "يحوّل هاتفك إلى كاميرا مراقبة تسجّل اللحظات التي يظهر فيها أشخاص، مع ثوانٍ قبل ظهورهم. جهّز الأذونات التالية لعمل مستقر."
            )
        }
        item {
            IosSection("الأذونات الأساسية", appear = 1) {
                row {
                    IosRow(
                        title = "الكاميرا", subtitle = "مطلوب للمراقبة", icon = Ic.Camera,
                        trailing = {
                            if (camOk) Glyph(Ic.Check, c.green, 22.dp)
                            else SmallButton("السماح", { camLauncher.launch(Manifest.permission.CAMERA) })
                        }
                    )
                }
                row {
                    IosRow(
                        title = "الإشعارات", subtitle = "لإشعار تشغيل الخدمة وتنبيهات الأحداث", icon = Ic.Bell, iconBg = c.red,
                        trailing = {
                            if (notifOk) Glyph(Ic.Check, c.green, 22.dp)
                            else SmallButton("السماح", {
                                if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            })
                        }
                    )
                }
            }
        }
        item {
            IosSection(
                "لعمل مستقر على هاتف شاومي (HyperOS)",
                footer = "يقتل HyperOS التطبيقات في الخلفية بشدة. أكمل الخطوات لضمان استمرار المراقبة وأقصى أداء.",
                appear = 2
            ) {
                row {
                    IosRow(
                        title = "البطارية: بدون قيود", subtitle = "استثناء التطبيق من تحسين البطارية", icon = Ic.Bolt, iconBg = c.green,
                        trailing = {
                            if (batteryOk) Glyph(Ic.Check, c.green, 22.dp)
                            else SmallButton("فتح", {
                                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName))
                                if (!openIntentSafe(ctx, i)) openIntentSafe(ctx, appDetails(ctx))
                            })
                        }
                    )
                }
                row {
                    IosRow(
                        title = "التشغيل التلقائي", subtitle = "فعّله لهذا التطبيق من إعدادات الأمان", icon = Ic.Gear, iconBg = c.gray,
                        trailing = {
                            if (cfg.autostartAck) Glyph(Ic.Check, c.green, 22.dp)
                            else SmallButton("فتح", {
                                val i = Intent().setComponent(
                                    ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                                )
                                if (!openIntentSafe(ctx, i)) openIntentSafe(ctx, appDetails(ctx))
                                ConfigStore.update { it.copy(autostartAck = true) }
                            })
                        }
                    )
                }
                row {
                    IosRow(
                        title = "Game Turbo", subtitle = "أضف التطبيق كلعبة في تطبيق الأمان لأقصى أداء", icon = Ic.Bolt, iconBg = c.orange,
                        trailing = { SmallButton("فتح", { openGameTurbo(ctx) }) }
                    )
                }
                row {
                    IosRow(
                        title = "تثبيت التطبيق في الأخيرة",
                        subtitle = "افتح قائمة التطبيقات الأخيرة، اضغط مطولاً على بطاقة التطبيق واختر «قفل»",
                        icon = Ic.Shield, iconBg = c.orange
                    )
                }
            }
        }
        item {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 24.dp)) {
                IosButton("ابدأ الاستخدام", { ConfigStore.update { it.copy(onboardingDone = true) } }, enabled = camOk)
            }
        }
    }
}
