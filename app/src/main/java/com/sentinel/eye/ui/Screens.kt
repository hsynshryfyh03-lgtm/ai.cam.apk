package com.sentinel.eye.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.sentinel.eye.analysis.CancelledException
import com.sentinel.eye.analysis.EventMerger
import com.sentinel.eye.core.Config
import com.sentinel.eye.core.ConfigStore
import com.sentinel.eye.core.EventMeta
import com.sentinel.eye.core.Storage
import com.sentinel.eye.detect.Coco
import com.sentinel.eye.detect.ModelCatalog
import com.sentinel.eye.detect.ModelManager
import com.sentinel.eye.monitor.LiveFrame
import com.sentinel.eye.monitor.MonitorBus
import com.sentinel.eye.monitor.MonitorService
import com.sentinel.eye.monitor.MonitorStatus
import com.sentinel.eye.monitor.Stats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ───────────── أدوات مساعدة ─────────────

fun fmtSize(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.2f GB".format(Locale.US, bytes / 1e9)
    bytes >= 1_000_000L -> "%.1f MB".format(Locale.US, bytes / 1e6)
    else -> "%d KB".format(Locale.US, bytes / 1000)
}

fun fmtDuration(ms: Long): String {
    val s = (ms / 1000).toInt().coerceAtLeast(0)
    return "%d:%02d".format(Locale.US, s / 60, s % 60)
}

fun fmtDate(ms: Long): String = SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.US).format(Date(ms))

fun shareFile(ctx: Context, file: File, mime: String) {
    try {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        val i = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "مشاركة").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Throwable) {
    }
}

// ───────────── عرض الإطار الحي ─────────────

@Composable
fun FrameView(
    frame: LiveFrame?, cfg: Config, modifier: Modifier = Modifier,
    showZoneFill: Boolean = false, showLabels: Boolean = true
) {
    val c = Ios.c
    val bmp = frame?.bitmap
    val dets = frame?.detections ?: emptyList()
    BoxWithConstraints(modifier.background(Color.Black)) {
        val w = maxWidth
        val h = maxHeight
        if (bmp != null) {
            val img = remember(bmp) { bmp.asImageBitmap() }
            Image(bitmap = img, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            Canvas(Modifier.fillMaxSize()) {
                val pw = size.width
                val ph = size.height
                for (d in dets) {
                    drawRoundRect(
                        color = c.green,
                        topLeft = Offset(d.left * pw, d.top * ph),
                        size = Size(d.width * pw, d.height * ph),
                        cornerRadius = CornerRadius(10f),
                        style = Stroke(width = 3.5f)
                    )
                }
                if (cfg.zoneEnabled) {
                    val tl = Offset(cfg.zoneL * pw, cfg.zoneT * ph)
                    val sz = Size((cfg.zoneR - cfg.zoneL) * pw, (cfg.zoneB - cfg.zoneT) * ph)
                    if (showZoneFill) drawRect(color = c.orange.copy(alpha = 0.18f), topLeft = tl, size = sz)
                    drawRect(
                        color = c.orange, topLeft = tl, size = sz,
                        style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
                    )
                }
            }
            if (showLabels) {
                for (d in dets.take(4)) {
                    Box(
                        Modifier.absoluteOffset(x = w * d.left, y = maxOf(h * d.top - 20.dp, 0.dp))
                            .clip(RoundedCornerShape(6.dp)).background(c.green)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Txt(Coco.labelAr(d.classId) + " " + (d.score * 100).toInt() + "%", Type.caption, Color.Black, maxLines = 1)
                    }
                }
            }
        } else {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Glyph(Ic.Eye, Color.White.copy(alpha = 0.55f), 40.dp)
                VSpace(8.dp)
                Txt("لا يوجد بث حالياً", Type.subhead, Color.White.copy(alpha = 0.7f))
            }
        }
    }
}

private fun statusLabel(s: MonitorStatus, stats: Stats, red: Color, green: Color, orange: Color, gray: Color): Pair<String, Color> =
    when {
        stats.recording -> "يسجّل" to red
        s is MonitorStatus.Running -> "مباشر" to green
        s is MonitorStatus.Starting -> "جارٍ التشغيل" to orange
        s is MonitorStatus.Error -> (if (s.retrying) "إعادة المحاولة" else "خطأ") to red
        else -> "متوقفة" to gray
    }

