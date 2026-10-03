package com.nodenote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodenote.export.MapSceneBuilder
import com.nodenote.model.Area
import com.nodenote.model.AreaHandle
import com.nodenote.model.ColorBy
import com.nodenote.model.Edge
import com.nodenote.state.AppState
import com.nodenote.state.LocalAppState
import com.nodenote.theme.Accent
import com.nodenote.theme.DangerRed
import com.nodenote.theme.PanelBackground
import com.nodenote.theme.PanelBackgroundRaised
import com.nodenote.theme.PanelBorder
import com.nodenote.theme.TextFaint
import com.nodenote.theme.TextPrimary
import com.nodenote.theme.TextSecondary
import com.nodenote.theme.color
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Below this zoom the map is seen "from across the room": areas show their names big in
 * the middle, and the cards inside them step back, so the territories read first.
 */
internal const val AREA_FAR_ZOOM = 0.5f

/** Tinted territories, under the links and cards (part of the canvas drawing layer). */
internal fun DrawScope.drawAreas(state: AppState) {
    val k = state.zoom * state.viewDensity
    // Biggest first, so an area drawn inside another sits on top of it.
    for (area in state.shownProject.areas.sortedByDescending { it.width * it.height }) {
        val topLeft = Offset(area.x, area.y) * k + state.pan
        val size = Size(area.width * k, area.height * k)
        val tint = Color(area.tint)
        val selected = area.id == state.selectedAreaId
        val radius = CornerRadius(18f * k, 18f * k)
        drawRoundRect(tint.copy(alpha = if (selected) 0.16f else 0.10f), topLeft, size, radius)
        drawRoundRect(
            color = when {
                selected -> Accent
                area.isPrivate -> PrivateAmber.copy(alpha = 0.85f)
                else -> tint.copy(alpha = 0.55f)
            },
            topLeft = topLeft,
            size = size,
            cornerRadius = radius,
            style = Stroke(
                width = (if (selected) 2f else 1.3f) * state.viewDensity,
                pathEffect = if (area.isPrivate) PathEffect.dashPathEffect(floatArrayOf(9f * state.viewDensity, 6f * state.viewDensity)) else null,
            ),
        )
    }
}

/**
 * An area's name: in its top-left corner, or big in the middle when zoomed far out. It's
 * the handle for the whole area: click selects it, double-click renames, dragging moves
 * the area with everything in it.
 */
