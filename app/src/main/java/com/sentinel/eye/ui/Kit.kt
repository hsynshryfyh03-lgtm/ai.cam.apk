package com.sentinel.eye.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun Txt(
    text: String,
    style: TextStyle = Type.body,
    color: Color = Ios.c.label,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign = TextAlign.Unspecified
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color, textAlign = align),
        overflow = TextOverflow.Ellipsis,
        maxLines = maxLines
    )
}

@Composable
fun HSpace(w: Dp) { Spacer(Modifier.width(w)) }

@Composable
fun VSpace(h: Dp) { Spacer(Modifier.height(h)) }

// ───────────── حركات مشتركة ─────────────

/** ضغطة ناعمة: يتقلص العنصر قليلاً عند اللمس ثم يرتد بنابض. */
@Composable
fun Modifier.pressScale(src: MutableInteractionSource, pressedScale: Float = 0.96f): Modifier {
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(
        if (pressed) pressedScale else 1f,
        spring(dampingRatio = 0.6f, stiffness = 700f),
        label = "press"
    )
    return this.graphicsLayer {
        scaleX = s
        scaleY = s
    }
}

/** ظهور متدرّج: يصعد العنصر ويتلاشى للداخل بتأخير حسب ترتيبه. */
@Composable
fun Modifier.appear(index: Int): Modifier {
    val a = remember { Animatable(if (index < 0) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (index >= 0) {
            delay((index * 55L).coerceAtMost(330L))
            a.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = 260f))
        }
    }
    val off = with(LocalDensity.current) { 26.dp.toPx() }
    return this.graphicsLayer {
        alpha = a.value.coerceIn(0f, 1f)
        translationY = (1f - a.value) * off
    }
}

// ───────────── القوائم المجمّعة ─────────────

/** بطاقة مستديرة بخلفية ملوّنة (للبطاقات المستقلة خارج القوائم المجمّعة). */
fun Modifier.clip16(bg: Color): Modifier = this.clip(RoundedCornerShape(12.dp)).background(bg)

class RowsBuilder {
    val items = mutableListOf<@Composable () -> Unit>()
    fun row(content: @Composable () -> Unit) { items.add(content) }
}

@Composable
fun IosSection(
    header: String? = null,
    footer: String? = null,
    appear: Int = -1,
    build: RowsBuilder.() -> Unit
) {
    val c = Ios.c
    val b = RowsBuilder()
    b.build()
    Column(Modifier.fillMaxWidth().appear(appear).padding(horizontal = 16.dp).padding(top = 22.dp)) {
        if (header != null) {
            Txt(header, Type.footnote, c.secondary, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp))
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.card)) {
            b.items.forEachIndexed { i, r ->
                if (i > 0) {
                    Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
                }
                r()
            }
        }
        if (footer != null) {
            Txt(footer, Type.footnote, c.secondary, Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
        }
    }
}

@Composable
fun IosRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: Ic? = null,
    iconBg: Color? = null,
    value: String? = null,
    chevron: Boolean = false,
    destructive: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val c = Ios.c
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val bgAlpha by animateFloatAsState(if (pressed) 1f else 0f, tween(if (pressed) 40 else 220), label = "rowhl")
    val clickMod = if (onClick != null) {
        Modifier.clickable(interactionSource = src, indication = null, onClick = onClick)
    } else Modifier
    Row(
        modifier.fillMaxWidth().then(clickMod)
            .drawBehind { drawRect(c.fill, alpha = bgAlpha) }
            .heightIn(min = 44.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)).background(iconBg ?: c.blue),
                contentAlignment = Alignment.Center
            ) { Glyph(icon, Color.White, 18.dp) }
            HSpace(12.dp)
        }
        Column(Modifier.weight(1f)) {
            Txt(title, Type.body, if (destructive) c.red else c.label)
            if (subtitle != null) Txt(subtitle, Type.footnote, c.secondary)
        }
        if (value != null) {
            HSpace(8.dp)
            Txt(value, Type.body, c.secondary, maxLines = 1)
        }
        if (trailing != null) {
            HSpace(8.dp)
            trailing()
        }
        if (chevron) {
            HSpace(8.dp)
            Glyph(Ic.ChevL, c.tertiary, 14.dp)
        }
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null) {
    IosRow(title = title, subtitle = subtitle, trailing = { IosSwitch(checked, onChange) })
}

