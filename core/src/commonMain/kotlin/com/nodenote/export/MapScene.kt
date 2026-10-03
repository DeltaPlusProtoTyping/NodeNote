package com.nodenote.export

import com.nodenote.model.Area
import com.nodenote.model.BuiltinTypes
import com.nodenote.model.CanvasElement
import com.nodenote.model.ColorBy
import com.nodenote.model.ElementKind
import com.nodenote.model.Lens
import com.nodenote.model.Node
import com.nodenote.model.NodeStatus
import com.nodenote.model.Project
import com.nodenote.model.edgeType
import com.nodenote.model.effortArgb
import com.nodenote.model.importanceArgb
import com.nodenote.model.nodeType
import com.nodenote.model.privateAreaIds
import com.nodenote.model.privateNodeIds
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A rectangle in world units (or scene units, for overlay positions). */
data class MapRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    val right: Float get() = x + width
    val bottom: Float get() = y + height
}

/** Colors of an exported map, ARGB. */
data class MapStyle(
    val canvas: Long,
    val card: Long,
    val cardBorder: Long,
    val title: Long,
    val secondary: Long,
    val faint: Long,
    val panel: Long,
    val edge: Long,
    val edgeLabel: Long,
    val labelPlate: Long,
    val chipFill: Long,
) {
    companion object {
        val Light = MapStyle(
            canvas = 0xFFF5F6F8, card = 0xFFFFFFFF, cardBorder = 0xFFD9DEE5, title = 0xFF1C2128,
            secondary = 0xFF59636F, faint = 0xFF8B94A0, panel = 0xFFFFFFFF, edge = 0xFF9AA6B4,
            edgeLabel = 0xFF59636F, labelPlate = 0xFFF5F6F8, chipFill = 0xFFFFFFFF,
        )
        val Dark = MapStyle(
            canvas = 0xFF15171B, card = 0xFF1E2126, cardBorder = 0xFF2A2E35, title = 0xFFE6E9EE,
            secondary = 0xFF9AA3AF, faint = 0xFF6B7280, panel = 0xFF17191D, edge = 0xFF4A5260,
            edgeLabel = 0xFF9AA3AF, labelPlate = 0xFF15171B, chipFill = 0xFF1E2126,
        )
    }
}

/** What goes into an exported map and how it looks. Sizes are in world units unless noted. */
data class MapSceneOptions(
    val style: MapStyle = MapStyle.Light,
    val transparent: Boolean = false,
    /** The part of the board to show; null = all of it plus [padding]. */
    val region: MapRect? = null,
    val padding: Float = 48f,
    /** Private nodes and private areas (with everything in them) are left out unless this is set. */
    val includePrivate: Boolean = false,
    /** Only these nodes (and the connections between them); null = all. */
    val onlyNodeIds: Set<String>? = null,
    val showEdgeLabels: Boolean = true,
    val showDescriptions: Boolean = true,
    /** Overlay (legend, title block) units per scene unit: 1 for whole-board exports, 1/zoom for the current view. */
    val overlayScale: Float = 1f,
    val showLegend: Boolean = true,
    val legendTitle: String = "Legend",
    val legendX: Float = 1f,
    val legendY: Float = 0f,
    val legendScale: Float = 1f,
    val showTitleBlock: Boolean = true,
    val titleBlockX: Float = 0f,
    val titleBlockY: Float = 1f,
    /** Second line of the title block, e.g. "37 nodes · exported Oct 2, 2026". */
    val subtitle: String = "",
    /** What the card colors mean (the app's "Color by"); the legend follows it. */
    val colorBy: ColorBy = ColorBy.Type,
)

/** A built map: the scene plus where the overlays landed (scene units), for dragging them on a preview. */
class MapScene(val scene: Scene, val legendRect: MapRect?, val titleRect: MapRect?, val overlayMargin: Float)

/**
 * Turns a board into a vector [Scene] — the one drawing behind the PDF, SVG and PNG
 * exports, so all three look the same. Areas lie underneath as tinted territories with
 * their names; cards mirror the app's (type, status, private, importance and effort in
 * the header; the WHOLE title, wrapped — a card grows to fit it even if it's cut short
 * on screen), drawn at each node's own scale and tinted by the chosen [ColorBy]. Text is
 * set in Helvetica/Arial, measured with [Helvetica] so wrapping matches every viewer.
 */