// ───────────── شاشة المراقبة ─────────────

@Composable
fun LiveScreen(onGoModels: () -> Unit) {
    val c = Ios.c
    val ctx = LocalContext.current
    val status by MonitorBus.status.collectAsState()
    val stats by MonitorBus.stats.collectAsState()
    val frame by MonitorBus.frame.collectAsState()
    val cfg by ConfigStore.flow.collectAsState()
    val modelVer by ModelManager.version.collectAsState()
    DisposableEffect(Unit) {
        MonitorBus.liveVisible = true
        onDispose { MonitorBus.liveVisible = false }
    }
    val s = status
    val spec = ModelCatalog.byId(cfg.modelId)
    val modelReady = spec != null && modelVer >= 0 && ModelManager.isInstalled(spec)
    val active = s is MonitorStatus.Starting || s is MonitorStatus.Running || (s is MonitorStatus.Error && s.retrying)
    var showZone by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) MonitorService.start(ctx)
    }
    val startMonitoring = {
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) MonitorService.start(ctx) else permLauncher.launch(Manifest.permission.CAMERA)
    }
    val elapsed by produceState(0L, stats.eventStartMs) {
        while (true) {
            value = if (stats.eventStartMs > 0L) (System.currentTimeMillis() - stats.eventStartMs) / 1000L else 0L
            delay(500)
        }
    }
    val label = statusLabel(s, stats, c.red, c.green, c.orange, c.gray)
    val aspect = frame?.bitmap?.let { if (it.height > 0) it.width.toFloat() / it.height else 16f / 9f } ?: (16f / 9f)
    val widthFrac = if (aspect >= 1f) 1f else 0.62f
    val pulseT = rememberInfiniteTransition(label = "pulse")
    val pulse by pulseT.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(780), RepeatMode.Reverse), label = "pa"
    )
    val recBorder = if (stats.recording) c.red else Color.Transparent

    Box(Modifier.fillMaxSize()) {
        LargeTitleScreen(title = "المراقبة", trailing = { Pill(label.first, label.second) }) {
            item {
                Box(Modifier.fillMaxWidth().appear(1).padding(horizontal = 16.dp).padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.fillMaxWidth(widthFrac).aspectRatio(aspect).clip(RoundedCornerShape(14.dp))
                            .border(2.5.dp, recBorder.copy(alpha = 0.25f + 0.75f * pulse), RoundedCornerShape(14.dp))
                    ) {
                        FrameView(frame, cfg, Modifier.fillMaxSize())
                        Row(
                            Modifier.align(Alignment.TopStart).padding(10.dp).clip(RoundedCornerShape(50))
                                .background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(8.dp).alpha(if (stats.recording) pulse else 1f).clip(CircleShape).background(label.second))
                            HSpace(6.dp)
                            val t = if (stats.recording) "تسجيل  " + fmtDuration(elapsed * 1000L) else label.first
                            Txt(t, Type.caption, Color.White, maxLines = 1)
                        }
                    }
                }
            }
            item {
                Box(Modifier.fillMaxWidth().appear(2).padding(horizontal = 16.dp).padding(top = 16.dp)) {
                    Crossfade(targetState = active, animationSpec = tween(260), label = "ctl") { a ->
                        if (a) {
                            IosButton("إيقاف المراقبة", { MonitorService.stop(ctx) }, style = BtnStyle.Destructive, icon = Ic.Stop)
                        } else {
                            IosButton("بدء المراقبة", { startMonitoring() }, enabled = modelReady, icon = Ic.Play)
                        }
                    }
                }
            }
            if (!modelReady) {
                item {
                    Callout(
                        Ic.Cpu, c.orange, "لا يوجد نموذج جاهز",
                        "ثبّت نموذجاً للكشف من تبويب «النماذج» ثم اخترْه. يُنصح بـ YOLO11 Nano لهاتفك.",
                        appear = 3
                    ) { SmallButton("فتح النماذج", onGoModels) }
                }
            }
            if (s is MonitorStatus.Error) {
                item { Callout(Ic.Warning, c.red, "تنبيه", s.message) }
            }
            if (active && stats.modelNote.isNotEmpty()) {
                item { Callout(Ic.Warning, c.orange, "النموذج", stats.modelNote) }
            }
            item {
                IosSection("تفاصيل التشغيل", footer = if (active) null else "تظهر التفاصيل عند بدء المراقبة.", appear = 3) {
                    row { IosRow(title = "المحرك", value = if (stats.engine.isEmpty()) "—" else null, subtitle = stats.engine.ifEmpty { null }) }
                    row { IosRow(title = "زمن الاستدلال", value = if (stats.inferMs > 0) "${stats.inferMs} ms" else "—") }
                    row { IosRow(title = "معدل التحليل", value = "%.1f إطار/ث".format(Locale.US, stats.fps)) }
                    row { IosRow(title = "ذاكرة ما قبل الظهور", value = "${stats.bufferSec} ث") }
                    row { IosRow(title = "حرارة النظام", value = stats.thermal.ifEmpty { "—" }) }
                    row { IosRow(title = "تسريع الأداء (ADPF)", value = if (stats.hint) "فعّال" else "غير فعّال") }
                    row { IosRow(title = "الفيديو", subtitle = stats.video.ifEmpty { null }, value = if (stats.video.isEmpty()) "—" else null) }
                }
            }
            item {
                IosSection("منطقة المراقبة", footer = "يُسجَّل الحدث فقط حين يظهر الهدف داخل المنطقة المحددة.", appear = 4) {
                    row {
                        SwitchRow("تفعيل المنطقة", cfg.zoneEnabled, { v -> ConfigStore.update { it.copy(zoneEnabled = v) } })
                    }
                    row { IosRow(title = "تعديل المنطقة", icon = Ic.Sliders, chevron = true, onClick = { showZone = true }) }
                }
            }
        }
        IosSheet(visible = showZone, onDismiss = { showZone = false }, title = "منطقة المراقبة") {
            ZoneEditor(frame, cfg)
        }
    }
}