@Composable
fun SliderRow(title: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val c = Ios.c
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(title, Type.body, modifier = Modifier.weight(1f))
            Txt(valueText, Type.body, c.secondary)
        }
        IosSlider(value, onChange, range)
    }
}

@Composable
fun StepperRow(
    title: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit,
    step: Int = 1, subtitle: String? = null, format: (Int) -> String = { it.toString() }
) {
    IosRow(title = title, subtitle = subtitle, trailing = { IosStepper(value, onChange, min, max, step, format) })
}

@Composable
fun SegmentRow(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Txt(title, Type.body)
        VSpace(8.dp)
        IosSegmented(options, selected, onSelect)
    }
}

// ───────────── عناصر التحكم ─────────────

@Composable
fun IosSwitch(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val c = Ios.c
    val view = LocalView.current
    val t by animateFloatAsState(if (checked) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = 650f), label = "sw")
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val knobW by animateDpAsState(if (pressed) 33.dp else 27.dp, spring(dampingRatio = 0.7f, stiffness = 700f), label = "swknob")
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            Modifier.size(width = 51.dp, height = 31.dp)
                .alpha(if (enabled) 1f else 0.5f)
                .clip(RoundedCornerShape(50))
                .background(lerp(c.fillStrong, c.green, t))
                .clickable(enabled = enabled, interactionSource = src, indication = null) {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onChange(!checked)
                }
                .padding(2.dp)
        ) {
            Box(
                Modifier.offset(x = (20.dp - (knobW - 27.dp)) * t).size(width = knobW, height = 27.dp)
                    .shadow(2.dp, CircleShape).background(Color.White, CircleShape)
            )
        }
    }
}

@Composable
fun IosSlider(
    value: Float,
    onChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    onFinished: () -> Unit = {}
) {
    val c = Ios.c
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        val density = LocalDensity.current
        val thumbPx = with(density) { 28.dp.toPx() }
        var widthPx by remember { mutableFloatStateOf(1f) }
        var active by remember { mutableStateOf(false) }
        val curChange by rememberUpdatedState(onChange)
        val curFinished by rememberUpdatedState(onFinished)
        val start = range.start
        val span = range.endInclusive - range.start
        val frac = if (span <= 0f) 0f else ((value - start) / span).coerceIn(0f, 1f)
        val usable = (widthPx - thumbPx).coerceAtLeast(1f)
        val activeDp = with(density) { (thumbPx / 2f + usable * frac).toDp() }
        val thumbScale by animateFloatAsState(if (active) 1.14f else 1f, spring(dampingRatio = 0.6f, stiffness = 600f), label = "thumb")
        Box(
            modifier.fillMaxWidth().height(36.dp)
                .onSizeChanged { widthPx = it.width.toFloat() }
                .pointerInput(start, span) {
                    detectTapGestures(onTap = { o ->
                        val f = ((o.x - thumbPx / 2f) / (widthPx - thumbPx).coerceAtLeast(1f)).coerceIn(0f, 1f)
                        curChange(start + f * span)
                        curFinished()
                    })
                }
                .pointerInput(start, span) {
                    detectHorizontalDragGestures(
                        onDragStart = { o ->
                            active = true
                            val f = ((o.x - thumbPx / 2f) / (widthPx - thumbPx).coerceAtLeast(1f)).coerceIn(0f, 1f)
                            curChange(start + f * span)
                        },
                        onDragEnd = { active = false; curFinished() },
                        onDragCancel = { active = false; curFinished() }
                    ) { change, _ ->
                        change.consume()
                        val f = ((change.position.x - thumbPx / 2f) / (widthPx - thumbPx).coerceAtLeast(1f)).coerceIn(0f, 1f)
                        curChange(start + f * span)
                    }
                },
            contentAlignment = Alignment.CenterStart
        ) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.fillStrong))
            Box(Modifier.width(activeDp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.blue))
            Box(
                Modifier.offset { IntOffset((usable * frac).roundToInt(), 0) }
                    .graphicsLayer {
                        scaleX = thumbScale
                        scaleY = thumbScale
                    }
                    .size(28.dp).shadow(3.dp, CircleShape).background(Color.White, CircleShape)
            )
        }
    }
}