object MapSceneBuilder {

    /** Decoded pixels for an image element (PDF can't embed PNGs as-is): ARGB, width, height. */
    class Pixels(val argb: IntArray, val width: Int, val height: Int)

    fun build(project: Project, options: MapSceneOptions, pixelsOf: (CanvasElement) -> Pixels? = { null }): MapScene? {
        val style = options.style
        val hiddenNodes = if (options.includePrivate) emptySet() else project.privateNodeIds()
        val hiddenAreas = if (options.includePrivate) emptySet() else project.privateAreaIds()
        val keep = project.nodes.filter { n ->
            n.id !in hiddenNodes && (options.onlyNodeIds == null || n.id in options.onlyNodeIds)
        }
        val keepIds = keep.mapTo(HashSet()) { it.id }
        val edges = project.edges.filter { it.fromNodeId in keepIds && it.toNodeId in keepIds }
        val elements = if (options.onlyNodeIds == null) project.elements else project.elements.filter { it.id in options.onlyNodeIds }
        // Biggest first, so an area drawn inside another lands on top of it.
        val areas = project.areas
            .filter { a -> a.id !in hiddenAreas && (options.onlyNodeIds == null || keep.any { a.contains(it.centerX, it.centerY) }) }
            .sortedByDescending { it.width * it.height }
        val lens = Lens(project, options.colorBy)

        // Every card at the height its full title needs (never shorter than on the board).
        val cards = keep.map { CardLayout(it, project, options.showDescriptions) }
        val cardById = cards.associateBy { it.node.id }

        val region = options.region ?: run {
            val rects = cards.map { MapRect(it.node.x, it.node.y, it.width, it.height) } +
                elements.map { MapRect(it.x, it.y, it.width, it.height) } +
                areas.map { MapRect(it.x, it.y, it.width, it.height) }
            if (rects.isEmpty()) return null
            val left = rects.minOf { it.x } - options.padding
            val top = rects.minOf { it.y } - options.padding
            MapRect(left, top, rects.maxOf { it.right } + options.padding - left, rects.maxOf { it.bottom } + options.padding - top)
        }
        if (region.width <= 0f || region.height <= 0f) return null
        val ox = -region.x
        val oy = -region.y

        val items = ArrayList<SceneItem>()

        // Territories at the bottom, then edges, then the cards.
        for (area in areas) items += areaItems(area, ox, oy)
        for (edge in edges) {
            val a = cardById[edge.fromNodeId] ?: continue
            val b = cardById[edge.toNodeId] ?: continue
            val ac = a.centerX + ox to a.centerY + oy
            val bc = b.centerX + ox to b.centerY + oy
            val start = rectEdgePoint(ac, a.width / 2f, a.height / 2f, bc)
            val end = rectEdgePoint(bc, b.width / 2f, b.height / 2f, ac)
            items += SceneLine(start.first, start.second, end.first, end.second, style.edge, width = 1.4f)
            val mid = (start.first + end.first) / 2f to (start.second + end.second) / 2f
            if (edge.type != BuiltinTypes.DEFAULT_EDGE) {
                items += arrowHead(start, end, 7f, style.edge)
                val typeColor = project.edgeType(edge.type).colorArgb
                items += SceneCircle(mid.first, mid.second, 5f, fill = style.chipFill, stroke = typeColor, strokeWidth = 1.2f)
                items += SceneCircle(mid.first, mid.second, 2.1f, fill = typeColor)
            }
            if (options.showEdgeLabels && edge.label.isNotBlank()) {
                val size = 10f
                val label = Helvetica.wrap(edge.label.trim(), 220f, size, maxLines = 1).first()
                val w = Helvetica.width(label, size)
                val top = mid.second + 9f
                items += SceneRect(mid.first - w / 2f - 5f, top - 2f, w + 10f, size * 1.25f + 4f, radius = 4f, fill = withAlpha(style.labelPlate, 0.9f))
                items += SceneText(mid.first, top + size * Helvetica.ASCENT + 1f, label, size, style.edgeLabel, anchor = TextAnchor.Middle)
            }
        }

        for (el in elements) items += elementItems(el, ox, oy, style, pixelsOf)
        for (card in cards) items += card.items(ox, oy, style, lens.colorOf(card.node))

        // Overlays sit in scene space, sized by overlayScale (so they look the same in a whole-board or current-view export).
        val u = options.overlayScale
        val margin = 16f * u
        var legendRect: MapRect? = null
        var titleRect: MapRect? = null
        if (options.showLegend) {
            legendItems(project, keep, edges, lens, options, region.width, region.height, margin)?.let { (rect, legendItems) ->
                legendRect = rect
                items += legendItems
            }
        }
        if (options.showTitleBlock) {
            titleItems(project, options, region.width, region.height, margin).let { (rect, titleItems) ->
                titleRect = rect
                items += titleItems
            }
        }

        val scene = Scene(
            width = region.width,
            height = region.height,
            items = items,
            background = if (options.transparent) null else style.canvas,
            title = project.name.ifBlank { "Board" },
        )
        return MapScene(scene, legendRect, titleRect, margin)
    }

