package com.nodenote.export

import kotlin.math.abs
import kotlin.math.roundToLong

/** A vector drawing in scene units (1 unit = 1 px in SVG = 1 pt in PDF). Origin top-left, y grows down. */
class Scene(
    val width: Float,
    val height: Float,
    val items: List<SceneItem>,
    /** ARGB (0xAARRGGBB) background, or null for transparent. */
    val background: Long? = null,
    /** Document title (SVG <title>, PDF /Title). */
    val title: String = "",
)

/** Something drawn in a [Scene], painted in list order (later items on top). */
sealed interface SceneItem

/** Axis-aligned rectangle, optionally rounded. Colors are ARGB longs; alpha is honored. */
data class SceneRect(
    val x: Float, val y: Float, val width: Float, val height: Float,
    val radius: Float = 0f,
    val fill: Long? = null,
    val stroke: Long? = null,
    val strokeWidth: Float = 1f,
    /** Dash pattern (on, off, …) or null for solid. */
    val dash: List<Float>? = null,
) : SceneItem

/** Circle around ([cx], [cy]); colors are ARGB longs. */
data class SceneCircle(
    val cx: Float, val cy: Float, val radius: Float,
    val fill: Long? = null,
    val stroke: Long? = null,
    val strokeWidth: Float = 1f,
) : SceneItem

/** Straight line in ARGB [color]; [roundCap] rounds both ends (and every dash). */
data class SceneLine(
    val x1: Float, val y1: Float, val x2: Float, val y2: Float,
    val color: Long,
    val width: Float = 1f,
    val roundCap: Boolean = true,
    val dash: List<Float>? = null,
) : SceneItem

/** Filled closed polygon; [points] = x0, y0, x1, y1, … */
data class ScenePolygon(val points: List<Float>, val fill: Long) : SceneItem

/** Which point of the text [SceneText.x] refers to. */
enum class TextAnchor { Start, Middle, End }

/** One line of text (no wrapping here — callers wrap with [Helvetica.wrap]). [y] is the BASELINE. Font: Helvetica, or Helvetica-Bold when [bold]. */
data class SceneText(
    val x: Float, val y: Float,
    val text: String,
    val size: Float,
    val color: Long,
    val bold: Boolean = false,
    /** Extra space after every character, in scene units (SVG letter-spacing / PDF Tc). */
    val letterSpacing: Float = 0f,
    val anchor: TextAnchor = TextAnchor.Start,
) : SceneItem

/**
 * Embedded raster image, stretched to the given box. [bytes] is the encoded file (PNG, JPEG, GIF,
 * WebP or BMP); SVG embeds it as-is. PDF embeds JPEG as-is, but other formats need the decoded
 * pixels in [argb] ([pixelWidth] × [pixelHeight], 0xAARRGGBB per pixel, row-major) — without them
 * PDF draws a grey placeholder box instead.
 */
class SceneImage(
    val x: Float, val y: Float, val width: Float, val height: Float,
    val bytes: ByteArray,
    val argb: IntArray? = null,
    val pixelWidth: Int = 0,
    val pixelHeight: Int = 0,
) : SceneItem

// ---- Shared by the writers ----

/** Fill and border of the box drawn for an image a writer can't embed. */
internal const val PLACEHOLDER_FILL = 0xFFEEEEEEL
internal const val PLACEHOLDER_STROKE = 0xFFBBBBBBL

/** Alpha (0–255) of an ARGB color; also correct for sign-extended `Color.toArgb().toLong()`. */
internal fun alphaOf(argb: Long): Int = ((argb shr 24) and 0xFF).toInt()

/** Whether paint [argb] shows at all (null and fully transparent don't). */
internal fun paints(argb: Long?): Boolean = argb != null && alphaOf(argb) > 0

/** Whether a stroke shows at all; widths that round to 0 don't (PDF would draw a 0 width as a hairline). */
internal fun strokes(argb: Long?, width: Float): Boolean = paints(argb) && width >= 0.005f

/** A usable scene side: at least 1 unit. */
internal fun sceneSide(v: Float): Float = if (v >= 1f && v.isFinite()) v else 1f

/**
 * [v] rounded to at most [decimals] (0–6) decimals with trailing zeros dropped, and never in
 * exponent notation ("1.0E-4" breaks SVG and PDF). Non-finite values become "0", and so does "-0".
 */
internal fun formatNumber(v: Double, decimals: Int = 2): String {
    if (!v.isFinite()) return "0"
    val places = decimals.coerceIn(0, 6)
    var scale = 1L
    repeat(places) { scale *= 10 }
    val n = (v.coerceIn(-1e12, 1e12) * scale).roundToLong()
    if (n == 0L) return "0"
    val a = abs(n)
    val sb = StringBuilder()
    if (n < 0) sb.append('-')
    sb.append(a / scale)
    var frac = a % scale
    if (frac != 0L) {
        var digits = places
        while (frac % 10 == 0L) { frac /= 10; digits-- }
        sb.append('.').append(frac.toString().padStart(digits, '0'))
    }
    return sb.toString()
}

internal fun formatNumber(v: Float, decimals: Int = 2): String = formatNumber(v.toDouble(), decimals)

/** [dash] if it's drawable (finite, non-negative, not all ~zero), else null for a solid stroke. */
internal fun usableDash(dash: List<Float>?): List<Float>? {
    if (dash.isNullOrEmpty()) return null
    if (dash.any { !it.isFinite() || it < 0f }) return null
    return if (dash.all { it < 0.005f }) null else dash
}

/** Characters XML 1.0 forbids (or are bare controls) — never written; tab, CR and LF are kept. */
internal fun isXmlDropped(c: Char): Boolean =
    (c < ' ' && c != '\t' && c != '\n' && c != '\r') || c in '\u007F'..'\u009F' || c == '\uFFFE' || c == '\uFFFF'
