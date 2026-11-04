package com.sentinel.eye.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

enum class Ic {
    Eye, List, Cpu, Gear, Play, Pause, Stop, Record, Trash, Download, Check, ChevL, ChevR,
    Share, Warning, Camera, Sliders, Info, Close, Plus, Doc, Bolt, Shield, Bell, Backspace, Fingerprint
}

/** أيقونات خطية بأسلوب SF Symbols مرسومة بالكود (لا حقوق نشر). */
@Composable
fun Glyph(ic: Ic, tint: Color, sizeDp: Dp = 22.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(sizeDp)) {
        val u = this.size.minDimension / 24f
        val sw = 1.8f * u
        val st = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, p(x1, y1), p(x2, y2), strokeWidth = sw, cap = StrokeCap.Round)
        fun poly(close: Boolean, vararg pts: Float): Path {
            val path = Path()
            path.moveTo(pts[0] * u, pts[1] * u)
            var i = 2
            while (i < pts.size) {
                path.lineTo(pts[i] * u, pts[i + 1] * u)
                i += 2
            }
            if (close) path.close()
            return path
        }
        when (ic) {
            Ic.Eye -> {
                val path = Path()
                path.moveTo(2f * u, 12f * u)
                path.cubicTo(6f * u, 6f * u, 18f * u, 6f * u, 22f * u, 12f * u)
                path.cubicTo(18f * u, 18f * u, 6f * u, 18f * u, 2f * u, 12f * u)
                path.close()
                drawPath(path, tint, style = st)
                drawCircle(tint, radius = 3.2f * u, center = p(12f, 12f), style = st)
            }
            Ic.List -> {
                for (i in 0..2) {
                    val y = 6f + 6f * i
                    drawCircle(tint, radius = 1.2f * u, center = p(4.5f, y))
                    line(9f, y, 20f, y)
                }
            }
            Ic.Cpu -> {
                drawRoundRect(tint, topLeft = p(6f, 6f), size = Size(12f * u, 12f * u), cornerRadius = CornerRadius(2.5f * u), style = st)
                drawRoundRect(tint, topLeft = p(9.5f, 9.5f), size = Size(5f * u, 5f * u), cornerRadius = CornerRadius(1f * u), style = st)
                for (t in floatArrayOf(9.5f, 14.5f)) {
                    line(t, 3f, t, 6f); line(t, 18f, t, 21f)
                    line(3f, t, 6f, t); line(18f, t, 21f, t)
                }
            }
            Ic.Gear -> {
                drawCircle(tint, radius = 3f * u, center = p(12f, 12f), style = st)
                drawCircle(tint, radius = 7f * u, center = p(12f, 12f), style = st)
                for (k in 0..7) {
                    val a = k * Math.PI / 4.0
                    val cx = cos(a).toFloat()
                    val cy = sin(a).toFloat()
                    line(12f + cx * 7.8f, 12f + cy * 7.8f, 12f + cx * 10f, 12f + cy * 10f)
                }
            }
            Ic.Play -> drawPath(poly(true, 8f, 5f, 19f, 12f, 8f, 19f), tint)
            Ic.Pause -> {
                drawRoundRect(tint, topLeft = p(6.5f, 5f), size = Size(4f * u, 14f * u), cornerRadius = CornerRadius(1.5f * u))
                drawRoundRect(tint, topLeft = p(13.5f, 5f), size = Size(4f * u, 14f * u), cornerRadius = CornerRadius(1.5f * u))
            }
            Ic.Stop -> drawRoundRect(tint, topLeft = p(6f, 6f), size = Size(12f * u, 12f * u), cornerRadius = CornerRadius(2.5f * u))
            Ic.Record -> drawCircle(tint, radius = 6f * u, center = p(12f, 12f))
            Ic.Trash -> {
                line(4f, 7f, 20f, 7f)
                drawPath(poly(false, 9f, 7f, 9f, 4.5f, 15f, 4.5f, 15f, 7f), tint, style = st)
                drawPath(poly(false, 6f, 7f, 7f, 20f, 17f, 20f, 18f, 7f), tint, style = st)
                line(10f, 11f, 10f, 16f); line(14f, 11f, 14f, 16f)
            }
            Ic.Download -> {
                line(12f, 4f, 12f, 15f)
                drawPath(poly(false, 7.5f, 10.5f, 12f, 15f, 16.5f, 10.5f), tint, style = st)
                line(5f, 20f, 19f, 20f)
            }
            Ic.Check -> drawPath(poly(false, 5f, 12.5f, 10f, 17.5f, 19f, 7f), tint, style = st)
            Ic.ChevL -> drawPath(poly(false, 15f, 5f, 8f, 12f, 15f, 19f), tint, style = st)
            Ic.ChevR -> drawPath(poly(false, 9f, 5f, 16f, 12f, 9f, 19f), tint, style = st)
            Ic.Share -> {
                drawPath(poly(false, 8.5f, 9f, 6f, 9f, 6f, 20f, 18f, 20f, 18f, 9f, 15.5f, 9f), tint, style = st)
                line(12f, 3f, 12f, 14f)
                drawPath(poly(false, 8.5f, 6.5f, 12f, 3f, 15.5f, 6.5f), tint, style = st)
            }
            Ic.Warning -> {
                drawPath(poly(true, 12f, 3.5f, 21.5f, 20f, 2.5f, 20f), tint, style = st)
                line(12f, 9.5f, 12f, 14f)
                drawCircle(tint, radius = 1.1f * u, center = p(12f, 17f))
            }
            Ic.Camera -> {
                drawRoundRect(tint, topLeft = p(3f, 7.5f), size = Size(18f * u, 12f * u), cornerRadius = CornerRadius(3f * u), style = st)
                drawCircle(tint, radius = 3.3f * u, center = p(12f, 13.5f), style = st)
                drawPath(poly(false, 8f, 7.5f, 9.5f, 4.5f, 14.5f, 4.5f, 16f, 7.5f), tint, style = st)
            }
            Ic.Sliders -> {
                line(4f, 6f, 20f, 6f); line(4f, 12f, 20f, 12f); line(4f, 18f, 20f, 18f)
                drawCircle(tint, radius = 2.2f * u, center = p(9f, 6f), style = st)
                drawCircle(tint, radius = 2.2f * u, center = p(15f, 12f), style = st)
                drawCircle(tint, radius = 2.2f * u, center = p(8f, 18f), style = st)
            }
            Ic.Info -> {
                drawCircle(tint, radius = 9f * u, center = p(12f, 12f), style = st)
                line(12f, 11f, 12f, 16.5f)
                drawCircle(tint, radius = 1.1f * u, center = p(12f, 7.8f))
            }
            Ic.Close -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            Ic.Plus -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
            Ic.Doc -> {
                drawRoundRect(tint, topLeft = p(5f, 3f), size = Size(14f * u, 18f * u), cornerRadius = CornerRadius(2.5f * u), style = st)
                line(8.5f, 9f, 15.5f, 9f); line(8.5f, 13f, 15.5f, 13f); line(8.5f, 17f, 12.5f, 17f)
            }
            Ic.Bolt -> drawPath(poly(true, 13f, 3f, 6f, 13.5f, 11.5f, 13.5f, 10.5f, 21f, 18f, 10f, 12.5f, 10f), tint, style = st)
            Ic.Shield -> drawPath(poly(true, 12f, 3f, 19.5f, 6f, 19.5f, 12.5f, 12f, 21f, 4.5f, 12.5f, 4.5f, 6f), tint, style = st)
            Ic.Backspace -> {
                drawPath(poly(true, 9f, 5f, 21f, 5f, 21f, 19f, 9f, 19f, 3f, 12f), tint, style = st)
                line(12.5f, 9.5f, 17f, 14.5f); line(17f, 9.5f, 12.5f, 14.5f)
            }
            Ic.Fingerprint -> {
                val cx = 12f
                val cy = 13f
                for ((i, r) in floatArrayOf(2.5f, 5.5f, 8.5f).withIndex()) {
                    drawArc(
                        tint, startAngle = 200f - i * 8f, sweepAngle = 220f + i * 10f, useCenter = false,
                        topLeft = p(cx - r, cy - r), size = Size(2f * r * u, 2f * r * u), style = st
                    )
                }
                line(12f, 11f, 12f, 16f)
            }
            Ic.Bell -> {
                val path = Path()
                path.moveTo(6f * u, 17f * u)
                path.cubicTo(7.5f * u, 15.5f * u, 7f * u, 13f * u, 7f * u, 10.5f * u)
                path.cubicTo(7f * u, 7.5f * u, 9f * u, 5.5f * u, 12f * u, 5.5f * u)
                path.cubicTo(15f * u, 5.5f * u, 17f * u, 7.5f * u, 17f * u, 10.5f * u)
                path.cubicTo(17f * u, 13f * u, 16.5f * u, 15.5f * u, 18f * u, 17f * u)
                path.close()
                drawPath(path, tint, style = st)
                line(10f, 20f, 14f, 20f)
                line(12f, 3.5f, 12f, 5.5f)
            }
        }
    }
}