@Composable
private fun ZoneEditor(frame: LiveFrame?, cfg: Config) {
    val bmp = frame?.bitmap
    val aspect = if (bmp != null && bmp.height > 0) bmp.width.toFloat() / bmp.height else 16f / 9f
    val widthFrac = if (aspect >= 1f) 1f else 0.5f
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 6.dp), contentAlignment = Alignment.Center) {
        FrameView(
            frame, cfg.copy(zoneEnabled = true),
            Modifier.fillMaxWidth(widthFrac).aspectRatio(aspect).clip(RoundedCornerShape(12.dp)),
            showZoneFill = true
        )
    }
    IosSection("الحدود", footer = "حرّك المؤشرات لتحديد المستطيل. الإطار البرتقالي هو منطقة الكشف.") {
        row {
            SwitchRow("تفعيل المنطقة", cfg.zoneEnabled, { v -> ConfigStore.update { it.copy(zoneEnabled = v) } })
        }
        row {
            SliderRow("الحد الأيسر", "${(cfg.zoneL * 100).toInt()}%", cfg.zoneL, 0f..0.9f, { v ->
                ConfigStore.update { it.copy(zoneEnabled = true, zoneL = v.coerceAtMost(it.zoneR - 0.05f)) }
            })
        }
        row {
            SliderRow("الحد الأيمن", "${(cfg.zoneR * 100).toInt()}%", cfg.zoneR, 0.1f..1f, { v ->
                ConfigStore.update { it.copy(zoneEnabled = true, zoneR = v.coerceAtLeast(it.zoneL + 0.05f)) }
            })
        }
        row {
            SliderRow("الحد العلوي", "${(cfg.zoneT * 100).toInt()}%", cfg.zoneT, 0f..0.9f, { v ->
                ConfigStore.update { it.copy(zoneEnabled = true, zoneT = v.coerceAtMost(it.zoneB - 0.05f)) }
            })
        }
        row {
            SliderRow("الحد السفلي", "${(cfg.zoneB * 100).toInt()}%", cfg.zoneB, 0.1f..1f, { v ->
                ConfigStore.update { it.copy(zoneEnabled = true, zoneB = v.coerceAtLeast(it.zoneT + 0.05f)) }
            })
        }
    }
}

// ───────────── شاشة الأحداث ─────────────

