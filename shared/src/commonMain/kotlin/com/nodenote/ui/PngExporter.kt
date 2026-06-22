package com.nodenote.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.nodenote.model.BuiltinTypes
import com.nodenote.model.CanvasElement
import com.nodenote.model.ElementKind
import com.nodenote.model.ExportRegion
import com.nodenote.model.ExportSettings
import com.nodenote.model.ExportTheme
import com.nodenote.model.Node
import com.nodenote.model.NodeTypeDef
import com.nodenote.model.Project
import com.nodenote.model.edgeType
import com.nodenote.model.nodeType
import com.nodenote.state.AppState
import com.nodenote.storage.decodeImageOrNull
import com.nodenote.theme.color
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.roundToInt

/** Full color set for one export theme; the dark palette mirrors the live editor. */
internal data class ExportPalette(
    val canvas: Color,
    val card: Color,
    val cardBorder: Color,
    val title: Color,
    val secondary: Color,
    val faint: Color,
    val legendBg: Color,
    val gridDot: Color,
    val edgeStyle: EdgeStyle,
) {
    companion object {
        // Fixed literals (not the app-theme accessors): the export's own Dark/Light
        // picker is independent of the editor's current appearance theme.
        val Dark = ExportPalette(
            canvas = Color(0xFF15171B),
            card = Color(0xFF1E2126),
            cardBorder = Color(0xFF2A2E35),
            title = Color(0xFFE6E9EE),
            secondary = Color(0xFF9AA3AF),
            faint = Color(0xFF6B7280),
            legendBg = Color(0xFF17191D),
            gridDot = Color.White.copy(alpha = 0.07f),
            edgeStyle = EdgeStyle(
                line = Color(0xFF4A5260),
                lineHighlight = Color(0xFF5B9CFF),
                label = Color(0xFF6B7280),
                labelHighlight = Color(0xFF9AA3AF),
                labelPlate = Color(0xFF15171B),
                chipFill = Color(0xFF1E2126),
            ),
        )
        val Light = ExportPalette(
            canvas = Color(0xFFF5F6F8),
            card = Color.White,
            cardBorder = Color(0xFFD9DEE5),
            title = Color(0xFF1C2128),
            secondary = Color(0xFF59636F),
            faint = Color(0xFF8B94A0),
            legendBg = Color.White,
            gridDot = Color.Black.copy(alpha = 0.10f),
            edgeStyle = EdgeStyle(
                line = Color(0xFF9AA6B4),
                lineHighlight = Color(0xFF9AA6B4),
                label = Color(0xFF8B94A0),
                labelHighlight = Color(0xFF59636F),
                labelPlate = Color(0xFFF5F6F8),
                chipFill = Color.White,
            ),
        )
    }
}

/**
 * A finished export render. The overlay rects (image-pixel coordinates) let
 * the export dialog hit-test and drag the legend / title block on its preview.
 */
class ExportRender(
    val bitmap: ImageBitmap,
    val legendRect: Rect?,
    val titleBlockRect: Rect?,
    val overlayMargin: Float,
)

/**
 * Renders the project to an offscreen bitmap for PNG export, driven entirely
 * by [ExportSettings] (region/crop, theme, background, overlays, scale, …).
 *
 * Node cards are live composables, so they can't be reused here — this draws
 * equivalent cards with plain DrawScope calls. Edges reuse exactly the same
 * code as the live canvas (GraphCanvas.drawProjectEdges) with a theme palette.
 */
object PngExporter {

    private const val MAX_DIMENSION = 6000