@Composable
fun AreaLabel(area: Area, state: AppState) {
    val density = LocalDensity.current.density
    val editing = state.editingAreaId == area.id
    val far = state.zoom < AREA_FAR_ZOOM && !editing
    val selected = state.selectedAreaId == area.id
    val tint = Color(area.tint)
    var size by remember { mutableStateOf(IntSize.Zero) }
    val topLeft = state.worldToScreen(Offset(area.x, area.y))
    val bottomRight = state.worldToScreen(Offset(area.right, area.bottom))
    val title = area.title.trim()
    val fontSp = if (far) {
        ((bottomRight.x - topLeft.x) / density / (max(title.length, 6) * 0.62f)).coerceIn(16f, 30f)
    } else {
        (MapSceneBuilder.areaTitleSize(area) * state.zoom).coerceIn(13f, 30f)
    }
    val inset = 10f * density
    val x = if (far) topLeft.x + (bottomRight.x - topLeft.x - size.width) / 2f else topLeft.x + inset
    val y = if (far) topLeft.y + (bottomRight.y - topLeft.y - size.height) / 2f else topLeft.y + inset * 0.6f
    val shape = RoundedCornerShape(6.dp)

    Row(
        Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .onSizeChanged { size = it }
            .clip(shape)
            .background(if (far || selected) PanelBackground.copy(alpha = 0.88f) else Color.Transparent)
            .then(if (selected) Modifier.border(1.dp, Accent, shape) else Modifier)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(state, area.id) {
                detectTapGestures(
                    onTap = { state.selectArea(area.id) },
                    onDoubleTap = { state.startRenamingArea(area.id) },
                )
            }
            // Unscaled on screen, so a drag of N pixels is N / (zoom × density) world units.
            .pointerInput(state, area.id) {
                detectDragGestures(
                    onDragStart = { state.beginAreaDrag(area.id) },
                    onDrag = { change, amount ->
                        change.consume()
                        state.dragArea(amount / (state.zoom * state.viewDensity))
                    },
                    onDragEnd = { state.endAreaDrag() },
                    onDragCancel = { state.endAreaDrag() },
                )
            }
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (area.isPrivate) {
            GlyphIcon(Glyph.Lock, PrivateAmber, (fontSp * 0.62f).dp)
            Spacer(Modifier.width(5.dp))
        }
        if (editing) {
            AreaTitleEditor(state, area, fontSp, tint)
        } else {
            Text(
                title.ifEmpty { "Untitled area" },
                fontSize = fontSp.sp,
                fontWeight = FontWeight.Bold,
                color = if (title.isEmpty()) TextFaint else tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Typing an area's name in place: Enter keeps it, Escape keeps the old one. */
@Composable
private fun AreaTitleEditor(state: AppState, area: Area, fontSp: Float, color: Color) {
    var value by remember { mutableStateOf(TextFieldValue(area.title, TextRange(0, area.title.length))) }
    val focusRequester = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    val appState = LocalAppState.current

    // Report focus so the window-level shortcuts (Delete, Tab, Enter…) pause while typing.
    DisposableEffect(appState) {
        onDispose { if (hadFocus) appState?.let { it.activeTextFields-- } }
    }

    fun finish(title: String?) {
        if (done) return
        done = true
        state.finishAreaTitle(area.id, title)
    }

    BasicTextField(
        value = value,
        onValueChange = { v -> value = v.copy(text = v.text.replace('\n', ' ').replace('\r', ' ')) },
        singleLine = true,
        textStyle = TextStyle(color = color, fontSize = fontSp.sp, fontWeight = FontWeight.Bold),
        cursorBrush = SolidColor(Accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { finish(value.text) }),
        modifier = Modifier
            .widthIn(min = 170.dp, max = 560.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { f ->
                if (f.isFocused && !hadFocus) {
                    hadFocus = true
                    appState?.let { it.activeTextFields++ }
                } else if (!f.isFocused && hadFocus) {
                    hadFocus = false
                    appState?.let { it.activeTextFields-- }
                    finish(value.text) // clicking elsewhere keeps what was typed
                }
            }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.Enter, Key.NumPadEnter -> {
                        finish(value.text)
                        true
                    }
                    Key.Escape -> {
                        finish(null)
                        true
                    }
                    else -> false
                }
            }
            .onKeyEvent { it.key == Key.Delete || it.key == Key.Backspace },
        decorationBox = { inner ->
            Box {
                if (value.text.isEmpty()) Text("Name this area", fontSize = fontSp.sp, fontWeight = FontWeight.Bold, color = TextFaint)
                inner()
            }
        },
    )

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

/**
 * Invisible bands along an area's outline (under the cards): click the border to select
 * the area, double-click to rename it, drag it to move the area with everything in it.
 */
@Composable
fun AreaBorder(area: Area, state: AppState) {
    val density = LocalDensity.current.density
    val topLeft = state.worldToScreen(Offset(area.x, area.y))
    val bottomRight = state.worldToScreen(Offset(area.right, area.bottom))
    val band = 12f * density
    val w = bottomRight.x - topLeft.x
    val h = bottomRight.y - topLeft.y
    val left = topLeft.x - band / 2f
    val top = topLeft.y - band / 2f
    // Top, bottom, left, right.
    BorderBand(area.id, state, left, top, w + band, band)
    BorderBand(area.id, state, left, bottomRight.y - band / 2f, w + band, band)
    BorderBand(area.id, state, left, top, band, h + band)
    BorderBand(area.id, state, bottomRight.x - band / 2f, top, band, h + band)
}

@Composable
private fun BorderBand(areaId: String, state: AppState, x: Float, y: Float, width: Float, height: Float) {
    val density = LocalDensity.current.density
    Box(
        Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .size((width / density).dp, (height / density).dp)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(state, areaId) {
                detectTapGestures(
                    onTap = { state.selectArea(areaId) },
                    onDoubleTap = { state.startRenamingArea(areaId) },
                )
            }
            .pointerInput(state, areaId) {
                detectDragGestures(
                    onDragStart = { state.beginAreaDrag(areaId) },
                    onDrag = { change, amount ->
                        change.consume()
                        state.dragArea(amount / (state.zoom * state.viewDensity))
                    },
                    onDragEnd = { state.endAreaDrag() },
                    onDragCancel = { state.endAreaDrag() },
                )
            },
    )
}

/** The selected area's eight grips, at its corners and the middles of its sides: drag one to resize from there (the cards stay put). */
@Composable
fun AreaGrips(area: Area, state: AppState) {
    val topLeft = state.worldToScreen(Offset(area.x, area.y))
    val bottomRight = state.worldToScreen(Offset(area.right, area.bottom))
    val middle = (topLeft + bottomRight) / 2f
    for (handle in AreaHandle.entries) {
        val x = when {
            handle.left -> topLeft.x
            handle.right -> bottomRight.x
            else -> middle.x
        }
        val y = when {
            handle.top -> topLeft.y
            handle.bottom -> bottomRight.y
            else -> middle.y
        }
        key(handle) { AreaGrip(area.id, handle, Offset(x, y), state) }
    }
}

@Composable
private fun AreaGrip(areaId: String, handle: AreaHandle, at: Offset, state: AppState) {
    val density = LocalDensity.current.density
    val half = 12f * density // a 24dp target around a 10dp square
    Box(
        Modifier
            .offset { IntOffset((at.x - half).roundToInt(), (at.y - half).roundToInt()) }
            .size(24.dp)
            .pointerHoverIcon(PointerIcon.Crosshair)
            .pointerInput(state, areaId, handle) {
                var dragged = Offset.Zero
                detectDragGestures(
                    onDragStart = {
                        dragged = Offset.Zero
                        state.beginAreaResize(areaId)
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        dragged += amount / (state.zoom * state.viewDensity)
                        state.resizeArea(areaId, handle, dragged)
                    },
                    onDragEnd = { state.endAreaResize() },
                    onDragCancel = { state.endAreaResize() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .background(PanelBackground, RoundedCornerShape(2.dp))
                .border(1.5.dp, Accent, RoundedCornerShape(2.dp)),
        )
    }
}

/** The selected area's toolbar, above it: name, color, private, fit, tidy inside, remove. */
@Composable
fun AreaQuickBarOverlay(state: AppState, area: Area) {
    var barSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current.density
    val margin = 6f * density
    val topLeft = state.worldToScreen(Offset(area.x, area.y))
    val gap = 8f * density
    val above = topLeft.y - barSize.height - gap
    // No room above (area at the top of the view): just inside its top edge, under the name.
    val y = if (above >= margin) above else max(margin, topLeft.y + 44f * density)
    val maxX = max(margin, state.canvasSize.width - barSize.width - margin)
    val x = topLeft.x.coerceIn(margin, maxX)
    val maxWidth = ((state.canvasSize.width - 2 * margin) / density).coerceAtLeast(120f).dp
    QuickBarRow(
        Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .widthIn(max = maxWidth)
            .onSizeChanged { barSize = it },
    ) {
        Text("AREA", fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold, color = TextFaint, modifier = Modifier.padding(horizontal = 6.dp))
        BarChip(onClick = { state.startRenamingArea(area.id) }) {
            Text("Rename", fontSize = 11.sp, color = TextPrimary)
        }
        Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            Area.Palette.forEach { c ->
                val current = area.tint == c
                Box(
                    Modifier
                        .size(if (current) 16.dp else 13.dp)
                        .clip(CircleShape)
                        .background(Color(c))
                        .border(if (current) 2.dp else 0.dp, if (current) TextPrimary else Color.Transparent, CircleShape)
                        .clickable { state.setAreaColor(area.id, c) },
                )
            }
        }
        BarChip(onClick = { state.setAreaPrivate(area.id, !area.isPrivate) }, highlight = if (area.isPrivate) PrivateAmber else null) {
            GlyphIcon(Glyph.Lock, if (area.isPrivate) PrivateAmber else TextFaint, 10.dp)
            Spacer(Modifier.width(4.dp))
            Text("Private", fontSize = 11.sp, color = if (area.isPrivate) TextPrimary else TextSecondary)
        }
        BarChip(onClick = { state.fitArea(area.id) }) {
            Text("Fit to cards", fontSize = 11.sp, color = TextPrimary)
        }
        BarChip(onClick = { state.tidyArea(area.id) }) {
            Text("Tidy inside", fontSize = 11.sp, color = TextPrimary)
        }
        BarChip(onClick = { state.removeArea(area.id) }) {
            Text("Remove area", fontSize = 11.sp, color = DangerRed)
        }
    }
}

/** Link types offered first on the link bar: what a connection between two cards usually means. */
private val QuickLinkTypes = listOf("Relates", "Causes", "Solves", "Blocks", "PartOf")

/** The clicked link's toolbar, at its midpoint: what the link means, flip it, delete it. */
@Composable
fun LinkQuickBarOverlay(state: AppState, edge: Edge) {
    val p = state.shownProject
    val from = p.nodes.find { it.id == edge.fromNodeId } ?: return
    val to = p.nodes.find { it.id == edge.toNodeId } ?: return
    var barSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current.density
    val margin = 6f * density
    val mid = (state.worldToScreen(Offset(from.centerX, from.centerY)) + state.worldToScreen(Offset(to.centerX, to.centerY))) / 2f
    val gap = 12f * density
    val above = mid.y - barSize.height - gap
    val y = if (above >= margin) above else mid.y + gap
    val maxX = max(margin, state.canvasSize.width - barSize.width - margin)
    val x = (mid.x - barSize.width / 2f).coerceIn(margin, maxX)
    val maxWidth = ((state.canvasSize.width - 2 * margin) / density).coerceAtLeast(120f).dp
    var moreOpen by remember { mutableStateOf(false) }
    QuickBarRow(
        Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .widthIn(max = maxWidth)
            .onSizeChanged { barSize = it },
    ) {
        Text(
            "${short(from.title)} → ${short(to.title)}",
            fontSize = 10.sp,
            color = TextFaint,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        QuickLinkTypes.forEach { id ->
            val def = state.edgeTypeOf(id)
            val current = edge.type == id
            BarChip(onClick = { state.setLinkType(edge.id, id) }, highlight = if (current) def.color else null) {
                TypeDot(def.color, 7.dp)
                Spacer(Modifier.width(4.dp))
                Text(def.label, fontSize = 11.sp, color = if (current) TextPrimary else TextSecondary, maxLines = 1)
            }
        }
        Box {
            BarChip(onClick = { moreOpen = true }, highlight = if (edge.type !in QuickLinkTypes) state.edgeTypeOf(edge.type).color else null) {
                Text(if (edge.type in QuickLinkTypes) "More" else state.edgeTypeOf(edge.type).label, fontSize = 11.sp, color = TextSecondary)
                Spacer(Modifier.width(3.dp))
                GlyphIcon(Glyph.ChevronDown, TextFaint, 9.dp)
            }
            DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                state.allEdgeTypes.filter { it.id !in QuickLinkTypes }.forEach { t ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TypeDot(t.color, 7.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(t.label, fontSize = 12.sp, color = if (t.id == edge.type) Accent else TextPrimary)
                            }
                        },
                        onClick = {
                            state.setLinkType(edge.id, t.id)
                            moreOpen = false
                        },
                    )
                }
            }
        }
        BarChip(onClick = { state.flipEdge(edge.id) }) {
            Text("Flip", fontSize = 11.sp, color = TextPrimary)
        }
        BarChip(onClick = { state.deleteEdge(edge.id) }) {
            Text("Delete", fontSize = 11.sp, color = DangerRed)
        }
    }
}

private fun short(title: String): String {
    val t = title.trim().ifEmpty { "(untitled)" }
    return if (t.length <= 18) t else t.take(17).trimEnd() + "…"
}

/** Floating toolbar chrome shared by the area and link bars (taps between chips don't reach the canvas). */
@Composable
private fun QuickBarRow(modifier: Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier
            .pointerInput(Unit) { detectTapGestures { } }
            .shadow(6.dp, shape)
            .clip(shape)
            .background(PanelBackground)
            .border(1.dp, PanelBorder, shape)
            .horizontalScroll(rememberScrollState())
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) { content() }
}

/**
 * "Color: Type" in the map's top-right corner: what the card colors mean (type, status,
 * importance, effort, person, or issues with no fix yet). With a lens on, its legend sits
 * underneath, with the way back to type.
 */
@Composable
fun ColorByControl(state: AppState, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val active = state.colorBy != ColorBy.Type
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier
            .pointerInput(Unit) { detectTapGestures { } }
            .clip(shape)
            .background(PanelBackground.copy(alpha = 0.94f))
            .border(1.dp, if (active) Accent.copy(alpha = 0.6f) else PanelBorder, shape)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box {
            Row(
                Modifier.clip(RoundedCornerShape(5.dp)).clickable { open = true }.padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Color: ", fontSize = 11.sp, color = TextFaint)
                Text(state.colorBy.label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (active) Accent else TextPrimary)
                Spacer(Modifier.width(4.dp))
                GlyphIcon(Glyph.ChevronDown, TextFaint, 9.dp)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                ColorBy.entries.forEach { by ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(by.label, fontSize = 12.sp, color = if (by == state.colorBy) Accent else TextPrimary)
                                Text(by.hint, fontSize = 10.sp, color = TextFaint)
                            }
                        },
                        onClick = {
                            state.changeColorBy(by)
                            open = false
                        },
                    )
                }
            }
        }
        if (active) {
            val keys = state.lens.legend(state.shownProject.nodes)
            Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (keys.isEmpty()) Text("Nothing to color yet", fontSize = 11.sp, color = TextFaint)
                keys.forEach { key ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LensSwatch(key.colorArgb)
                        Spacer(Modifier.width(7.dp))
                        Text(key.label, fontSize = 11.sp, color = TextSecondary, maxLines = 1)
                    }
                }
                Text(
                    "Back to type",
                    fontSize = 11.sp,
                    color = Accent,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { state.changeColorBy(ColorBy.Type) }.padding(vertical = 2.dp),
                )
            }
        }
    }
}

/** A tiny card in the lens color (outlined only when the lens leaves those cards plain). */
@Composable
fun LensSwatch(argb: Long, size: Int = 11) {
    val tint = if (argb != 0L) Color(argb) else null
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(tint?.copy(alpha = 0.3f)?.compositeOver(PanelBackgroundRaised) ?: PanelBackgroundRaised)
            .border(1.dp, tint ?: PanelBorder, RoundedCornerShape(3.dp)),
    )
}

/** Shown while "Area" waits for a box to be dragged on the map. */
@Composable
fun DrawingAreaHint(state: AppState, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier
            .clip(shape)
            .background(PanelBackground.copy(alpha = 0.94f))
            .border(1.dp, Accent.copy(alpha = 0.7f), shape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Drag a box on the map for the new area", fontSize = 12.sp, color = TextPrimary)
        Spacer(Modifier.width(12.dp))
        Text(
            "Cancel",
            fontSize = 12.sp,
            color = Accent,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { state.cancelAreaDrawing() }.padding(horizontal = 4.dp),
        )
    }
}
