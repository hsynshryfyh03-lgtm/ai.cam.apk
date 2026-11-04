package com.sentinel.eye.ui

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.sentinel.eye.core.AppLock
import com.sentinel.eye.core.ConfigStore
import com.sentinel.eye.core.EventMeta
import com.sentinel.eye.detect.ModelSpec
import java.io.File

sealed class Overlay {
    object None : Overlay()
    object Diagnostics : Overlay()
    object Shizuku : Overlay()
    object Telegram : Overlay()
    class Player(val meta: EventMeta) : Overlay()
    class Clip(val uri: Uri, val title: String, val subtitle: String, val startMs: Int, val file: File?) : Overlay()
    class Merged(val file: File, val title: String, val subtitle: String) : Overlay()
}

@Composable
fun SystemBarsEffect(dark: Boolean) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val w = (view.context as Activity).window
            val ctl = WindowCompat.getInsetsController(w, view)
            ctl.isAppearanceLightStatusBars = !dark
            ctl.isAppearanceLightNavigationBars = !dark
        }
    }
}

@Composable
fun SentinelApp(onImportModel: (ModelSpec) -> Unit, onPickVideo: () -> Unit, onBiometric: () -> Unit) {
    val cfg by ConfigStore.flow.collectAsState()
    SentinelTheme(cfg.appearance) {
        val c = Ios.c
        SystemBarsEffect(c.isDark)
        var tab by rememberSaveable { mutableStateOf(0) }
        var overlay by remember { mutableStateOf<Overlay>(Overlay.None) }
        val lastOverlay = remember { arrayOf<Overlay>(Overlay.None) }
        if (overlay !is Overlay.None) lastOverlay[0] = overlay
        val tabs = remember {
            listOf(
                TabItem("المراقبة", Ic.Eye),
                TabItem("الأحداث", Ic.List),
                TabItem("التحليل", Ic.Play),
                TabItem("النماذج", Ic.Cpu),
                TabItem("الإعدادات", Ic.Gear)
            )
        }
        val locked by AppLock.locked.collectAsState()
        BackHandler(enabled = overlay !is Overlay.None) { overlay = Overlay.None }
        BackHandler(enabled = locked) { }
        val ease = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
        Box(Modifier.fillMaxSize().background(c.grouped)) {
            if (!cfg.onboardingDone) {
                OnboardingScreen()
            } else {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        Crossfade(targetState = tab, animationSpec = tween(240), label = "tabs") { t ->
                            when (t) {
                                0 -> LiveScreen(onGoModels = { tab = 3 })
                                1 -> EventsScreen(
                                    onOpen = { m -> overlay = Overlay.Player(m) },
                                    onMerged = { f, t, sub -> overlay = Overlay.Merged(f, t, sub) }
                                )
                                2 -> AnalyzeScreen(
                                    onPickVideo = onPickVideo,
                                    onPlaySource = { u, title, startMs ->
                                        overlay = Overlay.Clip(u, title, "الفيديو الأصلي", startMs, null)
                                    },
                                    onPlayFile = { f ->
                                        overlay = Overlay.Clip(Uri.fromFile(f), "المقتطفات المستخرجة", "ملف واحد", 0, f)
                                    },
                                    onGoModels = { tab = 3 }
                                )
                                3 -> ModelsScreen(onImport = onImportModel)
                                else -> SettingsScreen(
                                    onOpenDiagnostics = { overlay = Overlay.Diagnostics },
                                    onOpenShizuku = { overlay = Overlay.Shizuku },
                                    onOpenTelegram = { overlay = Overlay.Telegram }
                                )
                            }
                        }
                    }
                    IosTabBar(tabs, tab) { i -> tab = i }
                }
                AnimatedVisibility(
                    visible = overlay !is Overlay.None,
                    enter = slideInHorizontally(animationSpec = tween(380, easing = ease), initialOffsetX = { -it }) + fadeIn(tween(220)),
                    exit = slideOutHorizontally(animationSpec = tween(300, easing = ease), targetOffsetX = { -it }) + fadeOut(tween(220))
                ) {
                    when (val o = lastOverlay[0]) {
                        is Overlay.Player -> PlayerScreen(o.meta) { overlay = Overlay.None }
                        is Overlay.Clip -> VideoPlayerScreen(o.uri, o.title, o.subtitle, o.startMs, o.file, null) { overlay = Overlay.None }
                        is Overlay.Merged -> VideoPlayerScreen(Uri.fromFile(o.file), o.title, o.subtitle, 0, o.file, null, o.file) { overlay = Overlay.None }
                        is Overlay.Diagnostics -> DiagnosticsScreen { overlay = Overlay.None }
                        is Overlay.Shizuku -> ShizukuScreen { overlay = Overlay.None }
                        is Overlay.Telegram -> TelegramScreen { overlay = Overlay.None }
                        else -> Box(Modifier.fillMaxSize())
                    }
                }
            }
            AnimatedVisibility(visible = locked, enter = fadeIn(tween(160)), exit = fadeOut(tween(260))) {
                LockScreen(onBiometric)
            }
        }
    }
}