    @OptIn(ExperimentalEncodingApi::class)
    fun render(state: AppState, settings: ExportSettings, textMeasurer: TextMeasurer): ExportRender? {
        val density = state.viewDensity
        val palette = if (settings.theme == ExportTheme.Light) ExportPalette.Light else ExportPalette.Dark

        // SelectedNodes exports only the selection and the edges between selected nodes.
        val project = if (settings.region == ExportRegion.SelectedNodes) {
            val ids = state.selectedNodeIds
            state.project.copy(
                nodes = state.project.nodes.filter { it.id in ids },
                elements = state.project.elements.filter { it.id in ids },
                edges = state.project.edges.filter { it.fromNodeId in ids && it.toNodeId in ids },
            )
        } else {
            state.project
        }

        // Camera for the chosen region: output size, pan (px) and world->px factor.
        var width: Float
        var height: Float
        var pan: Offset
        var k: Float // world -> px
        var textZoom: Float // multiplies sp font sizes (density is handled by the draw scope)
        when (settings.region) {
            ExportRegion.CurrentView -> {
                if (state.canvasSize.width <= 0 || state.canvasSize.height <= 0) return null
                width = state.canvasSize.width * settings.scale
                height = state.canvasSize.height * settings.scale
                pan = state.pan * settings.scale
                k = state.zoom * density * settings.scale
                textZoom = state.zoom * settings.scale
            }
            else -> {
                val bounds = contentBounds(project) ?: return null
                k = density * settings.scale
                textZoom = settings.scale
                width = (bounds.width + settings.padding * 2f) * k
                height = (bounds.height + settings.padding * 2f) * k
                pan = Offset(-(bounds.left - settings.padding) * k, -(bounds.top - settings.padding) * k)
            }
        }

        // Keep enormous exports in check (huge graphs at 3x): shrink everything uniformly.
        val cap = minOf(1f, MAX_DIMENSION / width, MAX_DIMENSION / height)
        width *= cap
        height *= cap
        pan *= cap
        k *= cap
        textZoom *= cap

        val widthPx = width.roundToInt().coerceAtLeast(1)
        val heightPx = height.roundToInt().coerceAtLeast(1)
        val bitmap = ImageBitmap(widthPx, heightPx)

        val overlayUnit = density * settings.scale * cap
        val fontScaleBase = settings.scale * cap
        var legendRect: Rect? = null
        var titleRect: Rect? = null

        CanvasDrawScope().draw(
            density = Density(density),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bitmap),
            size = Size(widthPx.toFloat(), heightPx.toFloat()),
        ) {
            if (!settings.transparentBackground) {
                drawRect(palette.canvas)
                if (settings.showGrid) drawExportGrid(pan, k, palette.gridDot)
            }
            drawProjectEdges(
                project = project,
                pan = pan,
                k = k,
                zoom = textZoom,
                selectedIds = emptySet(),
                textMeasurer = textMeasurer,
                style = palette.edgeStyle,
                showLabels = settings.showEdgeLabels,
            )
            for (element in project.elements) {
                drawElement(element, pan, k, textZoom, palette, textMeasurer)
            }
            for (node in project.nodes) {
                drawNodeCard(node, project.nodeType(node.type), pan, k, textZoom, palette, settings.showDescriptions, textMeasurer)
            }
            if (settings.showLegend) {
                legendRect = drawLegend(project, settings, overlayUnit, fontScaleBase, palette, textMeasurer)
            }
            if (settings.showTitleBlock) {
                titleRect = drawTitleBlock(project, settings, overlayUnit, fontScaleBase, palette, textMeasurer)
            }
        }
        return ExportRender(bitmap, legendRect, titleRect, overlayMargin = 16f * overlayUnit)
    }

    /** World-space bounding box of all exportable content (nodes + elements). */
    private fun contentBounds(project: Project): Rect? {
        val rects = project.nodes.map { Rect(it.x, it.y, it.x + it.width, it.y + it.height) } +
            project.elements.map { Rect(it.x, it.y, it.x + it.width, it.y + it.height) }
        if (rects.isEmpty()) return null
        return Rect(
            left = rects.minOf { it.left },
            top = rects.minOf { it.top },
            right = rects.maxOf { it.right },
            bottom = rects.maxOf { it.bottom },
        )
    }

    /** Same dot grid as the live canvas, in the export palette's color. */
    private fun DrawScope.drawExportGrid(pan: Offset, k: Float, dotColor: Color) {
        var spacing = AppState.GRID_SPACING * k
        while (spacing < 22f) spacing *= 2
        val points = ArrayList<Offset>()
        var x = pan.x.mod(spacing)
        while (x < size.width) {
            var y = pan.y.mod(spacing)
            while (y < size.height) {
                points.add(Offset(x, y))
                y += spacing
            }
            x += spacing
        }
        drawPoints(points, PointMode.Points, dotColor, strokeWidth = (2f * k).coerceIn(1.5f, 3f), cap = StrokeCap.Round)
    }

    /** Text blocks and images, drawn under the node cards (same layering as the live canvas). */
    @OptIn(ExperimentalEncodingApi::class)
    private fun DrawScope.drawElement(
        element: CanvasElement,
        pan: Offset,
        k: Float,
        textZoom: Float,
        palette: ExportPalette,
        textMeasurer: TextMeasurer,
    ) {
        val topLeft = Offset(element.x, element.y) * k + pan
        when (element.kind) {
            ElementKind.Text -> {
                if (element.text.isBlank()) return
                val layout = textMeasurer.measure(
                    element.text,
                    TextStyle(
                        color = palette.title,
                        fontSize = (element.fontSize * textZoom).sp,
                        lineHeight = (element.fontSize * 1.35f * textZoom).sp,
                    ),
                    constraints = Constraints(maxWidth = ((element.width - 12f) * k).toInt().coerceAtLeast(1)),
                )
                drawText(layout, topLeft = topLeft + Offset(6f * k, 6f * k))
            }
            ElementKind.Image -> {
                val image = runCatching { decodeImageOrNull(Base64.decode(element.content)) }.getOrNull() ?: return
                drawImage(
                    image = image,
                    dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
                    dstSize = IntSize((element.width * k).roundToInt().coerceAtLeast(1), (element.height * k).roundToInt().coerceAtLeast(1)),
                )
            }
        }
    }

    /** Static replica of the NodeCard composable. [k] = world -> px factor. */
    private fun DrawScope.drawNodeCard(
        node: Node,
        typeDef: NodeTypeDef,
        pan: Offset,
        k: Float,
        textZoom: Float,
        palette: ExportPalette,
        showDescription: Boolean,
        textMeasurer: TextMeasurer,
    ) {
        val topLeft = Offset(node.x, node.y) * k + pan
        val cardSize = Size(node.width * k, node.height * k)
        val corner = CornerRadius(10f * k, 10f * k)
        drawRoundRect(palette.card, topLeft, cardSize, corner)
        drawRoundRect(palette.cardBorder, topLeft, cardSize, corner, style = Stroke(width = 1f * k))

        val padX = 12f * k
        val maxTextWidth = ((node.width - 24f) * k).toInt().coerceAtLeast(1)
        val constraints = Constraints(maxWidth = maxTextWidth)

        // Header: type dot + uppercase type label.
        drawCircle(typeDef.color, radius = 3.5f * k, center = topLeft + Offset(padX + 3.5f * k, 13.5f * k))
        val typeLayout = textMeasurer.measure(
            typeDef.label.uppercase(),
            TextStyle(
                color = palette.faint,
                fontSize = (9f * textZoom).sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (1f * textZoom).sp,
            ),
            maxLines = 1,
            constraints = constraints,
        )
        drawText(typeLayout, topLeft = topLeft + Offset(padX + 13f * k, 13.5f * k - typeLayout.size.height / 2f))

        // Title (up to two lines, ellipsized) and one-line description.
        val titleLayout = textMeasurer.measure(
            node.title.ifBlank { "(untitled)" },
            TextStyle(
                color = palette.title,
                fontSize = (13f * textZoom).sp,
                fontWeight = FontWeight.Medium,
                lineHeight = (16f * textZoom).sp,
            ),
            overflow = TextOverflow.Ellipsis,
            maxLines = 2,
            constraints = constraints,
        )
        val titleTop = topLeft + Offset(padX, 23f * k)
        drawText(titleLayout, topLeft = titleTop)

        if (showDescription && node.description.isNotBlank()) {
            val descLayout = textMeasurer.measure(
                node.description,
                TextStyle(color = palette.secondary, fontSize = (10f * textZoom).sp),
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
                constraints = constraints,
            )
            val descTop = titleTop + Offset(0f, titleLayout.size.height + 3f * k)
            // Skip the description if it would spill out of the card.
            if (descTop.y + descLayout.size.height <= topLeft.y + cardSize.height - 6f * k) {
                drawText(descLayout, topLeft = descTop)
            }
        }
    }

    /** Positions a panel of [panelSize] using a (0..1, 0..1) fraction of the free area. */
    private fun DrawScope.overlayPosition(panelSize: Size, margin: Float, fx: Float, fy: Float): Offset {
        val freeW = (size.width - panelSize.width - margin * 2f).coerceAtLeast(0f)
        val freeH = (size.height - panelSize.height - margin * 2f).coerceAtLeast(0f)
        return Offset(margin + freeW * fx.coerceIn(0f, 1f), margin + freeH * fy.coerceIn(0f, 1f))
    }

    /** Legend listing the connection types used. Returns the drawn rect (image px) for preview dragging. */
    private fun DrawScope.drawLegend(
        project: Project,
        settings: ExportSettings,
        overlayUnit: Float,
        fontScaleBase: Float,
        palette: ExportPalette,
        textMeasurer: TextMeasurer,
    ): Rect? {
        // Resolve the used connection-type ids to defs, keeping catalog order.
        val order = (BuiltinTypes.edges + project.customEdgeTypes).map { it.id }
        val typesUsed = project.edges.map { it.type }.distinct()
            .map { project.edgeType(it) }
            .sortedBy { order.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        if (typesUsed.isEmpty()) return null

        val u = overlayUnit * settings.legendScale
        val fontScale = fontScaleBase * settings.legendScale
        val pad = 12f * u
        val rowHeight = 20f * u
        val chipRadius = 5f * u
        val chipGap = 9f * u

        val titleLayout = textMeasurer.measure(
            settings.legendTitle.uppercase().ifBlank { "CONNECTIONS" },
            TextStyle(
                color = palette.faint,
                fontSize = (10f * fontScale).sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (1.2f * fontScale).sp,
            ),
            maxLines = 1,
        )
        val rowLayouts = typesUsed.map { type ->
            type to textMeasurer.measure(
                type.label,
                TextStyle(color = palette.title, fontSize = (12f * fontScale).sp),
                maxLines = 1,
            )
        }

        val contentWidth = maxOf(
            titleLayout.size.width.toFloat(),
            rowLayouts.maxOf { it.second.size.width.toFloat() } + chipRadius * 2f + chipGap,
        )
        val panelSize = Size(
            contentWidth + pad * 2f,
            pad * 2f + titleLayout.size.height + 8f * u + typesUsed.size * rowHeight,
        )
        val margin = 16f * overlayUnit
        val panelTopLeft = overlayPosition(panelSize, margin, settings.legendX, settings.legendY)
        val corner = CornerRadius(8f * u, 8f * u)
        drawRoundRect(palette.legendBg, panelTopLeft, panelSize, corner)
        drawRoundRect(palette.cardBorder, panelTopLeft, panelSize, corner, style = Stroke(width = 1f * u))

        drawText(titleLayout, topLeft = panelTopLeft + Offset(pad, pad))

        var rowY = panelTopLeft.y + pad + titleLayout.size.height + 8f * u
        for ((type, layout) in rowLayouts) {
            val chipCenter = Offset(panelTopLeft.x + pad + chipRadius, rowY + rowHeight / 2f)
            // Same styling as the midpoint connection node on the canvas.
            drawCircle(palette.edgeStyle.chipFill, chipRadius, chipCenter)
            drawCircle(type.color, chipRadius * 0.42f, chipCenter)
            drawCircle(type.color, chipRadius, chipCenter, style = Stroke(width = 1.2f * u))
            drawText(layout, topLeft = Offset(chipCenter.x + chipRadius + chipGap - chipRadius, rowY + (rowHeight - layout.size.height) / 2f))
            rowY += rowHeight
        }
        return Rect(panelTopLeft, panelSize)
    }

    /** Project-name plate. Returns the drawn rect (image px) for preview dragging. */
    private fun DrawScope.drawTitleBlock(
        project: Project,
        settings: ExportSettings,
        overlayUnit: Float,
        fontScale: Float,
        palette: ExportPalette,
        textMeasurer: TextMeasurer,
    ): Rect {
        val pad = 12f * overlayUnit
        val nameLayout = textMeasurer.measure(
            project.name.ifBlank { "Untitled Project" },
            TextStyle(color = palette.title, fontSize = (13f * fontScale).sp, fontWeight = FontWeight.SemiBold),
            maxLines = 1,
        )
        val statsLayout = textMeasurer.measure(
            "${project.nodes.size} nodes · ${project.edges.size} connections",
            TextStyle(color = palette.faint, fontSize = (10f * fontScale).sp),
            maxLines = 1,
        )
        val panelSize = Size(
            maxOf(nameLayout.size.width, statsLayout.size.width) + pad * 2f,
            pad * 2f + nameLayout.size.height + 4f * overlayUnit + statsLayout.size.height,
        )
        val margin = 16f * overlayUnit
        val topLeft = overlayPosition(panelSize, margin, settings.titleBlockX, settings.titleBlockY)
        val corner = CornerRadius(8f * overlayUnit, 8f * overlayUnit)
        drawRoundRect(palette.legendBg, topLeft, panelSize, corner)
        drawRoundRect(palette.cardBorder, topLeft, panelSize, corner, style = Stroke(width = 1f * overlayUnit))
        drawText(nameLayout, topLeft = topLeft + Offset(pad, pad))
        drawText(statsLayout, topLeft = topLeft + Offset(pad, pad + nameLayout.size.height + 4f * overlayUnit))
        return Rect(topLeft, panelSize)
    }
}