/** مؤشر مقسّم بقرص منزلق ينتقل بنابض بين الخيارات. */
@Composable
fun IosSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Ios.c
    val view = LocalView.current
    BoxWithConstraints(
        modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(9.dp)).background(c.fill).padding(2.dp)
    ) {
        val n = options.size.coerceAtLeast(1)
        val segW = maxWidth / n
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val vi = if (rtl) n - 1 - selected else selected
        val x by animateDpAsState(segW * vi, spring(dampingRatio = 0.78f, stiffness = 520f), label = "segx")
        Box(
            Modifier.absoluteOffset(x = x).width(segW).fillMaxHeight()
                .shadow(1.dp, RoundedCornerShape(7.dp))
                .clip(RoundedCornerShape(7.dp)).background(c.segSelected)
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                val sel = i == selected
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            if (!sel) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            onSelect(i)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Txt(label, if (sel) Type.footnoteSemi else Type.footnote, c.label, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun IosStepper(
    value: Int, onChange: (Int) -> Unit, min: Int, max: Int,
    step: Int = 1, format: (Int) -> String = { it.toString() }
) {
    val c = Ios.c
    Row(verticalAlignment = Alignment.CenterVertically) {
        Txt(format(value), Type.body, c.secondary, maxLines = 1)
        HSpace(10.dp)
        Row(Modifier.clip(RoundedCornerShape(8.dp)).background(c.fill).height(30.dp), verticalAlignment = Alignment.CenterVertically) {
            StepButton("−") { onChange((value - step).coerceAtLeast(min)) }
            Box(Modifier.width(0.5.dp).height(16.dp).background(c.separator))
            StepButton("+") { onChange((value + step).coerceAtMost(max)) }
        }
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    val view = LocalView.current
    val src = remember { MutableInteractionSource() }
    Box(
        Modifier.width(44.dp).fillMaxHeight().pressScale(src, 0.88f)
            .clickable(interactionSource = src, indication = null) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) { Txt(label, Type.title3, Ios.c.label) }
}

enum class BtnStyle { Filled, Tinted, Plain, Destructive }

@Composable
fun IosButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: BtnStyle = BtnStyle.Filled,
    enabled: Boolean = true,
    icon: Ic? = null
) {
    val c = Ios.c
    val src = remember { MutableInteractionSource() }
    val bg: Color
    val fg: Color
    when (style) {
        BtnStyle.Filled -> { bg = c.blue; fg = Color.White }
        BtnStyle.Tinted -> { bg = c.blue.copy(alpha = 0.15f); fg = c.blue }
        BtnStyle.Plain -> { bg = Color.Transparent; fg = c.blue }
        BtnStyle.Destructive -> { bg = c.red.copy(alpha = 0.15f); fg = c.red }
    }
    val a by animateFloatAsState(if (enabled) 1f else 0.4f, tween(200), label = "btnA")
    Row(
        modifier.fillMaxWidth().heightIn(min = 50.dp)
            .pressScale(src, 0.97f)
            .alpha(a)
            .clip(RoundedCornerShape(14.dp)).background(bg)
            .clickable(enabled = enabled, interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Glyph(icon, fg, 20.dp)
            HSpace(8.dp)
        }
        Txt(text, Type.headline, fg)
    }
}

@Composable
fun SmallButton(text: String, onClick: () -> Unit, color: Color? = null) {
    val c = Ios.c
    val col = color ?: c.blue
    val src = remember { MutableInteractionSource() }
    Box(
        Modifier.pressScale(src, 0.92f).clip(RoundedCornerShape(50)).background(col.copy(alpha = 0.14f))
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) { Txt(text, Type.footnoteSemi, col, maxLines = 1) }
}

@Composable
fun ProgressBar(frac: Float, modifier: Modifier = Modifier) {
    val c = Ios.c
    val f by animateFloatAsState(frac.coerceIn(0f, 1f), tween(300), label = "pb")
    Box(modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.fill)) {
        Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(c.blue))
    }
}

@Composable
fun Pill(text: String, color: Color) {
    val col by animateColorAsState(color, tween(250), label = "pill")
    Box(Modifier.clip(RoundedCornerShape(50)).background(col.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Txt(text, Type.caption, col, maxLines = 1)
    }
}

@Composable
fun Callout(icon: Ic, tint: Color, title: String, body: String, appear: Int = -1, action: (@Composable () -> Unit)? = null) {
    val c = Ios.c
    Column(
        Modifier.fillMaxWidth().appear(appear).padding(horizontal = 16.dp).padding(top = 16.dp)
            .clip(RoundedCornerShape(12.dp)).background(c.card).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(icon, tint, 22.dp)
            HSpace(10.dp)
            Txt(title, Type.headline, c.label)
        }
        VSpace(6.dp)
        Txt(body, Type.subhead, c.secondary)
        if (action != null) {
            VSpace(12.dp)
            action()
        }
    }
}

@Composable
fun IosTextField(
    value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
    secret: Boolean = false, keyboard: KeyboardType = KeyboardType.Uri
) {
    val c = Ios.c
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = Type.body.copy(color = c.label),
            cursorBrush = SolidColor(c.blue),
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.fill).padding(horizontal = 12.dp, vertical = 11.dp)) {
                    if (value.isEmpty()) Txt(placeholder, Type.body, c.tertiary)
                    inner()
                }
            }
        )
    }
}

