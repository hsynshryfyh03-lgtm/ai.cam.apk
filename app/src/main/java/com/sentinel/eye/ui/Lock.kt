package com.sentinel.eye.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import com.sentinel.eye.core.AppLock
import com.sentinel.eye.core.BiometricHelper
import com.sentinel.eye.core.ConfigStore

enum class PinFlow { Enable, Disable, Change }

/** لوحة إدخال رمز بنمط iOS: نقاط تمتلئ، اهتزاز عند الخطأ، ولوحة أرقام دائرية. */
@Composable
fun PinPad(
    title: String,
    message: String?,
    errorTick: Int,
    resetKey: Int,
    onComplete: (String) -> Unit,
    bottomStart: (@Composable () -> Unit)? = null
) {
    val c = Ios.c
    val view = LocalView.current
    val length = AppLock.PIN_LENGTH
    var entered by remember(resetKey) { mutableStateOf("") }
    val shake = remember { Animatable(0f) }
    LaunchedEffect(errorTick) {
        if (errorTick > 0) {
            for (i in 0 until 5) shake.animateTo(if (i % 2 == 0) 14f else -14f, tween(55))
            shake.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 600f))
        }
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Txt(title, Type.title2, c.label, align = TextAlign.Center)
            VSpace(6.dp)
            Txt(message ?: " ", Type.subhead, if (message != null) c.red else c.secondary, align = TextAlign.Center)
            VSpace(22.dp)
            Row(
                Modifier.graphicsLayer { translationX = shake.value.dp.toPx() },
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                for (i in 0 until length) {
                    val filled = i < entered.length
                    val sc by animateFloatAsState(if (filled) 1f else 0.0f, spring(dampingRatio = 0.5f, stiffness = 600f), label = "dot")
                    Box(
                        Modifier.size(14.dp).clip(CircleShape).border(1.5.dp, c.label.copy(alpha = 0.55f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier.size(14.dp).graphicsLayer {
                                scaleX = sc
                                scaleY = sc
                            }.clip(CircleShape).background(c.label)
                        )
                    }
                }
            }
            VSpace(30.dp)
            val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))
            for (r in rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    for (d in r) PinKey(d) {
                        if (entered.length < length) {
                            entered += d
                            if (entered.length == length) onComplete(entered)
                        }
                    }
                }
                VSpace(14.dp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) { if (bottomStart != null) bottomStart() }
                PinKey("0") {
                    if (entered.length < length) {
                        entered += "0"
                        if (entered.length == length) onComplete(entered)
                    }
                }
                val src = remember { MutableInteractionSource() }
                Box(
                    Modifier.size(76.dp).pressScale(src, 0.88f).clip(CircleShape)
                        .clickable(interactionSource = src, indication = null) {
                            if (entered.isNotEmpty()) {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                entered = entered.dropLast(1)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) { Glyph(Ic.Backspace, c.label, 28.dp) }
            }
        }
    }
}

@Composable
private fun PinKey(label: String, onClick: () -> Unit) {
    val c = Ios.c
    val view = LocalView.current
    val src = remember { MutableInteractionSource() }
    Box(
        Modifier.size(76.dp).pressScale(src, 0.88f).clip(CircleShape).background(c.fillStrong)
            .clickable(interactionSource = src, indication = null) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) { Txt(label, Type.largeTitle, c.label) }
}

/** شاشة القفل: تغطي التطبيق كله حتى إدخال الرمز الصحيح أو البصمة. */
@Composable
fun LockScreen(onBiometric: () -> Unit) {
    val c = Ios.c
    val ctx = LocalContext.current
    val cfg by ConfigStore.flow.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    var reset by remember { mutableIntStateOf(0) }
    var msg by remember { mutableStateOf<String?>(null) }
    val bioOk = cfg.lockBio && BiometricHelper.available(ctx)
    LaunchedEffect(Unit) { if (bioOk) onBiometric() }
    Box(Modifier.fillMaxSize().background(c.grouped).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(top = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Glyph(Ic.Shield, c.blue, 44.dp)
            VSpace(14.dp)
            PinPad(
                title = "أدخل رمز المرور",
                message = msg,
                errorTick = tick,
                resetKey = reset,
                onComplete = { pin ->
                    if (AppLock.verify(pin)) {
                        AppLock.unlock()
                    } else {
                        tick++
                        reset++
                        val wait = AppLock.remainingBlockSec()
                        msg = if (wait > 0) "محاولات كثيرة. انتظر $wait ثانية" else "الرمز غير صحيح"
                    }
                },
                bottomStart = if (bioOk) {
                    {
                        val src = remember { MutableInteractionSource() }
                        Box(
                            Modifier.size(76.dp).pressScale(src, 0.88f).clip(CircleShape)
                                .clickable(interactionSource = src, indication = null, onClick = onBiometric),
                            contentAlignment = Alignment.Center
                        ) { Glyph(Ic.Fingerprint, c.blue, 34.dp) }
                    }
                } else null
            )
        }
    }
}

/** ورقة إعداد/إيقاف/تغيير الرمز. */
@Composable
fun PinFlowSheet(flow: PinFlow?, onClose: () -> Unit) {
    val last = remember { arrayOf(PinFlow.Enable) }
    if (flow != null) last[0] = flow
    val f = last[0]
    IosSheet(
        visible = flow != null, onDismiss = onClose,
        title = when (f) {
            PinFlow.Enable -> "تفعيل قفل التطبيق"
            PinFlow.Disable -> "إيقاف قفل التطبيق"
            PinFlow.Change -> "تغيير الرمز"
        }
    ) {
        key(f, flow != null) { PinFlowContent(f, onClose) }
    }
}

@Composable
private fun PinFlowContent(flow: PinFlow, onClose: () -> Unit) {
    // 0 = تحقق من الرمز الحالي، 1 = رمز جديد، 2 = تأكيد الرمز الجديد
    var step by remember { mutableIntStateOf(if (flow == PinFlow.Enable) 1 else 0) }
    var first by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    var reset by remember { mutableIntStateOf(0) }
    var msg by remember { mutableStateOf<String?>(null) }
    val title = when (step) {
        0 -> "أدخل الرمز الحالي"
        1 -> "أدخل رمزاً جديداً من 6 أرقام"
        else -> "أكّد الرمز"
    }
    PinPad(
        title = title, message = msg, errorTick = tick, resetKey = reset,
        onComplete = { pin ->
            when (step) {
                0 -> if (AppLock.verify(pin)) {
                    if (flow == PinFlow.Disable) {
                        AppLock.disable()
                        onClose()
                    } else {
                        step = 1
                        reset++
                        msg = null
                    }
                } else {
                    tick++
                    reset++
                    val wait = AppLock.remainingBlockSec()
                    msg = if (wait > 0) "محاولات كثيرة. انتظر $wait ثانية" else "الرمز غير صحيح"
                }
                1 -> {
                    first = pin
                    step = 2
                    reset++
                    msg = null
                }
                else -> if (pin == first) {
                    AppLock.setPin(pin)
                    onClose()
                } else {
                    tick++
                    reset++
                    step = 1
                    msg = "الرمزان غير متطابقين، أعد المحاولة"
                }
            }
        }
    )
}