    // ---- cards ----

    private const val PAD_X = 12f
    private const val PAD_TOP = 9f
    private const val HEADER = 15f
    private const val TITLE_TOP = PAD_TOP + HEADER + 5f
    private const val TITLE_SIZE = 13f
    private const val TITLE_LINE = 16f
    private const val DESC_SIZE = 10f

    /** One card, laid out at scale 1 ("base" units) and drawn at the node's scale. */
    private class CardLayout(val node: Node, val project: Project, showDescription: Boolean) {
        val s = node.scale
        val baseWidth = node.width / s
        val titleLines = Helvetica.wrap(node.title.trim().ifBlank { "(untitled)" }, baseWidth - 2 * PAD_X, TITLE_SIZE)
        val description = node.description.trim().takeIf { showDescription && it.isNotEmpty() }
            ?.let { Helvetica.wrap(it.replace('\n', ' '), baseWidth - 2 * PAD_X, DESC_SIZE, maxLines = 2) }
        val baseHeight = max(
            node.height / s,
            TITLE_TOP + titleLines.size * TITLE_LINE + (description?.let { 3f + it.size * 13f } ?: 0f) + PAD_TOP,
        )
        val width = baseWidth * s
        val height = baseHeight * s
        val centerX = node.x + width / 2f
        val centerY = node.y + height / 2f

