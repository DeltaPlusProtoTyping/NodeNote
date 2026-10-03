package com.nodenote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodenote.model.BuiltinTypes
import com.nodenote.model.Project
import com.nodenote.model.edgeType
import com.nodenote.state.AppState
import com.nodenote.theme.Accent
import com.nodenote.theme.CanvasBackground
import com.nodenote.theme.PanelBackground
import com.nodenote.theme.PanelBackgroundRaised
import com.nodenote.theme.PanelBorder
import com.nodenote.theme.TextFaint
import com.nodenote.theme.TextPrimary
import com.nodenote.theme.TextSecondary
import com.nodenote.theme.ThemeState
import com.nodenote.theme.color
import kotlin.math.abs
import kotlin.math.min

/**
 * The graph editor viewport.
 *
 * Rendering is layers inside one Box:
 *  1. A Compose [Canvas] that draws the dot grid, the areas' tinted territories,
 *     all edges, and the dashed "pending connection" line.
 *  2. One [NodeCard] composable per node, absolutely positioned on top.
 *  3. Area names (each the handle of its area) and the floating toolbars.
 *
 * The camera transform is `screen = world * (zoom * density) + pan` (see
 * [AppState]); both layers apply the same transform so cards and edge endpoints
 * always agree.
 *
 * Gesture routing:
 *  - Middle-button (scroll wheel) drag pans the camera, anywhere on the canvas —
 *    handled in the Initial pass so it wins even when the drag starts on a node.
 *  - Scroll wheel zooms at the cursor.
 *  - Left-drag on a node moves the selection (NodeCard consumes it first);
 *    left-drag on empty canvas draws a box-selection marquee. Touch drag pans,
 *    so the same code works on iOS.
 *  - Tap on empty canvas (inside an area too) deselects / cancels a pending
 *    connection; a tap on a link selects the link. An area is selected by its name.
 *  - After "Area" with nothing selected, the box-drag draws the new area instead.
 */