/**
 * حالة التحديد المتعدد. الضغط المطوّل على حدث يبدأ التحديد، ثم السحب دون رفع الإصبع يحدّد (أو يلغي تحديد)
 * كل الأحداث بين الحدث الأول والحدث تحت الإصبع، مع تمرير تلقائي عند الاقتراب من حافتي القائمة.
 */
private class SelState {
    var mode by mutableStateOf(false)
    var ids by mutableStateOf<Set<String>>(emptySet())
    var dragging by mutableStateOf(false)
    var pointerY by mutableFloatStateOf(0f)
    var containerTop = 0f
    var height = 0f
    var topBar = 0f
    var haptic: (() -> Unit)? = null
    val rows = HashMap<String, FloatArray>()
    private var anchor = -1
    private var base: Set<String> = emptySet()
    private var adding = true

    fun hit(rootY: Float): String? {
        for ((id, r) in rows) {
            if (rootY >= r[0] && rootY < r[1]) return id
        }
        return null
    }

    fun begin(all: List<String>, idx: Int) {
        base = ids
        anchor = idx
        adding = !base.contains(all[idx])
        ids = if (adding) base + all[idx] else base - all[idx]
        mode = true
        dragging = true
        haptic?.invoke()
    }

    fun update(all: List<String>, idx: Int) {
        if (!dragging || anchor < 0 || idx < 0 || idx >= all.size) return
        val lo = minOf(anchor, idx)
        val hi = maxOf(anchor, idx)
        val range = HashSet<String>()
        for (i in lo..hi) range.add(all[i])
        ids = if (adding) base + range else base - range
    }

    fun end() {
        dragging = false
        if (ids.isEmpty()) mode = false
    }

    fun toggle(id: String) {
        ids = if (ids.contains(id)) ids - id else ids + id
        if (ids.isEmpty()) mode = false
    }

    fun clear() {
        ids = emptySet()
        mode = false
        dragging = false
    }
}

private class MergeAsk(val group: List<EventMeta>, val selected: Int)

/**
 * يراقب اللمس في مرحلة Initial (قبل القائمة القابلة للتمرير) فيميّز الضغط المطوّل من النقر والتمرير:
 * النقر والتمرير يمرّان دون أي تدخل، أما الضغط المطوّل فيبدأ التحديد ثم يستهلك حركات الإصبع
 * حتى لا تتمرر القائمة ولا يُفتح الحدث عند الرفع.
 */
private fun Modifier.eventSelectGesture(sel: SelState, ids: () -> List<String>): Modifier =
    pointerInput(sel) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val slop = viewConfiguration.touchSlop
            val early = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                var stop = false
                while (!stop) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    val ch = ev.changes.firstOrNull { it.id == down.id }
                    if (ch == null || !ch.pressed || (ch.position - down.position).getDistance() > slop) stop = true
                }
                true
            }
            if (early != null) return@awaitEachGesture
            if (down.position.y < sel.topBar) return@awaitEachGesture
            val all = ids()
            val key = sel.hit(sel.containerTop + down.position.y) ?: return@awaitEachGesture
            val idx = all.indexOf(key)
            if (idx < 0) return@awaitEachGesture
            sel.begin(all, idx)
            down.consume()
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                ch.consume()
                if (!ch.pressed) break
                sel.pointerY = ch.position.y
            }
            sel.end()
        }
    }

