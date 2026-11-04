package com.sentinel.eye.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.sentinel.eye.R

/** ألوان النظام في iOS (فاتح/داكن). */
@Immutable
class IosColors(
    val isDark: Boolean,
    val bg: Color,
    val grouped: Color,
    val card: Color,
    val label: Color,
    val secondary: Color,
    val tertiary: Color,
    val separator: Color,
    val fill: Color,
    val fillStrong: Color,
    val segSelected: Color,
    val bar: Color,
    val blue: Color,
    val green: Color,
    val red: Color,
    val orange: Color,
    val gray: Color
)

val LightColors = IosColors(
    isDark = false,
    bg = Color(0xFFFFFFFF),
    grouped = Color(0xFFF2F2F7),
    card = Color(0xFFFFFFFF),
    label = Color(0xFF000000),
    secondary = Color(0x993C3C43),
    tertiary = Color(0x4D3C3C43),
    separator = Color(0x4A3C3C43),
    fill = Color(0x1F767680),
    fillStrong = Color(0xFFE9E9EA),
    segSelected = Color(0xFFFFFFFF),
    bar = Color(0xF0F9F9F9),
    blue = Color(0xFF007AFF),
    green = Color(0xFF34C759),
    red = Color(0xFFFF3B30),
    orange = Color(0xFFFF9500),
    gray = Color(0xFF8E8E93)
)

val DarkColors = IosColors(
    isDark = true,
    bg = Color(0xFF000000),
    grouped = Color(0xFF000000),
    card = Color(0xFF1C1C1E),
    label = Color(0xFFFFFFFF),
    secondary = Color(0x99EBEBF5),
    tertiary = Color(0x4DEBEBF5),
    separator = Color(0xA6545458),
    fill = Color(0x5C767680),
    fillStrong = Color(0xFF39393D),
    segSelected = Color(0xFF636366),
    bar = Color(0xF01C1C1E),
    blue = Color(0xFF0A84FF),
    green = Color(0xFF30D158),
    red = Color(0xFFFF453A),
    orange = Color(0xFFFF9F0A),
    gray = Color(0xFF8E8E93)
)

val LocalIos = staticCompositionLocalOf { LightColors }

object Ios {
    val c: IosColors
        @Composable
        @ReadOnlyComposable
        get() = LocalIos.current
}

val Plex = FontFamily(
    Font(R.font.plex_regular, FontWeight.Normal),
    Font(R.font.plex_medium, FontWeight.Medium),
    Font(R.font.plex_semibold, FontWeight.SemiBold),
    Font(R.font.plex_bold, FontWeight.Bold)
)

/** أنماط النص بمقاسات iOS. */
object Type {
    val largeTitle = TextStyle(fontFamily = Plex, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 42.sp)
    val title2 = TextStyle(fontFamily = Plex, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp)
    val title3 = TextStyle(fontFamily = Plex, fontWeight = FontWeight.Normal, fontSize = 20.sp, lineHeight = 26.sp)
    val headline = TextStyle(fontFamily = Plex, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp)
    val body = TextStyle(fontFamily = Plex, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 24.sp)
    val callout = TextStyle(fontFamily = Plex, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp)
    val subhead = TextStyle(fontFamily = Plex, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp)
    val footnote = TextStyle(fontFamily = Plex, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)
    val footnoteSemi = TextStyle(fontFamily = Plex, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp)
    val caption = TextStyle(fontFamily = Plex, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp)
}

@Composable
fun SentinelTheme(appearance: Int, content: @Composable () -> Unit) {
    val dark = when (appearance) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    CompositionLocalProvider(
        LocalIos provides (if (dark) DarkColors else LightColors),
        LocalLayoutDirection provides LayoutDirection.Rtl
    ) {
        content()
    }
}