@Composable
fun GraphCanvas(state: AppState, modifier: Modifier = Modifier) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current.density
    val focusRequester = remember { FocusRequester() }

    // Keep AppState's camera math in sync with the actual viewport metrics.
    SideEffect { state.viewDensity = density }

    // Back to the canvas after an inline title edit, so Tab / Enter / Delete keep working.
    LaunchedEffect(state, state.canvasFocusRequests) {
        if (state.canvasFocusRequests > 0) focusRequester.requestFocus()
    }

    // A freshly loaded board is framed once the canvas knows its size.
    val canvasReady = state.canvasSize.width > 0
    LaunchedEffect(state, canvasReady, state.syncedProject != null) {
        if (canvasReady && state.syncedProject != null && state.zoomToFitWhenSized) {
            state.zoomToFitWhenSized = false
            if (state.project.nodes.isNotEmpty() || state.project.elements.isNotEmpty() || state.project.areas.isNotEmpty()) {
                state.zoomToFit()
                if (state.zoom > 1f) state.resetZoomKeepingCenter()
            }
        }
    }

    Box(
        modifier
            .clipToBounds()
            .background(CanvasBackground)
            .onSizeChanged { state.canvasSize = it }
            // Pressing the canvas moves keyboard focus out of any panel text
            // field (see the event loop below), which re-arms the window-level
            // Delete-selected-nodes shortcut in main.kt.
            .focusRequester(focusRequester)
            .focusable()
            // Left-drag on empty canvas: box-selection marquee for mouse, camera
            // pan for touch (iOS). Mouse panning is middle-drag, handled below.
            // Keyed on [state]: switching/opening a tab swaps the AppState instance,
            // and the handler must rebind to the one actually being rendered.
            .pointerInput(state) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (change.type == PointerType.Touch && !state.drawingArea) {
                            state.pan += dragAmount
                        } else {
                            val start = state.marquee?.first ?: (change.position - dragAmount)
                            state.marquee = start to change.position
                        }
                    },
                    onDragEnd = { state.finishMarquee() },
                    onDragCancel = { state.marquee = null },
                )
            }
            // Tap on empty canvas: clear selection / abort a pending connection.
            // Double-tap on empty canvas: quick-add a node of the last-used type.
            .pointerInput(state) {
                detectTapGestures(
                    onTap = { pos ->
                        state.cancelConnection()
                        if (!state.drawingArea) {
                            // Inside an area too: empty space deselects; the area's name is its handle.
                            val edge = state.edgeAt(pos)
                            if (edge != null) state.selectEdge(edge) else state.clearSelection()
                        }
                    },
                    onDoubleTap = { pos -> if (!state.drawingArea) state.addNodeAt(state.screenToWorld(pos)) },
                )
            }
            // Raw event loop for what the gesture detectors don't cover:
            // mouse-wheel zoom anchored at the cursor, middle-button panning, and
            // pointer tracking so the pending-connection line can follow the mouse.
            // Runs in the Initial pass (parent sees events before children) so a
            // middle-button drag pans the canvas even when it starts on top of a
            // node — consuming the move cancels the node's own drag detector.
            .pointerInput(state) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        // Modifier keys ride along on pointer events — reliable in the browser too.
                        state.altDown = event.keyboardModifiers.isAltPressed
                        // Two fingers: pinch zooms around the fingers, and moving them pans.
                        // Consumed here, in the parent's first pass, so the one-finger
                        // drag handlers (pan / move node) stand down.
                        if (event.changes.count { it.pressed && it.type == PointerType.Touch } >= 2) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            if (zoomChange != 1f) state.zoomBy(zoomChange, event.calculateCentroid(useCurrent = true))
                            if (panChange != Offset.Zero) state.pan += panChange
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        when (event.type) {
                            // Any press over the canvas (or a node on it) gives the
                            // canvas keyboard focus for the Delete shortcut. Clicks
                            // that land on a text field re-take focus right after.
                            PointerEventType.Press -> focusRequester.requestFocus()
                            PointerEventType.Scroll -> {
                                val change = event.changes.first()
                                val delta = change.scrollDelta.y
                                if (delta != 0f) {
                                    state.zoomBy(if (delta < 0) 1.15f else 1 / 1.15f, change.position)
                                    change.consume()
                                }
                            }
                            PointerEventType.Move -> {
                                val change = event.changes.first()
                                state.pointerPos = change.position
                                if (event.buttons.isTertiaryPressed || event.buttons.isSecondaryPressed) {
                                    val delta = change.positionChange()
                                    if (delta != Offset.Zero) {
                                        state.pan += delta
                                        change.consume()
                                    }
                                }
                            }
                            else -> Unit
                        }
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawDotGrid(state.pan, state.zoom, density)
            drawAreas(state)
            drawEdges(state, textMeasurer)
            drawPendingConnection(state)
            drawMarquee(state)
        }

        // Elements (text blocks, images) sit under the node cards.
        for (element in state.project.elements) {
            key(element.id) { ElementView(element, state) }
        }
        // While presenting, private nodes aren't drawn at all.
        for (node in state.shownProject.nodes) {
            key(node.id) { NodeCard(node, state) }
        }

        // Area names on top of the cards: each one is its area's handle (and at a far zoom, the big name).
        for (area in state.shownProject.areas.sortedByDescending { it.width * it.height }) {
            key("area-" + area.id) { AreaLabel(area, state) }
        }

        ColorByControl(state, Modifier.align(Alignment.TopEnd).padding(10.dp))

        val selected = state.selectedNode
        val quiet = state.connectFromId == null && state.marquee == null
        if (selected != null && selected.id !in state.hiddenNodeIds && quiet) {
            NodeQuickBarOverlay(state, selected)
        }
        val selectedArea = state.selectedArea?.takeIf { a -> state.selectedNodeIds.isEmpty() && state.shownProject.areas.any { it.id == a.id } }
        if (selectedArea != null && quiet) {
            AreaResizeGrip(selectedArea, state)
            AreaQuickBarOverlay(state, selectedArea)
        }
        val selectedEdge = state.selectedEdgeId?.let { id -> state.shownProject.edges.find { it.id == id } }
        if (selectedEdge != null && quiet) LinkQuickBarOverlay(state, selectedEdge)

        if (state.presenting) PresentingBadge(state, Modifier.align(Alignment.TopStart).padding(10.dp))
        if (state.drawingArea) DrawingAreaHint(state, Modifier.align(Alignment.TopCenter).padding(10.dp))

        if (state.project.nodes.isEmpty() && state.project.elements.isEmpty() && state.project.areas.isEmpty()) {
            EmptyCanvasHint(Modifier.align(Alignment.Center))
        }
    }
}

/** Colors for edge rendering, so the exporter can re-theme edges (light/dark) without duplicating the drawing code. */
internal data class EdgeStyle(
    val line: Color,
    val lineHighlight: Color,
    val label: Color,
    val labelHighlight: Color,
    val labelPlate: Color,
    val chipFill: Color,
)

