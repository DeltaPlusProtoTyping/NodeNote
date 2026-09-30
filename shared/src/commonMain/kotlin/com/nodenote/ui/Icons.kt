package com.nodenote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.nodenote.theme.TextSecondary

/**
 * The few UI symbols the app needs, drawn as vectors. The browser build
 * renders text with its own bundled font, which has no geometric-shape or
 * dingbat glyphs (▾ ▸ ✕ ✓ …) — so they are drawn rather than typed.
 */
enum class Glyph { ChevronDown, ChevronRight, ChevronLeft, Close, Check }

@Composable
fun GlyphIcon(glyph: Glyph, color: Color, size: Dp = 10.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val strokeWidth = (w * 0.13f).coerceAtLeast(1.3f)
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun polyline(vararg points: Pair<Float, Float>) = Path().apply {
            points.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * w, y * h) else lineTo(x * w, y * h) }
        }
        when (glyph) {
            Glyph.ChevronDown -> drawPath(polyline(0.22f to 0.38f, 0.5f to 0.64f, 0.78f to 0.38f), color, style = stroke)
            Glyph.ChevronRight -> drawPath(polyline(0.38f to 0.22f, 0.64f to 0.5f, 0.38f to 0.78f), color, style = stroke)
            Glyph.ChevronLeft -> drawPath(polyline(0.62f to 0.22f, 0.36f to 0.5f, 0.62f to 0.78f), color, style = stroke)
            Glyph.Check -> drawPath(polyline(0.2f to 0.52f, 0.42f to 0.74f, 0.82f to 0.28f), color, style = stroke)
            Glyph.Close -> {
                drawLine(color, Offset(0.25f * w, 0.25f * h), Offset(0.75f * w, 0.75f * h), strokeWidth, StrokeCap.Round)
                drawLine(color, Offset(0.75f * w, 0.25f * h), Offset(0.25f * w, 0.75f * h), strokeWidth, StrokeCap.Round)
            }
        }
    }
}

/** Small "x" button for dialog headers and chips. */
@Composable
fun CloseButton(onClick: () -> Unit, color: Color = TextSecondary, size: Dp = 12.dp) {
    Box(
        Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(Glyph.Close, color, size)
    }
}