@Composable
fun EventsScreen(onOpen: (EventMeta) -> Unit, onMerged: (File, String, String) -> Unit) {
    val c = Ios.c
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val changed by MonitorBus.eventsChanged.collectAsState()
    var events by remember { mutableStateOf<List<EventMeta>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var confirmAll by remember { mutableStateOf(false) }
    val sel = remember { SelState() }
    val listState = rememberLazyListState()
    var merging by remember { mutableStateOf(false) }
    var mergeProgress by remember { mutableFloatStateOf(0f) }
    var mergeInfo by remember { mutableStateOf("") }
    var mergeError by remember { mutableStateOf<String?>(null) }
    var mergeAsk by remember { mutableStateOf<MergeAsk?>(null) }
    val mergeCancel = remember { AtomicBoolean(false) }
    LaunchedEffect(changed) {
        events = withContext(Dispatchers.IO) { Storage.events.list() }
        loaded = true
    }
    val list = events
    val total = list.sumOf { it.sizeBytes }
    val idsState = rememberUpdatedState(list.map { it.id })

    SideEffect {
        sel.haptic = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
        sel.topBar = listState.layoutInfo.beforeContentPadding.toFloat()
    }
    // أحداث حُذفت أثناء التحديد تُزال من المحدد.
    LaunchedEffect(list) {
        if (sel.ids.isNotEmpty()) {
            val alive = list.map { it.id }.toSet()
            val kept = sel.ids.filter { alive.contains(it) }.toSet()
            if (kept.size != sel.ids.size) sel.ids = kept
            if (kept.isEmpty()) sel.mode = false
        }
    }
    BackHandler(enabled = sel.mode && !merging) { sel.clear() }

    // تمرير تلقائي وتحديث المدى أثناء السحب، حتى والإصبع ثابت قرب الحافة.
    LaunchedEffect(sel.dragging) {
        if (!sel.dragging) return@LaunchedEffect
        val zone = with(density) { 88.dp.toPx() }
        val maxStep = with(density) { 22.dp.toPx() }
        while (sel.dragging) {
            val y = sel.pointerY
            val topEdge = listState.layoutInfo.beforeContentPadding + zone * 0.4f
            val bottomEdge = sel.height - zone
            var step = 0f
            if (y < topEdge) {
                step = -maxStep * ((topEdge - y) / zone).coerceIn(0.1f, 1f)
            } else if (y > bottomEdge) {
                step = maxStep * ((y - bottomEdge) / zone).coerceIn(0.1f, 1f)
            }
            if (step != 0f) listState.scrollBy(step)
            val h = sel.hit(sel.containerTop + y)
            if (h != null) {
                val all = idsState.value
                val idx = all.indexOf(h)
                if (idx >= 0) sel.update(all, idx)
            }
            delay(16)
        }
    }

    fun runMerge(group: List<EventMeta>) {
        mergeCancel.set(false)
        mergeProgress = 0f
        mergeInfo = "جارٍ دمج " + group.size + " مقطع دون إعادة ترميز…"
        merging = true
        scope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    EventMerger.merge(ctx, group, mergeCancel) { p -> mergeProgress = p }
                }
                merging = false
                sel.clear()
                val sub = fmtDuration(res.durationMs) + (if (res.note.isNotEmpty()) " · " + res.note else "")
                onMerged(res.file, "أحداث مدموجة (" + res.count + ")", sub)
            } catch (e: CancelledException) {
                merging = false
            } catch (t: Throwable) {
                merging = false
                mergeError = t.message ?: "فشل الدمج"
            }
        }
    }

    fun startMerge() {
        val chosen = list.filter { sel.ids.contains(it.id) }
        if (chosen.size < 2) return
        mergeInfo = "جارٍ فحص توافق المقاطع…"
        mergeProgress = 0f
        mergeCancel.set(false)
        merging = true
        scope.launch {
            val an = try {
                withContext(Dispatchers.IO) { EventMerger.analyze(chosen) }
            } catch (t: Throwable) {
                null
            }
            merging = false
            if (an == null || an.groups.isEmpty()) {
                mergeError = "تعذّرت قراءة المقاطع المحددة، أو أن ملفاتها لم تعد موجودة."
                return@launch
            }
            val best = an.best()
            if (best.size < 2) {
                mergeError = "إعدادات التسجيل (الدقة أو الترميز أو الاتجاه) مختلفة بين كل الأحداث المحددة، فلا يوجد حدثان يمكن دمجهما دون إعادة ترميز."
                return@launch
            }
            if (an.groups.size == 1 && an.missing == 0) runMerge(best) else mergeAsk = MergeAsk(best, chosen.size)
        }
    }

    val gesture = Modifier
        .onGloballyPositioned { sel.containerTop = it.positionInRoot().y }
        .onSizeChanged { sel.height = it.height.toFloat() }
        .eventSelectGesture(sel) { idsState.value }

    LargeTitleScreen(
        title = if (sel.mode) "${sel.ids.size} محدد" else "الأحداث",
        modifier = gesture,
        state = listState,
        leading = {
            if (sel.mode) {
                Txt(
                    "إلغاء", Type.body, c.blue,
                    Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { sel.clear() }
                )
            }
        },
        trailing = {
            if (sel.mode) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt(
                        "الكل", Type.body, c.blue,
                        Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            sel.ids = list.map { it.id }.toSet()
                        }
                    )
                    HSpace(16.dp)
                    val ready = sel.ids.size >= 2
                    Txt(
                        "دمج", Type.headline, if (ready) c.blue else c.tertiary,
                        Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            if (ready) {
                                startMerge()
                            } else {
                                Toast.makeText(ctx, "حدّد حدثين على الأقل", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            } else if (list.isNotEmpty()) {
                Txt(
                    "حذف الكل", Type.body, c.red,
                    Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { confirmAll = true }
                )
            }
        }
    ) {
        if (list.isEmpty()) {
            item {
                val floatT = rememberInfiniteTransition(label = "float")
                val floatY by floatT.animateFloat(
                    initialValue = -5f, targetValue = 5f,
                    animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "fy"
                )
                Column(
                    Modifier.fillMaxWidth().appear(1).padding(top = 80.dp, start = 32.dp, end = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Glyph(Ic.List, c.tertiary, 56.dp, Modifier.graphicsLayer { translationY = floatY.dp.toPx() })
                    VSpace(12.dp)
                    Txt(if (loaded) "لا توجد أحداث بعد" else "جارٍ التحميل…", Type.title2, c.label, align = TextAlign.Center)
                    VSpace(6.dp)
                    Txt(
                        "عند ظهور هدف أمام الكاميرا سيُسجَّل مقطع يبدأ قبل ظهوره بثوانٍ ويظهر هنا.",
                        Type.subhead, c.secondary, align = TextAlign.Center
                    )
                }
            }
        } else {
            item {
                val chosen = if (sel.mode) list.filter { sel.ids.contains(it.id) } else emptyList()
                val line = if (sel.mode) {
                    "${chosen.size} محدد · ${fmtDuration(chosen.sumOf { it.durationMs })}"
                } else {
                    "${list.size} حدث · ${fmtSize(total)}"
                }
                Column(Modifier.padding(horizontal = 32.dp).padding(top = 10.dp)) {
                    Txt(line, Type.footnote, c.secondary)
                    if (!sel.mode) {
                        Txt("اضغط مطوّلاً على حدث ثم اسحب لتحديد عدة أحداث ودمجها في فيديو واحد", Type.footnote, c.tertiary)
                    }
                }
            }
            itemsIndexed(list, key = { _, m -> m.id }) { i, m ->
                val first = i == 0
                val last = i == list.size - 1
                val shape = RoundedCornerShape(
                    topStart = if (first) 12.dp else 0.dp, topEnd = if (first) 12.dp else 0.dp,
                    bottomStart = if (last) 12.dp else 0.dp, bottomEnd = if (last) 12.dp else 0.dp
                )
                DisposableEffect(m.id) { onDispose { sel.rows.remove(m.id) } }
                Column(
                    Modifier.animateItem().fillMaxWidth().padding(horizontal = 16.dp).padding(top = if (first) 8.dp else 0.dp)
                        .clip(shape).background(c.card)
                        .onGloballyPositioned { co ->
                            val top = co.positionInRoot().y
                            sel.rows[m.id] = floatArrayOf(top, top + co.size.height)
                        }
                ) {
                    if (!first) Box(Modifier.padding(start = 112.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
                    IosRowEvent(m, sel.mode, sel.ids.contains(m.id)) {
                        if (sel.mode) sel.toggle(m.id) else onOpen(m)
                    }
                }
            }
        }
    }
    if (confirmAll) {
        IosAlert(
            title = "حذف كل الأحداث؟", message = "سيتم حذف جميع المقاطع نهائياً.", confirmText = "حذف", destructive = true,
            onConfirm = {
                confirmAll = false
                sel.clear()
                Storage.events.deleteAll()
                MonitorBus.eventsChanged.value = System.currentTimeMillis()
            },
            onDismiss = { confirmAll = false }
        )
    }
    IosSheet(visible = merging, onDismiss = { }, title = "دمج الأحداث") {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Txt(mergeInfo, Type.body, c.label)
            VSpace(10.dp)
            ProgressBar(mergeProgress)
            VSpace(14.dp)
            IosButton("إلغاء", { mergeCancel.set(true) }, style = BtnStyle.Destructive)
        }
    }
    val ask = mergeAsk
    if (ask != null) {
        IosAlert(
            title = "دمج المقاطع المتوافقة فقط؟",
            message = "تختلف إعدادات التسجيل (الدقة أو الترميز أو الاتجاه) أو تغيب ملفات بين الأحداث المحددة، والدمج دون إعادة ترميز يتطلب التطابق. سيُدمج " +
                ask.group.size + " من " + ask.selected + " حدثاً.",
            confirmText = "دمج المتوافقة", destructive = false,
            onConfirm = {
                mergeAsk = null
                runMerge(ask.group)
            },
            onDismiss = { mergeAsk = null }
        )
    }
    val err = mergeError
    if (err != null) {
        IosAlert(
            title = "تعذّر الدمج", message = err, confirmText = "حسناً", destructive = false,
            onConfirm = { mergeError = null }, onDismiss = { mergeError = null }
        )
    }
}

@Composable
private fun IosRowEvent(m: EventMeta, selecting: Boolean, selected: Boolean, onClick: () -> Unit) {
    val c = Ios.c
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) c.blue.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selecting) {
            if (selected) {
                Box(Modifier.size(24.dp).clip(CircleShape).background(c.blue), contentAlignment = Alignment.Center) {
                    Glyph(Ic.Check, Color.White, 14.dp)
                }
            } else {
                Box(Modifier.size(24.dp).border(1.5.dp, c.tertiary, CircleShape))
            }
            HSpace(12.dp)
        }
        EventThumb(m, Modifier.size(width = 80.dp, height = 52.dp))
        HSpace(12.dp)
        Column(Modifier.weight(1f)) {
            Txt(m.labels.ifEmpty { "حدث" }, Type.headline, c.label, maxLines = 1)
            Txt(fmtDate(m.startMs), Type.footnote, c.secondary, maxLines = 1)
            Txt("${fmtDuration(m.durationMs)} · ${fmtSize(m.sizeBytes)}", Type.footnote, c.secondary, maxLines = 1)
        }
        HSpace(8.dp)
        if (!selecting) Glyph(Ic.ChevL, c.tertiary, 14.dp)
    }
}