        /** [tint] is the card's "Color by" color (null = the plain card). */
        fun items(ox: Float, oy: Float, style: MapStyle, tint: Long?): List<SceneItem> {
            val x0 = node.x + ox
            val y0 = node.y + oy
            fun x(v: Float) = x0 + v * s
            fun y(v: Float) = y0 + v * s
            val out = ArrayList<SceneItem>()
            out += SceneRect(
                x0, y0, width, height, radius = 10f * s,
                fill = if (tint != null) mix(style.card, tint, 0.2f) else style.card,
                stroke = when {
                    node.isPrivate -> PRIVATE
                    tint != null -> mix(style.card, tint, 0.85f)
                    else -> style.cardBorder
                },
                strokeWidth = if (tint != null && !node.isPrivate) 1.5f * s else s,
            )

            // Header, right side first: ratings, status, private marker; the type label gets what's left.
            val headerMid = PAD_TOP + HEADER / 2f
            var right = baseWidth - PAD_X
            if (node.effort > 0) {
                val label = "E${node.effort}"
                val w = Helvetica.width(label, 8.5f, bold = true) + 6f
                right -= w
                val c = effortColor(node.effort)
                out += SceneRect(x(right), y(headerMid - 7.5f), w * s, 15f * s, radius = 4f * s, stroke = withAlpha(c, 0.8f), strokeWidth = s)
                out += SceneText(x(right + w / 2f), y(baseline(headerMid, 8.5f)), label, 8.5f * s, c, bold = true, anchor = TextAnchor.Middle)
                right -= 3f
            }
            if (node.importance > 0) {
                right -= 15f
                val c = importanceColor(node.importance)
                out += SceneRect(x(right), y(headerMid - 7.5f), 15f * s, 15f * s, radius = 4f * s,
                    fill = withAlpha(c, 0.22f), stroke = withAlpha(c, 0.9f), strokeWidth = s)
                out += SceneText(x(right + 7.5f), y(baseline(headerMid, 8.7f)), node.importance.toString(), 8.7f * s, c, bold = true, anchor = TextAnchor.Middle)
                right -= 5f
            }
            if (node.status != NodeStatus.None) {
                val label = node.status.label.uppercase()
                val w = Helvetica.width(label, 8f, bold = true, letterSpacing = 0.6f)
                right -= w
                out += SceneText(x(right), y(baseline(headerMid, 8f)), label, 8f * s, node.status.colorArgb, bold = true, letterSpacing = 0.6f * s)
                right -= 9f
                out += SceneCircle(x(right + 3f), y(headerMid), 3f * s, fill = node.status.colorArgb)
                right -= 5f
            }
            if (node.isPrivate) {
                right -= Helvetica.width("PRIVATE", 8f, bold = true, letterSpacing = 0.6f)
                out += SceneText(x(right), y(baseline(headerMid, 8f)), "PRIVATE", 8f * s, PRIVATE, bold = true, letterSpacing = 0.6f * s)
                right -= 6f
            }
            val type = project.nodeType(node.type)
            out += SceneCircle(x(PAD_X + 3.5f), y(headerMid), 3.5f * s, fill = type.colorArgb)
            val typeRoom = right - (PAD_X + 13f)
            if (typeRoom > 12f) {
                val label = Helvetica.wrap(type.label.uppercase(), typeRoom, 9f, bold = true, maxLines = 1).first()
                out += SceneText(x(PAD_X + 13f), y(baseline(headerMid, 9f)), label, 9f * s, style.faint, bold = true, letterSpacing = s)
            }

            // The whole title.
            titleLines.forEachIndexed { i, line ->
                val top = TITLE_TOP + i * TITLE_LINE
                out += SceneText(x(PAD_X), y(baseline(top + TITLE_LINE / 2f, TITLE_SIZE)), line, TITLE_SIZE * s, style.title)
            }
            description?.forEachIndexed { i, line ->
                val top = TITLE_TOP + titleLines.size * TITLE_LINE + 3f + i * 13f
                out += SceneText(x(PAD_X), y(baseline(top + 6.5f, DESC_SIZE)), line, DESC_SIZE * s, style.secondary)
            }
            return out
        }
    }

    // ---- areas ----

    private const val AREA_PAD = 16f
    private const val AREA_PAD_TOP = 13f

    /** A territory: tinted, outlined, its name in the corner, in a size that reads from far away. */
    private fun areaItems(area: Area, ox: Float, oy: Float): List<SceneItem> {
        val x = area.x + ox
        val y = area.y + oy
        val tint = area.tint
        val out = ArrayList<SceneItem>()
        out += SceneRect(
            x, y, area.width, area.height, radius = 18f,
            fill = withAlpha(tint, 0.10f),
            stroke = if (area.isPrivate) withAlpha(PRIVATE, 0.85f) else withAlpha(tint, 0.6f),
            strokeWidth = 1.4f,
        )
        val title = area.title.trim()
        if (title.isNotEmpty()) {
            val size = areaTitleSize(area)
            val line = Helvetica.wrap(title, area.width - 2 * AREA_PAD, size, bold = true, maxLines = 1).first()
            val baselineY = y + AREA_PAD_TOP + size * Helvetica.ASCENT
            out += SceneText(x + AREA_PAD, baselineY, line, size, tint, bold = true)
            if (area.isPrivate) {
                val tagX = x + AREA_PAD + Helvetica.width(line, size, bold = true) + 10f
                out += SceneText(tagX, baselineY, "PRIVATE", 9f, PRIVATE, bold = true, letterSpacing = 0.6f)
            }
        }
        return out
    }

    /** An area's name grows with the area (so a big territory reads from across the room), 18 to 36 units. */
    fun areaTitleSize(area: Area): Float = (area.width / 14f).coerceIn(18f, 36f)

    /** Baseline that centers a line of [size] on [middle]. */
    private fun baseline(middle: Float, size: Float) = middle + (Helvetica.ASCENT - Helvetica.DESCENT) / 2f * size

    // ---- canvas elements ----

