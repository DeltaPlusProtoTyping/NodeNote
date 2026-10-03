package com.nodenote.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.nodenote.export.Helvetica
import com.nodenote.export.MapRect
import com.nodenote.export.MapScene
import com.nodenote.export.MapSceneBuilder
import com.nodenote.export.MapSceneOptions
import com.nodenote.export.MapStyle
import com.nodenote.export.PdfWriter
import com.nodenote.export.Scene
import com.nodenote.export.SceneCircle
import com.nodenote.export.SceneImage
import com.nodenote.export.SceneLine
import com.nodenote.export.ScenePolygon
import com.nodenote.export.SceneRect
import com.nodenote.export.SceneText
import com.nodenote.export.SvgWriter
import com.nodenote.export.TextAnchor
import com.nodenote.model.ElementKind
import com.nodenote.model.ExportFormat
import com.nodenote.model.ExportRegion
import com.nodenote.model.ExportSettings
import com.nodenote.model.ExportTheme
import com.nodenote.model.nowMillis
import com.nodenote.state.AppState
import com.nodenote.storage.decodeImageOrNull
import com.nodenote.storage.encodeToPng
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A rendered export (the dialog's preview, or a PNG). The overlay rects are in
 * image pixels so the dialog can hit-test and drag the legend / title block.
 */
class ExportRender(
    val bitmap: ImageBitmap,
    val legendRect: Rect?,
    val titleBlockRect: Rect?,
    val overlayMargin: Float,
)

/**
 * Exports a board as PDF, SVG or PNG. All three come from the same vector scene
 * ([MapSceneBuilder]): PDF and SVG write it out as vectors (sharp at any zoom),
 * PNG and the dialog preview rasterize it with Compose.
 */
object MapExporter {

    /** PNGs are capped so huge boards at 3× don't exhaust memory (vectors have no such limit). */
    private const val MAX_PIXELS = 8000

    /** The vector map for [settings], or null when that region has nothing to show. */
    fun scene(state: AppState, settings: ExportSettings, withPixels: Boolean = false): MapScene? {
        val region = when (settings.region) {
            ExportRegion.CurrentView -> {
                if (state.canvasSize.width <= 0 || state.canvasSize.height <= 0) return null
                val a = state.screenToWorld(Offset.Zero)
                val b = state.screenToWorld(Offset(state.canvasSize.width.toFloat(), state.canvasSize.height.toFloat()))
                MapRect(a.x, a.y, b.x - a.x, b.y - a.y)
            }
            else -> null
        }
        val only = if (settings.region == ExportRegion.SelectedNodes) state.selectedNodeIds else null
        val hidden = if (settings.includePrivate) emptySet() else state.privateIds
        val count = state.project.nodes.count { it.id !in hidden && (only == null || it.id in only) }
        val options = MapSceneOptions(
            style = if (settings.theme == ExportTheme.Light) MapStyle.Light else MapStyle.Dark,
            transparent = settings.transparentBackground,
            region = region,
            padding = settings.padding,
            includePrivate = settings.includePrivate,
            onlyNodeIds = only,
            showEdgeLabels = settings.showEdgeLabels,
            showDescriptions = settings.showDescriptions,
            overlayScale = if (region != null) 1f / state.zoom else 1f,
            showLegend = settings.showLegend,
            legendTitle = settings.legendTitle,
            legendX = settings.legendX,
            legendY = settings.legendY,
            legendScale = settings.legendScale,
            showTitleBlock = settings.showTitleBlock,
            titleBlockX = settings.titleBlockX,
            titleBlockY = settings.titleBlockY,
            subtitle = "$count node${if (count == 1) "" else "s"} · ${formatDate(nowMillis())}",
            colorBy = state.colorBy,
        )
        return MapSceneBuilder.build(state.project, options, if (withPixels) ::pixelsOf else { _ -> null })
    }

    /** Rasterizes the map: [pixelsPerUnit] defaults to the PNG resolution (scale × screen density). */
    fun render(state: AppState, settings: ExportSettings, textMeasurer: TextMeasurer, pixelsPerUnit: Float? = null): ExportRender? {
        val map = scene(state, settings) ?: return null
        val scene = map.scene
        var k = pixelsPerUnit ?: (settings.scale * state.viewDensity.coerceAtLeast(1f))
        k *= min(1f, min(MAX_PIXELS / (scene.width * k), MAX_PIXELS / (scene.height * k)))
        val w = (scene.width * k).roundToInt().coerceAtLeast(1)
        val h = (scene.height * k).roundToInt().coerceAtLeast(1)
        val bitmap = ImageBitmap(w, h)
        CanvasDrawScope().draw(Density(state.viewDensity), LayoutDirection.Ltr, Canvas(bitmap), Size(w.toFloat(), h.toFloat())) {
            drawScene(scene, k, textMeasurer)
        }
        fun MapRect.px() = Rect(x * k, y * k, right * k, bottom * k)
        return ExportRender(bitmap, map.legendRect?.px(), map.titleRect?.px(), map.overlayMargin * k)
    }

    /** The file for [settings]' format, or null when there's nothing to export. */
    fun fileBytes(state: AppState, settings: ExportSettings, textMeasurer: TextMeasurer): ByteArray? = when (settings.format) {
        ExportFormat.Png -> render(state, settings, textMeasurer)?.let { encodeToPng(it.bitmap) }
        ExportFormat.Svg -> scene(state, settings)?.let { SvgWriter.write(it.scene).encodeToByteArray() }
        ExportFormat.Pdf -> scene(state, settings, withPixels = true)?.let { PdfWriter.write(it.scene) }
    }

    /**
     * Decoded pixels of an image element — PDF needs them for anything but JPEG. They go in
     * uncompressed, so big photos are shrunk to at most [MAX_IMAGE_SIDE] pixels first.
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun pixelsOf(element: com.nodenote.model.CanvasElement): MapSceneBuilder.Pixels? {
        if (element.kind != ElementKind.Image) return null
        val decoded = runCatching { decodeImageOrNull(Base64.decode(element.content)) }.getOrNull() ?: return null
        val shrink = min(1f, MAX_IMAGE_SIDE.toFloat() / maxOf(decoded.width, decoded.height))
        val bitmap = if (shrink >= 1f) {
            decoded
        } else {
            val w = (decoded.width * shrink).roundToInt().coerceAtLeast(1)
            val h = (decoded.height * shrink).roundToInt().coerceAtLeast(1)
            ImageBitmap(w, h).also { small ->
                Canvas(small).drawImageRect(
                    decoded,
                    dstSize = IntSize(w, h),
                    paint = Paint().apply { filterQuality = FilterQuality.Medium },
                )
            }
        }
        val argb = IntArray(bitmap.width * bitmap.height)
        bitmap.readPixels(argb)
        return MapSceneBuilder.Pixels(argb, bitmap.width, bitmap.height)
    }

    private const val MAX_IMAGE_SIDE = 1200
}

private fun argb(color: Long) = Color(color.toInt())

/**
 * Draws a [Scene] with Compose at [k] pixels per scene unit. Text uses the app's font
 * but keeps the scene's line breaks; a line that comes out wider than its Helvetica
 * width is squeezed to fit, so the picture matches the PDF/SVG layout.
 */
internal fun DrawScope.drawScene(scene: Scene, k: Float, textMeasurer: TextMeasurer) {
    scene.background?.let { drawRect(argb(it)) }
    for (item in scene.items) {
        when (item) {
            is SceneRect -> {
                val topLeft = Offset(item.x * k, item.y * k)
                val size = Size(item.width * k, item.height * k)
                val corner = CornerRadius(item.radius * k, item.radius * k)
                item.fill?.let { drawRoundRect(argb(it), topLeft, size, corner) }
                item.stroke?.let {
                    drawRoundRect(argb(it), topLeft, size, corner, style = Stroke(width = item.strokeWidth * k, pathEffect = dashes(item.dash, k)))
                }
            }
            is SceneCircle -> {
                val center = Offset(item.cx * k, item.cy * k)
                item.fill?.let { drawCircle(argb(it), item.radius * k, center) }
                item.stroke?.let { drawCircle(argb(it), item.radius * k, center, style = Stroke(width = item.strokeWidth * k)) }
            }
            is SceneLine -> drawLine(
                argb(item.color),
                Offset(item.x1 * k, item.y1 * k),
                Offset(item.x2 * k, item.y2 * k),
                strokeWidth = item.width * k,
                cap = if (item.roundCap) StrokeCap.Round else StrokeCap.Butt,
                pathEffect = dashes(item.dash, k),
            )
            is ScenePolygon -> {
                if (item.points.size < 6) continue
                val path = Path().apply {
                    moveTo(item.points[0] * k, item.points[1] * k)
                    var i = 2
                    while (i + 1 < item.points.size) {
                        lineTo(item.points[i] * k, item.points[i + 1] * k)
                        i += 2
                    }
                    close()
                }
                drawPath(path, argb(item.fill))
            }
            is SceneText -> drawSceneText(item, k, textMeasurer)
            is SceneImage -> {
                val image = runCatching { decodeImageOrNull(item.bytes) }.getOrNull() ?: continue
                drawImage(
                    image,
                    dstOffset = IntOffset((item.x * k).roundToInt(), (item.y * k).roundToInt()),
                    dstSize = IntSize((item.width * k).roundToInt().coerceAtLeast(1), (item.height * k).roundToInt().coerceAtLeast(1)),
                )
            }
        }
    }
}

private fun dashes(dash: List<Float>?, k: Float): PathEffect? =
    dash?.takeIf { it.size >= 2 }?.let { PathEffect.dashPathEffect(it.map { d -> d * k }.toFloatArray()) }

private fun DrawScope.drawSceneText(t: SceneText, k: Float, textMeasurer: TextMeasurer) {
    val sizePx = t.size * k
    if (sizePx < 0.6f || t.text.isEmpty()) return
    val layout = textMeasurer.measure(
        t.text,
        TextStyle(
            color = argb(t.color),
            fontSize = (sizePx / density).sp,
            fontWeight = if (t.bold) FontWeight.Bold else FontWeight.Normal,
            letterSpacing = (t.letterSpacing * k / density).sp,
        ),
        maxLines = 1,
        softWrap = false,
    )
    val expected = Helvetica.width(t.text, t.size, t.bold, t.letterSpacing) * k
    val actual = layout.size.width.toFloat()
    val squeeze = if (actual > expected * 1.02f && actual > 0f) expected / actual else 1f
    val width = actual * squeeze
    val left = when (t.anchor) {
        TextAnchor.Start -> t.x * k
        TextAnchor.Middle -> t.x * k - width / 2f
        TextAnchor.End -> t.x * k - width
    }
    val baseline = t.y * k
    val topLeft = Offset(left, baseline - layout.firstBaseline)
    if (squeeze < 1f) {
        withTransform({ scale(squeeze, 1f, pivot = Offset(left, baseline)) }) { drawText(layout, topLeft = topLeft) }
    } else {
        drawText(layout, topLeft = topLeft)
    }
}