@Composable
private fun EventThumb(m: EventMeta, modifier: Modifier) {
    val c = Ios.c
    val img by produceState<ImageBitmap?>(null, m.id) {
        value = withContext(Dispatchers.IO) {
            try {
                val f = Storage.events.thumbFile(m.id)
                if (!f.exists()) {
                    null
                } else {
                    val o = BitmapFactory.Options()
                    o.inSampleSize = 2
                    BitmapFactory.decodeFile(f.absolutePath, o)?.asImageBitmap()
                }
            } catch (_: Throwable) {
                null
            }
        }
    }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(c.fill)) {
        val i = img
        if (i != null) {
            Image(i, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Glyph(Ic.Camera, c.tertiary, 22.dp, Modifier.align(Alignment.Center))
        }
    }
}

// ───────────── المشغّل ─────────────

/** مشغّل عام: يشغّل أي فيديو (حدث مسجَّل، أو لحظة داخل فيديو محلَّل، أو ملف مستخرج). */
@Composable
fun VideoPlayerScreen(
    uri: Uri,
    title: String,
    subtitle: String,
    startMs: Int,
    shareTarget: File?,
    onDelete: (() -> Unit)?,
    saveTarget: File? = null,
    onClose: () -> Unit
) {
    val c = Ios.c
    val ctx = LocalContext.current
    var saveState by remember { mutableIntStateOf(0) }
    val holder = remember { arrayOfNulls<VideoView>(1) }
    var playing by remember { mutableStateOf(true) }
    var pos by remember { mutableIntStateOf(0) }
    var dur by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(uri) {
        while (true) {
            val v = holder[0]
            if (v != null) {
                try {
                    if (!dragging) pos = v.currentPosition
                    playing = v.isPlaying
                } catch (_: Throwable) {
                }
            }
            delay(250)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { context ->
                val vv = VideoView(context)
                vv.setVideoURI(uri)
                vv.setOnPreparedListener { mp ->
                    dur = mp.duration.coerceAtLeast(1)
                    if (startMs > 0) vv.seekTo(startMs)
                    vv.start()
                }
                vv.setOnCompletionListener { playing = false }
                holder[0] = vv
                vv
            },
            modifier = Modifier.fillMaxSize()
        )
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp).height(48.dp)
                .background(Color.Black.copy(alpha = 0.35f)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(40.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
                contentAlignment = Alignment.Center
            ) { Glyph(Ic.ChevR, Color.White, 22.dp) }
            Column(Modifier.weight(1f)) {
                Txt(title, Type.headline, Color.White, maxLines = 1)
                Txt(subtitle, Type.caption, Color.White.copy(alpha = 0.8f), maxLines = 1)
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f)).navigationBarsPadding().padding(16.dp)
        ) {
            IosSlider(
                value = pos.toFloat(),
                onChange = { v -> dragging = true; pos = v.toInt() },
                range = 0f..dur.toFloat(),
                onFinished = {
                    holder[0]?.seekTo(pos)
                    dragging = false
                }
            )
            Row(Modifier.fillMaxWidth()) {
                Txt(fmtDuration(pos.toLong()), Type.caption, Color.White.copy(alpha = 0.8f), Modifier.weight(1f))
                Txt(fmtDuration(dur.toLong()), Type.caption, Color.White.copy(alpha = 0.8f))
            }
            VSpace(8.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                val src = remember { MutableInteractionSource() }
                Box(
                    Modifier.size(56.dp).pressScale(src, 0.9f).clip(CircleShape).background(Color.White.copy(alpha = 0.2f))
                        .clickable(interactionSource = src, indication = null) {
                            val v = holder[0]
                            if (v != null) {
                                if (v.isPlaying) {
                                    v.pause()
                                    playing = false
                                } else {
                                    v.start()
                                    playing = true
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) { Glyph(if (playing) Ic.Pause else Ic.Play, Color.White, 26.dp) }
            }
            if (shareTarget != null || onDelete != null || saveTarget != null) {
                VSpace(12.dp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (saveTarget != null) {
                        IosButton(
                            if (saveState == 0) "حفظ" else if (saveState == 1) "جارٍ الحفظ…" else "تم الحفظ ✓",
                            {
                                if (saveState == 0) {
                                    saveState = 1
                                    val app = ctx.applicationContext
                                    Thread {
                                        val path = EventMerger.saveToGallery(app, saveTarget)
                                        saveState = if (path != null) 2 else 0
                                        Handler(Looper.getMainLooper()).post {
                                            Toast.makeText(
                                                app,
                                                if (path != null) "حُفظ في المعرض: $path" else "تعذّر الحفظ في المعرض",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }.start()
                                }
                            },
                            Modifier.weight(1f), BtnStyle.Filled, enabled = saveState != 2, icon = Ic.Download
                        )
                    }
                    if (shareTarget != null) {
                        IosButton("مشاركة", { shareFile(ctx, shareTarget, "video/mp4") }, Modifier.weight(1f), BtnStyle.Tinted, icon = Ic.Share)
                    }
                    if (onDelete != null) {
                        IosButton("حذف", { confirmDelete = true }, Modifier.weight(1f), BtnStyle.Destructive, icon = Ic.Trash)
                    }
                }
            }
        }
    }
    if (confirmDelete && onDelete != null) {
        IosAlert(
            title = "حذف هذا الفيديو؟", message = "لا يمكن التراجع عن الحذف.", confirmText = "حذف", destructive = true,
            onConfirm = {
                confirmDelete = false
                holder[0]?.stopPlayback()
                onDelete()
            },
            onDismiss = { confirmDelete = false }
        )
    }
}

@Composable
fun PlayerScreen(meta: EventMeta, onClose: () -> Unit) {
    val file = remember(meta.id) { Storage.events.videoFile(meta.id) }
    VideoPlayerScreen(
        uri = Uri.fromFile(file),
        title = meta.labels.ifEmpty { "حدث" },
        subtitle = fmtDate(meta.startMs),
        startMs = 0,
        shareTarget = file,
        onDelete = {
            Storage.events.delete(meta.id)
            MonitorBus.eventsChanged.value = System.currentTimeMillis()
            onClose()
        },
        onClose = onClose
    )
}