    @OptIn(ExperimentalEncodingApi::class)
    private fun elementItems(el: CanvasElement, ox: Float, oy: Float, style: MapStyle, pixelsOf: (CanvasElement) -> Pixels?): List<SceneItem> =
        when (el.kind) {
            ElementKind.Text -> {
                if (el.text.isBlank()) emptyList()
                else {
                    val size = el.fontSize
                    val lineHeight = size * 1.35f
                    Helvetica.wrap(el.text, el.width - 12f, size).mapIndexed { i, line ->
                        val top = el.y + oy + 6f + i * lineHeight
                        SceneText(el.x + ox + 6f, baseline(top + lineHeight / 2f, size), line, size, style.title)
                    }
                }
            }
            ElementKind.Image -> {
                val bytes = runCatching { Base64.decode(el.content) }.getOrNull()
                if (bytes == null) emptyList()
                else {
                    val px = pixelsOf(el)
                    listOf(SceneImage(el.x + ox, el.y + oy, el.width, el.height, bytes, px?.argb, px?.width ?: 0, px?.height ?: 0))
                }
            }
        }

    // ---- overlays ----

    private fun place(width: Float, height: Float, sceneW: Float, sceneH: Float, margin: Float, fx: Float, fy: Float): Pair<Float, Float> {
        val freeW = max(0f, sceneW - width - margin * 2f)
        val freeH = max(0f, sceneH - height - margin * 2f)
        return margin + freeW * fx.coerceIn(0f, 1f) to margin + freeH * fy.coerceIn(0f, 1f)
    }

    /**
     * What the card colors mean (node types, or the [ColorBy] scale when the cards are
     * colored by something else), then any typed connections.
     */
    private fun legendItems(
        project: Project,
        nodes: List<Node>,
        edges: List<com.nodenote.model.Edge>,
        lens: Lens,
        options: MapSceneOptions,
        sceneW: Float,
        sceneH: Float,
        margin: Float,
    ): Pair<MapRect, List<SceneItem>>? {
        val types = if (lens.by == ColorBy.Type) {
            nodes.map { it.type }.distinct().map { project.nodeType(it) }.sortedBy { it.label.lowercase() }.map { it.label to it.colorArgb }
        } else {
            lens.legend(nodes).map { it.label to it.colorArgb }
        }
        val links = edges.map { it.type }.filter { it != BuiltinTypes.DEFAULT_EDGE }.distinct().map { project.edgeType(it) }
        if (types.isEmpty()) return null
        val u = options.overlayScale * options.legendScale
        val style = options.style
        val pad = 12f * u
        val row = 19f * u
        val heading = 10f * u
        val text = 12f * u
        val name = options.legendTitle.trim().ifBlank { "Legend" }
        val title = (if (lens.by == ColorBy.Type) name else "$name · ${lens.by.label}").uppercase()
        val rows = types + links.map { it.label to it.colorArgb }
        val sectionGap = if (links.isNotEmpty()) 8f * u else 0f
        val contentW = max(
            Helvetica.width(title, heading, bold = true, letterSpacing = 1.2f * u),
            rows.maxOf { Helvetica.width(it.first, text) } + 18f * u,
        )
        val w = contentW + pad * 2f
        val h = pad * 2f + heading + 8f * u + rows.size * row + sectionGap
        val (x, y) = place(w, h, sceneW, sceneH, margin, options.legendX, options.legendY)
        val out = ArrayList<SceneItem>()
        out += SceneRect(x, y, w, h, radius = 8f * u, fill = style.panel, stroke = style.cardBorder, strokeWidth = u)
        out += SceneText(x + pad, y + pad + heading * Helvetica.ASCENT, title, heading, style.faint, bold = true, letterSpacing = 1.2f * u)
        var rowTop = y + pad + heading + 8f * u
        types.forEach { (label, color) ->
            if (lens.by == ColorBy.Type) {
                out += SceneCircle(x + pad + 5f * u, rowTop + row / 2f, 4.5f * u, fill = color)
            } else {
                // A card-like swatch: tinted the way the cards are (outlined only when untinted).
                val sw = 11f * u
                out += SceneRect(
                    x + pad, rowTop + (row - sw) / 2f, sw, sw, radius = 3f * u,
                    fill = if (color != 0L) mix(style.card, color, 0.2f) else style.card,
                    stroke = if (color != 0L) mix(style.card, color, 0.85f) else style.cardBorder, strokeWidth = 1.2f * u,
                )
            }
            out += SceneText(x + pad + 18f * u, baseline(rowTop + row / 2f, text), label, text, style.title)
            rowTop += row
        }
        if (links.isNotEmpty()) rowTop += sectionGap
        links.forEach { t ->
            out += SceneLine(x + pad, rowTop + row / 2f, x + pad + 11f * u, rowTop + row / 2f, t.colorArgb, width = 1.6f * u)
            out += SceneText(x + pad + 18f * u, baseline(rowTop + row / 2f, text), t.label, text, style.title)
            rowTop += row
        }
        return MapRect(x, y, w, h) to out
    }