/** Edge colors for the live canvas, derived from the current app theme (re-read on every draw). */
internal fun liveEdgeStyle(): EdgeStyle = EdgeStyle(
    line = ThemeState.colors.edgeLine,
    lineHighlight = ThemeState.colors.accent,
    label = ThemeState.colors.textFaint,
    labelHighlight = ThemeState.colors.textSecondary,
    labelPlate = ThemeState.colors.canvasBackground,
    chipFill = ThemeState.colors.panelBackgroundRaised,
)

/** Figma-style dot grid. Spacing follows zoom, coarsening when zoomed far out so dot count stays bounded. */
private fun DrawScope.drawDotGrid(pan: Offset, zoom: Float, density: Float) {
    var spacing = AppState.GRID_SPACING * zoom * density
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
    drawPoints(
        points,
        pointMode = PointMode.Points,
        color = ThemeState.colors.gridDot,
        strokeWidth = (2f * zoom * density).coerceIn(1.5f, 3f),
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.drawEdges(state: AppState, textMeasurer: TextMeasurer) {
    drawProjectEdges(
        project = state.shownProject,
        pan = state.pan,
        k = state.zoom * state.viewDensity,
        zoom = state.zoom,
        selectedIds = state.selectedNodeIds,
        textMeasurer = textMeasurer,
        selectedEdgeId = state.selectedEdgeId,
    )
}

/**
 * Draws every edge trimmed to the card borders. A plain "Relates to" link is just
 * a line (which way it was drawn means nothing); typed links (Causes, Blocks,
 * Solves…) get an arrowhead and a small "connection node" at the midpoint in the
 * type's color. The optional label sits just below the midpoint.
 *
 * Internal (not private) because the PNG exporter reuses it with the same
 * camera parameters and an empty selection. [k] = zoom * density.
 */
internal fun DrawScope.drawProjectEdges(
    project: Project,
    pan: Offset,
    k: Float,
    zoom: Float,
    selectedIds: Set<String>,
    textMeasurer: TextMeasurer,
    style: EdgeStyle = liveEdgeStyle(),
    showLabels: Boolean = true,
    selectedEdgeId: String? = null,
) {
    val nodesById = project.nodes.associateBy { it.id }

    for (edge in project.edges) {
        val from = nodesById[edge.fromNodeId] ?: continue
        val to = nodesById[edge.toNodeId] ?: continue

        val fromCenter = Offset(from.centerX, from.centerY) * k + pan
        val toCenter = Offset(to.centerX, to.centerY) * k + pan
        // Trim the line to each card's border so the arrowhead lands on the target's edge.
        val start = rectEdgePoint(fromCenter, from.width / 2f * k, from.height / 2f * k, toCenter)
        val end = rectEdgePoint(toCenter, to.width / 2f * k, to.height / 2f * k, fromCenter)

        val picked = edge.id == selectedEdgeId
        val highlighted = picked || edge.fromNodeId in selectedIds || edge.toNodeId in selectedIds
        val color = if (highlighted) style.lineHighlight else style.line
        drawLine(
            color = color,
            start = start,
            end = end,
            strokeWidth = (1.4f * k).coerceIn(1f, 3f) * if (picked) 2.2f else 1f,
            cap = StrokeCap.Round,
        )
        val mid = (start + end) / 2f
        val radius = 5f * k.coerceIn(0.6f, 1.6f)
        if (edge.type != BuiltinTypes.DEFAULT_EDGE) {
            drawArrowHead(color, start, end, sizePx = 7f * k.coerceIn(0.6f, 1.8f))
            // Midpoint connection node: disc with a ring + dot in the edge type's color.
            val typeColor = project.edgeType(edge.type).color
            drawCircle(style.chipFill, radius, mid)
            drawCircle(typeColor, radius * 0.42f, mid)
            drawCircle(typeColor, radius, mid, style = Stroke(width = (1.2f * k).coerceIn(1f, 2.5f)))
        }

        if (edge.label.isNotBlank() && showLabels) {
            val textStyle = TextStyle(
                color = if (highlighted) style.labelHighlight else style.label,
                fontSize = (10f * zoom).coerceIn(7f, 13f).sp,
            )
            val layout = textMeasurer.measure(edge.label, textStyle)
            val topLeft = Offset(mid.x - layout.size.width / 2f, mid.y + radius + 4f)
            // A small backing plate so the label stays readable on top of the grid.
            drawRoundRect(
                color = style.labelPlate.copy(alpha = 0.88f),
                topLeft = topLeft - Offset(5f, 2f),
                size = Size(layout.size.width + 10f, layout.size.height + 4f),
                cornerRadius = CornerRadius(4f, 4f),
            )
            drawText(layout, topLeft = topLeft)
        }
    }
}

/** Accent-tinted rectangle while box-selecting on empty canvas. */
private fun DrawScope.drawMarquee(state: AppState) {
    val (a, b) = state.marquee ?: return
    val topLeft = Offset(min(a.x, b.x), min(a.y, b.y))
    val size = Size(abs(b.x - a.x), abs(b.y - a.y))
    drawRect(Accent.copy(alpha = 0.08f), topLeft, size)
    drawRect(Accent.copy(alpha = 0.7f), topLeft, size, style = Stroke(width = 1f * state.viewDensity))
}

/** Dashed accent line from the connection source node to the mouse cursor. */
private fun DrawScope.drawPendingConnection(state: AppState) {
    val fromId = state.connectFromId ?: return
    val from = state.project.nodes.find { it.id == fromId } ?: return
    val k = state.zoom * state.viewDensity
    val start = Offset(from.centerX, from.centerY) * k + state.pan
    drawLine(
        color = Accent.copy(alpha = 0.85f),
        start = start,
        end = state.pointerPos,
        strokeWidth = 1.5f * state.viewDensity,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
        cap = StrokeCap.Round,
    )
    drawCircle(Accent, radius = 3f * state.viewDensity, center = start)
}

/**
 * Point where the segment from [center] towards [towards] exits an axis-aligned
 * rectangle of half-extents ([halfW], [halfH]) centered at [center].
 */
private fun rectEdgePoint(center: Offset, halfW: Float, halfH: Float, towards: Offset): Offset {
    val d = towards - center
    val adx = abs(d.x)
    val ady = abs(d.y)
    if (adx < 1e-3f && ady < 1e-3f) return center
    val t = minOf(
        if (adx > 0f) halfW / adx else Float.MAX_VALUE,
        if (ady > 0f) halfH / ady else Float.MAX_VALUE,
        1f, // never overshoot past the other node's center
    )
    return center + d * t
}

private fun DrawScope.drawArrowHead(color: Color, start: Offset, end: Offset, sizePx: Float) {
    val d = end - start
    val len = d.getDistance()
    if (len < 1f) return
    val u = d / len
    val p = Offset(-u.y, u.x) // perpendicular
    val base = end - u * sizePx
    val path = Path().apply {
        moveTo(end.x, end.y)
        lineTo(base.x + p.x * sizePx * 0.5f, base.y + p.y * sizePx * 0.5f)
        lineTo(base.x - p.x * sizePx * 0.5f, base.y - p.y * sizePx * 0.5f)
        close()
    }
    drawPath(path, color)
}

/** Reminder that this screen is in presentation mode, with the way out. */
@Composable
private fun PresentingBadge(state: AppState, modifier: Modifier = Modifier) {
    val hidden = state.hiddenNodeIds.size
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(PanelBackground.copy(alpha = 0.92f))
            .border(1.dp, PrivateAmber.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(Glyph.Lock, PrivateAmber, 11.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            "Presenting" + if (hidden > 0) " · $hidden private hidden" else "",
            fontSize = 12.sp,
            color = TextPrimary,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "Stop",
            fontSize = 12.sp,
            color = Accent,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { state.togglePresenting() }.padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun EmptyCanvasHint(modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(PanelBackground.copy(alpha = 0.65f))
            .border(1.dp, PanelBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 30.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(16.dp).background(Accent, RoundedCornerShape(5.dp)))
            Spacer(Modifier.width(10.dp))
            Text("NodeNote", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        }
        Spacer(Modifier.height(5.dp))
        Text("An empty board — map the topics, then talk under each one.", fontSize = 12.sp, color = TextSecondary)
        Spacer(Modifier.height(20.dp))
        EmptyHintRow("Add", "+ Add Node, or double-click the canvas")
        EmptyHintRow("Branch", "select a node: Tab adds a child, Enter a sibling")
        EmptyHintRow("Chat", "select a node; the chat panel is on the right")
        EmptyHintRow("Braindump", "nothing selected = post to the board inbox")
        EmptyHintRow("Rate", "mark key details 1–10 as you write them")
        EmptyHintRow("Navigate", "middle- or right-drag to pan · scroll to zoom")
        EmptyHintRow("Connect", "drag a node's port dot to another node")
        EmptyHintRow("Areas", "select cards, then Area (Ctrl+G) to name a territory")
    }
}

@Composable
private fun EmptyHintRow(label: String, detail: String) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label.uppercase(),
            fontSize = 9.sp,
            letterSpacing = 0.8.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextFaint,
            modifier = Modifier.width(78.dp),
        )
        Text(detail, fontSize = 11.sp, color = TextSecondary)
    }
}