/**
 * حقل نص تكون حالته المحلية هي المرجع، فلا تضيع محارف عند الكتابة السريعة (مشكلة BasicTextField
 * عندما تأتي القيمة من تدفق يتأخر إطاراً واحداً). غيّر [resetKey] لإعادة تعبئته من [initial] برمجياً.
 */
@Composable
fun IosInputField(
    initial: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
    secret: Boolean = false, keyboard: KeyboardType = KeyboardType.Uri, resetKey: Any? = null
) {
    val c = Ios.c
    var tfv by remember(resetKey) { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BasicTextField(
            value = tfv,
            onValueChange = { nv ->
                val changed = nv.text != tfv.text
                tfv = nv
                if (changed) onChange(nv.text)
            },
            singleLine = true,
            textStyle = Type.body.copy(color = c.label),
            cursorBrush = SolidColor(c.blue),
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.fill).padding(horizontal = 12.dp, vertical = 11.dp)) {
                    if (tfv.text.isEmpty()) Txt(placeholder, Type.body, c.tertiary)
                    inner()
                }
            }
        )
    }
}

// ───────────── الهيكل ─────────────

@Composable
fun LargeTitleScreen(
    title: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    bottomInset: Dp = 0.dp,
    state: LazyListState? = null,
    content: LazyListScope.() -> Unit
) {
    val c = Ios.c
    val listState = state ?: rememberLazyListState()
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val progress by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / 56f).coerceIn(0f, 1f)
        }
    }
    Box(modifier.fillMaxSize().background(c.grouped)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = topInset + 44.dp, bottom = bottomInset + 32.dp)
        ) {
            item {
                Txt(
                    title, Type.largeTitle, c.label,
                    Modifier.fillMaxWidth().appear(0).padding(horizontal = 20.dp).padding(top = 2.dp, bottom = 2.dp)
                )
            }
            content()
        }
        Box(Modifier.fillMaxWidth().drawBehind { drawRect(c.bar, alpha = progress) }) {
            Column {
                Spacer(Modifier.height(topInset))
                Box(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp)) {
                    if (leading != null) Box(Modifier.align(Alignment.CenterStart)) { leading() }
                    Txt(
                        title, Type.headline, c.label,
                        Modifier.align(Alignment.Center).graphicsLayer {
                            alpha = progress
                            translationY = (1f - progress) * 8.dp.toPx()
                        },
                        maxLines = 1
                    )
                    if (trailing != null) Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
                }
            }
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(0.5.dp)
                    .graphicsLayer { alpha = progress }.background(c.separator)
            )
        }
    }
}

class TabItem(val title: String, val icon: Ic)