    private fun titleItems(project: Project, options: MapSceneOptions, sceneW: Float, sceneH: Float, margin: Float): Pair<MapRect, List<SceneItem>> {
        val u = options.overlayScale
        val style = options.style
        val pad = 12f * u
        val nameSize = 15f * u
        val subSize = 10f * u
        val name = project.name.ifBlank { "Board" }
        val sub = options.subtitle
        val w = max(Helvetica.width(name, nameSize, bold = true), Helvetica.width(sub, subSize)) + pad * 2f
        val h = pad * 2f + nameSize * 1.2f + if (sub.isNotEmpty()) 4f * u + subSize * 1.2f else 0f
        val (x, y) = place(w, h, sceneW, sceneH, margin, options.titleBlockX, options.titleBlockY)
        val out = ArrayList<SceneItem>()
        out += SceneRect(x, y, w, h, radius = 8f * u, fill = style.panel, stroke = style.cardBorder, strokeWidth = u)
        out += SceneText(x + pad, y + pad + nameSize * Helvetica.ASCENT, name, nameSize, style.title, bold = true)
        if (sub.isNotEmpty()) {
            out += SceneText(x + pad, y + pad + nameSize * 1.2f + 4f * u + subSize * Helvetica.ASCENT, sub, subSize, style.faint)
        }
        return MapRect(x, y, w, h) to out
    }

    // ---- geometry and colors ----

    /** Where the segment from [center] towards [towards] leaves a box of half-size ([halfW], [halfH]) around [center]. */
    private fun rectEdgePoint(center: Pair<Float, Float>, halfW: Float, halfH: Float, towards: Pair<Float, Float>): Pair<Float, Float> {
        val dx = towards.first - center.first
        val dy = towards.second - center.second
        val adx = abs(dx)
        val ady = abs(dy)
        if (adx < 1e-3f && ady < 1e-3f) return center
        val t = min(min(if (adx > 0f) halfW / adx else Float.MAX_VALUE, if (ady > 0f) halfH / ady else Float.MAX_VALUE), 1f)
        return center.first + dx * t to center.second + dy * t
    }

    private fun arrowHead(start: Pair<Float, Float>, end: Pair<Float, Float>, size: Float, color: Long): ScenePolygon {
        val dx = end.first - start.first
        val dy = end.second - start.second
        val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
        val ux = dx / len
        val uy = dy / len
        val bx = end.first - ux * size
        val by = end.second - uy * size
        return ScenePolygon(
            listOf(end.first, end.second, bx - uy * size * 0.5f, by + ux * size * 0.5f, bx + uy * size * 0.5f, by - ux * size * 0.5f),
            color,
        )
    }

    private const val PRIVATE = 0xFFE5B25B

    /** Same scales as the app (ChatWidgets): importance 1–2 slate … 9–10 red; effort green → amber → violet. */
    fun importanceColor(importance: Int): Long = importanceArgb(importance)

    fun effortColor(effort: Int): Long = effortArgb(effort)

    private fun withAlpha(argb: Long, alpha: Float): Long =
        ((alpha.coerceIn(0f, 1f) * 255f).toLong() shl 24) or (argb and 0xFFFFFF)

    /** Opaque blend: [t] of the way from [base] to [top]. */
    private fun mix(base: Long, top: Long, t: Float): Long {
        fun ch(c: Long, shift: Int) = ((c shr shift) and 0xFF).toFloat()
        fun blend(shift: Int) = (ch(base, shift) + (ch(top, shift) - ch(base, shift)) * t).toLong().coerceIn(0, 255) shl shift
        return 0xFF000000 or blend(16) or blend(8) or blend(0)
    }
}
