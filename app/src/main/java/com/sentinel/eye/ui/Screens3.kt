package com.sentinel.eye.ui

import android.net.Uri
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sentinel.eye.analysis.AnState
import com.sentinel.eye.analysis.AnalysisBus
import com.sentinel.eye.analysis.AnalysisService
import com.sentinel.eye.analysis.ExState
import com.sentinel.eye.analysis.Request
import com.sentinel.eye.analysis.Segment
import com.sentinel.eye.core.ConfigStore
import com.sentinel.eye.detect.Coco
import com.sentinel.eye.detect.ModelCatalog
import com.sentinel.eye.detect.ModelManager
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

@Composable
fun AnalyzeScreen(
    onPickVideo: () -> Unit,
    onPlaySource: (Uri, String, Int) -> Unit,
    onPlayFile: (File) -> Unit,
    onGoModels: () -> Unit
) {
    val c = Ios.c
    val ctx = LocalContext.current
    val cfg by ConfigStore.flow.collectAsState()
    val sel by AnalysisBus.selected.collectAsState()
    val name by AnalysisBus.selectedName.collectAsState()
    val info by AnalysisBus.selectedInfo.collectAsState()
    val st by AnalysisBus.state.collectAsState()
    val ex by AnalysisBus.export.collectAsState()
    val modelVer by ModelManager.version.collectAsState()
    val spec = ModelCatalog.byId(cfg.modelId)
    val modelReady = spec != null && modelVer >= 0 && ModelManager.isInstalled(spec)
    val running = st is AnState.Running
    val exporting = ex is ExState.Running
    val nowMs by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            value = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    val uri = sel
    val anyTarget = cfg.anPersons || cfg.anAnimals || cfg.anVehicles

    LargeTitleScreen("تحليل الفيديو") {
        item {
            Callout(
                Ic.Eye, c.blue, "استخرج اللحظات المهمة فقط",
                "في كل ثانية يُحلَّل أول إطار والإطار الأوسط وآخر إطار. إن وُجد هدف في أي منها تُضمَّن الثانية كاملة، ويُتخطّى ما تبقّى منها لتوفير الطاقة.",
                appear = 1
            )
        }
        if (!modelReady) {
            item {
                Callout(
                    Ic.Cpu, c.orange, "لا يوجد نموذج جاهز",
                    "ثبّت نموذجاً واخترْه من تبويب «النماذج» قبل التحليل.", appear = 2
                ) { SmallButton("فتح النماذج", onGoModels) }
            }
        }
        item {
            IosSection("الفيديو", appear = 2) {
                row {
                    IosRow(
                        title = if (uri == null) "اختيار فيديو" else name.ifEmpty { "فيديو محدد" },
                        subtitle = if (uri == null) "اختر ملفاً من هاتفك" else info.ifEmpty { null },
                        icon = Ic.Play, chevron = true,
                        onClick = { if (!running && !exporting) onPickVideo() }
                    )
                }
            }
        }
        item {
            IosSection("الأهداف", footer = "تُضمَّن الثانية إن ظهر أي من الأهداف المختارة.", appear = 3) {
                row { SwitchRow("أشخاص", cfg.anPersons, { v -> ConfigStore.update { it.copy(anPersons = v) } }) }
                row { SwitchRow("حيوانات", cfg.anAnimals, { v -> ConfigStore.update { it.copy(anAnimals = v) } }) }
                row { SwitchRow("مركبات", cfg.anVehicles, { v -> ConfigStore.update { it.copy(anVehicles = v) } }) }
            }
        }
        item {
            IosSection(
                "الدقة والتوفير",
                footer = "«3 إطارات» أدق، و«إطار واحد» أسرع وأوفر للطاقة. الهامش يضيف ثواني قبل اللحظة وبعدها، والدمج يوصل المقاطع المتقاربة.",
                appear = 4
            ) {
                row {
                    SegmentRow("إطارات كل ثانية", listOf("3 إطارات · أدق", "إطار واحد · أسرع"), cfg.anSampling) { i ->
                        ConfigStore.update { it.copy(anSampling = i) }
                    }
                }
                row {
                    SliderRow("حد الثقة", "${(cfg.anConfidence * 100).toInt()}%", cfg.anConfidence, 0.2f..0.9f, { v ->
                        ConfigStore.update { it.copy(anConfidence = v) }
                    })
                }
                row {
                    StepperRow("هامش حول اللحظة", cfg.anPadSec, 0, 10, { v -> ConfigStore.update { it.copy(anPadSec = v) } }, 1, null) { "$it ث" }
                }
                row {
                    StepperRow("دمج الفجوات حتى", cfg.anGapSec, 0, 10, { v -> ConfigStore.update { it.copy(anGapSec = v) } }, 1, null) { "$it ث" }
                }
            }
        }
        item {
            Box(Modifier.fillMaxWidth().appear(5).padding(horizontal = 16.dp).padding(top = 20.dp)) {
                if (running) {
                    IosButton("إلغاء التحليل", { AnalysisBus.cancel.set(true) }, style = BtnStyle.Destructive, icon = Ic.Close)
                } else {
                    IosButton(
                        "ابدأ التحليل",
                        {
                            val u = AnalysisBus.selected.value
                            if (u != null) {
                                AnalysisBus.state.value = AnState.Running(0, 0, 0, 0, SystemClock.elapsedRealtime())
                                AnalysisBus.export.value = ExState.Idle
                                AnalysisBus.pending = Request.Analyze(u, ConfigStore.current)
                                AnalysisService.start(ctx)
                            }
                        },
                        enabled = uri != null && modelReady && anyTarget && !exporting,
                        icon = Ic.Bolt
                    )
                }
            }
        }

        val s = st
        if (s is AnState.Running) {
            item {
                Column(
                    Modifier.fillMaxWidth().appear(0).padding(horizontal = 16.dp).padding(top = 16.dp)
                        .clip16(c.card).padding(16.dp)
                ) {
                    val frac = if (s.totalSec > 0) s.doneSec.toFloat() / s.totalSec else 0f
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Txt("جارٍ التحليل…", Type.headline, c.label, Modifier.weight(1f))
                        Txt((frac * 100).toInt().toString() + "%", Type.headline, c.blue)
                    }
                    VSpace(10.dp)
                    ProgressBar(frac)
                    VSpace(10.dp)
                    Txt(
                        "الثانية ${s.doneSec} من ${s.totalSec} · ${s.hits} ثانية فيها أهداف · ${s.frames} إطاراً مُحلَّلاً",
                        Type.footnote, c.secondary
                    )
                    if (s.doneSec > 3 && s.totalSec > s.doneSec) {
                        val el = (nowMs - s.startedAtMs).coerceAtLeast(1L)
                        val eta = el * (s.totalSec - s.doneSec) / s.doneSec
                        Txt("الوقت المتبقي تقريباً: " + fmtDuration(eta), Type.footnote, c.secondary)
                    }
                }
            }
        }
        if (s is AnState.Failed) {
            item { Callout(Ic.Warning, c.red, "تعذّر التحليل", s.message, appear = 0) }
        }
        if (s is AnState.Done) {
            item {
                val kept = s.segments.sumOf { it.length }
                val pct = if (s.durationSec > 0) kept * 100 / s.durationSec else 0
                val labels = s.labels.map { Coco.labelAr(it) }.distinct().joinToString("، ")
                Callout(
                    Ic.Check, c.green,
                    if (s.segments.isEmpty()) "لم تُوجد أهداف" else "وُجدت ${s.segments.size} لحظة",
                    if (s.segments.isEmpty()) "لا يحتوي الفيديو على الأهداف المختارة."
                    else "يمثل المستخرج $kept ثانية من أصل ${s.durationSec} (" + pct + "%)" +
                        (if (labels.isNotEmpty()) " · $labels" else "") +
                        "\nاستغرق التحليل " + fmtDuration(s.elapsedMs) + " · " + s.frames + " إطاراً" +
                        (if (s.fallback) "\n(استُخدم المسار البديل الأبطأ)" else ""),
                    appear = 0
                )
            }
            if (s.segments.isNotEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 14.dp)) {
                        IosButton(
                            if (exporting) "جارٍ الاستخراج…" else "استخراج المقاطع في ملف واحد",
                            {
                                val u = AnalysisBus.selected.value ?: s.uri
                                AnalysisBus.export.value = ExState.Running(0f)
                                AnalysisBus.pending = Request.Export(u, s.segments)
                                AnalysisService.start(ctx)
                            },
                            enabled = !exporting, icon = Ic.Download, style = BtnStyle.Tinted
                        )
                    }
                }
                item {
                    val e = ex
                    if (e is ExState.Running) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp)) {
                            ProgressBar(e.frac)
                        }
                    }
                    if (e is ExState.Failed) {
                        Callout(Ic.Warning, c.red, "تعذّر الاستخراج", e.message, appear = 0)
                    }
                    if (e is ExState.Done) {
                        IosSection("الملف المستخرج", footer = "يحوي ${e.keptSec} ثانية. القص يبدأ من أقرب إطار مفتاحي، دون إعادة ترميز.", appear = 0) {
                            row {
                                IosRow(
                                    title = "تشغيل المقتطفات", subtitle = fmtSize(e.file.length()),
                                    icon = Ic.Play, chevron = true, onClick = { onPlayFile(e.file) }
                                )
                            }
                            row {
                                IosRow(title = "مشاركة", icon = Ic.Share, iconBg = c.green, onClick = { shareFile(ctx, e.file, "video/mp4") })
                            }
                        }
                    }
                }
                item {
                    IosSection("اللحظات المكتشفة", footer = "اضغط على لحظة لمعاينتها داخل الفيديو الأصلي.", appear = 1) {
                        for (seg in s.segments.take(60)) {
                            row { SegmentRowItem(seg, s.uri, onPlaySource) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentRowItem(seg: Segment, uri: Uri, onPlay: (Uri, String, Int) -> Unit) {
    IosRow(
        title = fmtDuration(seg.startSec * 1000L) + "  ←  " + fmtDuration(seg.endSec * 1000L),
        subtitle = "${seg.length} ثانية",
        icon = Ic.Play,
        chevron = true,
        onClick = { onPlay(uri, "لحظة " + fmtDuration(seg.startSec * 1000L), seg.startSec * 1000) }
    )
}