@Composable
fun IosTabBar(items: List<TabItem>, selected: Int, onSelect: (Int) -> Unit) {
    val c = Ios.c
    val view = LocalView.current
    Column(Modifier.fillMaxWidth().background(c.bar)) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
        Row(Modifier.fillMaxWidth().height(52.dp)) {
            items.forEachIndexed { i, t ->
                val sel = i == selected
                val tint by animateColorAsState(if (sel) c.blue else c.gray, tween(200), label = "tabc")
                val sc by animateFloatAsState(if (sel) 1.14f else 1f, spring(dampingRatio = 0.42f, stiffness = 520f), label = "tabs")
                val lift by animateFloatAsState(if (sel) -1.5f else 0f, spring(dampingRatio = 0.55f, stiffness = 500f), label = "tabl")
                val src = remember { MutableInteractionSource() }
                Column(
                    Modifier.weight(1f).fillMaxHeight().pressScale(src, 0.9f)
                        .clickable(interactionSource = src, indication = null) {
                            if (!sel) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            onSelect(i)
                        },
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Glyph(
                        t.icon, tint, 24.dp,
                        Modifier.graphicsLayer {
                            scaleX = sc
                            scaleY = sc
                            translationY = lift.dp.toPx()
                        }
                    )
                    VSpace(2.dp)
                    Txt(t.title, Type.caption, tint, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

/** ورقة سفلية بنابض مع سحب للإغلاق. */
@Composable
fun IosSheet(visible: Boolean, onDismiss: () -> Unit, title: String, content: @Composable ColumnScope.() -> Unit) {
    val c = Ios.c
    var dragPx by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(visible) {
        if (!visible) {
            delay(400)
            dragPx = 0f
        }
    }
    val dragState = rememberDraggableState { d -> dragPx = (dragPx + d).coerceAtLeast(0f) }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = visible, enter = fadeIn(tween(240)), exit = fadeOut(tween(240))) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            )
        }
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(animationSpec = spring(dampingRatio = 0.86f, stiffness = 420f), initialOffsetY = { it }),
            exit = slideOutVertically(animationSpec = tween(260), targetOffsetY = { it })
        ) {
            Column(
                Modifier.offset { IntOffset(0, dragPx.roundToInt()) }
                    .fillMaxWidth().heightIn(max = 640.dp)
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                    .background(c.grouped)
                    .imePadding()
                    .navigationBarsPadding()
            ) {
                Column(
                    Modifier.fillMaxWidth().draggable(
                        state = dragState,
                        orientation = Orientation.Vertical,
                        onDragStopped = { v ->
                            if (dragPx > 180f || v > 1400f) {
                                onDismiss()
                            } else {
                                animate(dragPx, 0f, animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f)) { value, _ ->
                                    dragPx = value
                                }
                            }
                        }
                    )
                ) {
                    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.width(36.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(c.tertiary))
                    }
                    Box(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp)) {
                        Txt(title, Type.headline, c.label, Modifier.align(Alignment.Center), maxLines = 1)
                        Box(
                            Modifier.align(Alignment.CenterEnd)
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                        ) { Txt("تم", Type.headline, c.blue) }
                    }
                }
                Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) { content() }
            }
        }
    }
}

@Composable
fun IosAlert(
    title: String,
    message: String?,
    confirmText: String,
    destructive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    cancelText: String = "إلغاء"
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val c = Ios.c
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown = true }
        val sc by animateFloatAsState(if (shown) 1f else 0.84f, spring(dampingRatio = 0.68f, stiffness = 520f), label = "alertS")
        val al by animateFloatAsState(if (shown) 1f else 0f, tween(160), label = "alertA")
        Column(
            Modifier.width(280.dp)
                .graphicsLayer {
                    scaleX = sc
                    scaleY = sc
                    alpha = al
                }
                .clip(RoundedCornerShape(14.dp)).background(c.card)
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Txt(title, Type.headline, c.label, align = TextAlign.Center)
                if (message != null) {
                    VSpace(4.dp)
                    Txt(message, Type.footnote, c.label, align = TextAlign.Center)
                }
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
            Row(Modifier.fillMaxWidth().height(46.dp)) {
                AlertButton(cancelText, c.blue, FontWeight.Normal, Modifier.weight(1f), onDismiss)
                Box(Modifier.width(0.5.dp).fillMaxHeight().background(c.separator))
                AlertButton(confirmText, if (destructive) c.red else c.blue, FontWeight.SemiBold, Modifier.weight(1f), onConfirm)
            }
        }
    }
}

@Composable
private fun AlertButton(text: String, color: Color, weight: FontWeight, modifier: Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val bgA by animateFloatAsState(if (pressed) 1f else 0f, tween(80), label = "alertbtn")
    val fillC = Ios.c.fill
    Box(
        modifier.fillMaxHeight()
            .drawBehind { drawRect(fillC, alpha = bgA) }
            .clickable(interactionSource = src, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Txt(text, Type.body.copy(fontWeight = weight), color, maxLines = 1) }
}
