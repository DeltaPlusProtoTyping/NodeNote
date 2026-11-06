package com.nodenote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodenote.model.Node
import com.nodenote.model.NodeStatus
import com.nodenote.model.NodeTypeDef
import com.nodenote.model.areaOf
import com.nodenote.state.AppState
import com.nodenote.state.LocalAppState
import com.nodenote.theme.Accent
import com.nodenote.theme.PanelBackgroundRaised
import com.nodenote.theme.PanelBorder
import com.nodenote.theme.TextFaint
import com.nodenote.theme.TextPrimary
import com.nodenote.theme.TextSecondary
import com.nodenote.theme.color
import kotlin.math.roundToInt

/** Marks private nodes (lock + label on the card, the Private chip). */
val PrivateAmber = Color(0xFFE5B25B)
private val DoneGreen = Color(NodeStatus.Solved.colorArgb)

/**
 * One node on the canvas, rendered as a rounded card.
 *
 * Positioning: the card is laid out at its natural size at scale 1 (width/scale ×
 * height/scale dp), offset to the node's screen position, then visually scaled by
 * zoom × the node's own scale with a top-left transform origin — so a bigger node is
 * the same card, text and all, just larger. graphicsLayer also transforms pointer
 * input, so hit testing and drag deltas match what's on screen at any zoom.
 *
 * The card grows to fit its whole title (see [AppState.fitNodeHeight]). Under a "Color by"
 * other than type, the whole card takes the lens color; zoomed far out, cards inside an
 * area step back so the area names read first.
 */
@Composable
fun NodeCard(node: Node, state: AppState) {
    val selected = node.id in state.selectedNodeIds
    val isConnectSource = state.connectFromId == node.id
    val shape = RoundedCornerShape(10.dp)
    // On-screen size of one card unit.
    val k = state.zoom * node.scale
    val tint = state.lens.colorOf(node)?.let { Color(it) }
    val receded = state.zoom < AREA_FAR_ZOOM && !selected && state.shownProject.areaOf(node) != null
    // The overview draws the card larger around its centre, for the whole title, as far as the
    // cards around it leave room; nothing saved changes.
    val overview = k < FAR_ZOOM && state.editingNodeId != node.id
    val nodes = state.shownProject.nodes
    val grow = if (overview) remember(node.x, node.y, node.width, node.height, nodes) { overviewGrowth(node, nodes) } else Offset.Zero
    val drawnWidth = (node.width + grow.x * 2f) / node.scale
    val drawnHeight = (node.height + grow.y * 2f) / node.scale

    Box(
        Modifier
            .offset {
                val screenPos = state.worldToScreen(Offset(node.x, node.y))
                IntOffset(
                    (screenPos.x - grow.x * state.zoom * density).roundToInt(),
                    (screenPos.y - grow.y * state.zoom * density).roundToInt(),
                )
            }
            .graphicsLayer {
                scaleX = k
                scaleY = k
                transformOrigin = TransformOrigin(0f, 0f)
                alpha = if (receded) 0.5f else 1f
            }
            .size(drawnWidth.dp, drawnHeight.dp)
            .shadow(if (selected) 10.dp else 4.dp, shape)
            .background(tint?.copy(alpha = 0.22f)?.compositeOver(PanelBackgroundRaised) ?: PanelBackgroundRaised, shape)
            .border(
                width = if (selected || isConnectSource || tint != null) 1.5.dp else 1.dp,
                color = when {
                    selected -> Accent
                    isConnectSource -> Accent.copy(alpha = 0.7f)
                    node.isPrivate -> PrivateAmber.copy(alpha = 0.55f)
                    tint != null -> tint.copy(alpha = 0.85f)
                    else -> PanelBorder
                },
                shape = shape,
            )
            // Tap: select, or complete a pending connection (see AppState.nodeTapped).
            // Double-tap: edit the title inline.
            // Keyed on [state] too: a different tab can reuse the same node id, so
            // the handler must rebind to the document actually being rendered.
            .pointerInput(state, node.id) {
                detectTapGestures(
                    // Alt+click starts a connection here; clicking another node finishes it.
                    onTap = {
                        if (state.altDown && state.connectFromId == null) state.startConnection(node.id) else state.nodeTapped(node.id)
                    },
                    onDoubleTap = { state.startRenaming(node.id) },
                )
            }
            // Drag: move the whole selection (dragging an unselected card first
            // makes it the selection). The drag delta arrives in the card's local
            // pixels (before zoom and the card's scale), hence × scale ÷ density.
            .pointerInput(state, node.id) {
                // Alt+drag draws a connection instead: release over another node to connect.
                var connecting = false
                detectDragGestures(
                    onDragStart = {
                        connecting = state.altDown
                        if (connecting) {
                            state.startConnection(node.id)
                        } else {
                            state.pushUndo() // one undo step per drag gesture
                            if (node.id !in state.selectedNodeIds) state.selectOnly(node.id)
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val scale = state.project.nodes.find { it.id == node.id }?.scale ?: 1f
                        if (!connecting) state.moveSelected(dragAmount * scale / state.viewDensity)
                    },
                    onDragEnd = { if (connecting) state.completeConnectionAtPointer() },
                    onDragCancel = { if (connecting) state.cancelConnection() },
                )
            },
    ) {
        val typeDef = state.nodeTypeOf(node.type)
        // Zoomed far out (a wide board on a phone): drop the details and blow the
        // title up so it stays readable (~9sp × the card's scale on screen, so big
        // nodes stay big).
        if (overview) {
            val title = node.title.ifBlank { "(untitled)" }
            // The whole title, always: as large as the overview wants, or smaller if it needs the room.
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val fit = remember(title, drawnWidth, drawnHeight, density.density) {
                largestFontThatFits(measurer, density, title, drawnWidth, drawnHeight)
            }
            val fontSize = minOf((FAR_TEXT / state.zoom).coerceAtMost(40f), fit)
            Row(Modifier.fillMaxSize().padding(FAR_PADDING.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size((fontSize * FAR_DOT).dp).background(tint ?: node.status.takeIf { it != NodeStatus.None }?.color ?: typeDef.color, CircleShape))
                Spacer(Modifier.width((fontSize * FAR_DOT_GAP).dp))
                Text(title, style = overviewTitleStyle(fontSize))
            }
            return@Box
        }

        val showGrip = selected && state.selectedNodeIds.size == 1 && state.editingNodeId != node.id
        CardContent(state, node, typeDef)

        // Chat indicator: message count, accent-filled when someone else posted since I last looked.
        val stats = state.threadStats[node.id]
        val count = stats?.count ?: 0
        val unread = stats?.unread ?: 0
        if (count > 0 || unread > 0) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = if (showGrip) (-20).dp else (-8).dp, y = (-6).dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (unread > 0) Accent else PanelBorder)
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            ) {
                Text(
                    if (unread > 0) "$unread new" else "$count msg",
                    style = tightText(8.sp, FontWeight.SemiBold, if (unread > 0) Color.White else TextSecondary),
                )
            }
        }

        // Connection port: drag from this dot onto another node to create an
        // edge (the dashed preview line follows the cursor). Sits on the card's
        // right edge; its own drag handler wins over the card-move handler.
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .offset(x = (-2).dp)
                .size(9.dp)
                .background(
                    if (selected || isConnectSource) Accent else PanelBorder,
                    CircleShape,
                )
                .border(1.dp, PanelBackgroundRaised, CircleShape)
                .pointerInput(state, node.id) {
                    detectDragGestures(
                        onDragStart = { state.startConnection(node.id) },
                        onDrag = { change, _ -> change.consume() }, // pointerPos is tracked by the canvas
                        onDragEnd = { state.completeConnectionAtPointer() },
                        onDragCancel = { state.cancelConnection() },
                    )
                },
        )

        if (showGrip) ResizeGrip(state, node)
    }
}

/** Below this on-screen scale, cards switch to the big-title "overview" rendering. */
private const val FAR_ZOOM = 0.55f

/** How much larger the overview draws a card than its real size, with room around it. */
private const val FAR_GROW_WIDTH = 1.25f
private const val FAR_GROW_HEIGHT = 1.5f

/** World units to add on each side of [node] in the overview: half the gap to a neighbour at most, as it grows too. */
private fun overviewGrowth(node: Node, nodes: List<Node>): Offset {
    var x = node.width * (FAR_GROW_WIDTH - 1f) / 2f
    var y = node.height * (FAR_GROW_HEIGHT - 1f) / 2f
    for (other in nodes) {
        if (other.id == node.id) continue
        val gapX = maxOf(other.x - (node.x + node.width), node.x - (other.x + other.width))
        val gapY = maxOf(other.y - (node.y + node.height), node.y - (other.y + other.height))
        if (gapY < 0f) x = minOf(x, gapX.coerceAtLeast(0f) / 2f)
        if (gapX < 0f) y = minOf(y, gapY.coerceAtLeast(0f) / 2f)
    }
    return Offset(x, y)
}

/** The overview title's size on screen when it has the room, before the card's own scale. */
private const val FAR_TEXT = 7.5f
private const val FAR_PADDING = 6f
private const val FAR_DOT = 0.6f
private const val FAR_DOT_GAP = 0.35f

private fun overviewTitleStyle(fontSize: Float) = TextStyle(
    fontSize = fontSize.sp,
    lineHeight = (fontSize * 1.15f).sp,
    fontWeight = FontWeight.SemiBold,
    color = TextPrimary,
)

/** The largest overview font (card units) at which all of [title] fits a card drawn at this size. */
private fun largestFontThatFits(measurer: TextMeasurer, density: Density, title: String, width: Float, height: Float): Float {
    var font = 40f
    while (font > 4f) {
        val textWidth = width - FAR_PADDING * 2 - font * (FAR_DOT + FAR_DOT_GAP)
        if (textWidth > 0f) {
            val measured = measurer.measure(
                title,
                overviewTitleStyle(font),
                constraints = Constraints(maxWidth = (textWidth * density.density).toInt()),
                density = density,
            )
            if (measured.size.height <= (height - FAR_PADDING * 2) * density.density) return font
        }
        font *= 0.92f
    }
    return font
}

/** Header (type, private, status, ratings), the full title, and a one-line description. Reports its height so the card fits it. */
@Composable
private fun CardContent(state: AppState, node: Node, typeDef: NodeTypeDef) {
    val density = LocalDensity.current.density
    var contentHeight by remember(node.id) { mutableStateOf(0f) }
    val stats = state.threadStats[node.id]
    // Room for the "N msg" tag in the bottom corner.
    val reserve = if ((stats?.count ?: 0) > 0 || (stats?.unread ?: 0) > 0) 12f else 0f
    LaunchedEffect(contentHeight, reserve, node.height, node.scale) {
        if (contentHeight > 0f) state.fitNodeHeight(node.id, contentHeight + reserve)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .wrapContentHeight(Alignment.Top, unbounded = true)
            .onSizeChanged { contentHeight = it.height / density }
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DoneBox(node.status == NodeStatus.Solved) { state.toggleNodeDone(node.id) }
            Spacer(Modifier.width(6.dp))
            TypeDot(typeDef.color, 7.dp)
            Spacer(Modifier.weight(1f))
            if (node.isPrivate) {
                GlyphIcon(Glyph.Lock, PrivateAmber, 9.dp)
                Spacer(Modifier.width(5.dp))
            }
            // Open is the usual case and Solved is the check, so only the states that need attention get words.
            if (node.status == NodeStatus.InProgress || node.status == NodeStatus.Blocked || node.status == NodeStatus.Parked) {
                Box(Modifier.size(6.dp).background(node.status.color, CircleShape))
                Spacer(Modifier.width(3.dp))
                Text(
                    node.status.label.uppercase(),
                    fontSize = 8.sp,
                    letterSpacing = 0.6.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = node.status.color,
                    maxLines = 1,
                )
            }
            // The rating shows on the selected card; the card's size already says how much it matters.
            if ((node.importance > 0 || node.effort > 0) && node.id in state.selectedNodeIds) {
                Spacer(Modifier.width(5.dp))
                CardRating(state, node)
            }
            if (node.owner.isNotBlank()) {
                Spacer(Modifier.width(5.dp))
                AssigneeBadge(node.owner)
            }
        }
        Spacer(Modifier.height(5.dp))
        if (state.editingNodeId == node.id) {
            InlineTitleEditor(state, node, typeDef.label)
        } else {
            Text(
                node.title.ifBlank { "(untitled)" },
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (node.title.isBlank() || node.status == NodeStatus.Solved) TextFaint else TextPrimary,
                textDecoration = if (node.status == NodeStatus.Solved) TextDecoration.LineThrough else null,
                lineHeight = 16.sp,
                maxLines = 12,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (node.description.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                node.description,
                fontSize = 10.sp,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The card's done box: click it to tick the card off (Solved), click again to reopen it. */
@Composable
private fun DoneBox(done: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(3.dp)
    Box(
        Modifier
            .size(12.dp)
            .clip(shape)
            .background(if (done) DoneGreen else Color.Transparent, shape)
            .border(1.dp, if (done) DoneGreen else TextFaint, shape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        if (done) GlyphIcon(Glyph.Check, Color.White, 10.dp)
    }
}

/** Who the card is assigned to: their initials in their person color. */
@Composable
private fun AssigneeBadge(name: String) {
    val initials = name.trim().split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }
    Box(Modifier.size(15.dp).background(authorColor(name), CircleShape), contentAlignment = Alignment.Center) {
        Text(initials, fontSize = 7.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
    }
}

/** Importance and effort tags on the card; click them to re-rate. */
@Composable
private fun CardRating(state: AppState, node: Node) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(4.dp)).clickable { open = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (node.importance > 0) ImportanceBadge(node.importance, size = 15.dp)
            if (node.importance > 0 && node.effort > 0) Spacer(Modifier.width(3.dp))
            EffortBadge(node.effort)
        }
        RatingMenu(state, node.id, expanded = open, onDismiss = { open = false })
    }
}

/** Bottom-right handle of the selected card: drag to make it bigger or smaller, which sets its importance (text scales with it). */
@Composable
private fun BoxScope.ResizeGrip(state: AppState, node: Node) {
    Box(
        Modifier
            .align(Alignment.BottomEnd)
            .size(16.dp)
            .pointerInput(state, node.id) {
                var startScale = 1f
                var startPointer = Offset.Zero
                var startW = 1f
                var startH = 1f
                detectDragGestures(
                    onDragStart = {
                        val n = state.project.nodes.find { it.id == node.id } ?: return@detectDragGestures
                        // Measured on screen: the card's own coordinates change as it scales.
                        val px = state.zoom * state.viewDensity
                        startScale = n.scale
                        startPointer = state.pointerPos
                        startW = n.width * px
                        startH = n.height * px
                        state.pushUndo()
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val d = state.pointerPos - startPointer
                        val ratio = maxOf((startW + d.x) / startW, (startH + d.y) / startH)
                        state.setNodeScale(node.id, startScale * ratio, pushUndoStep = false)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(Glyph.Grip, TextSecondary, 11.dp)
    }
}

/**
 * The title text field shown in place of the title while editing (double-click,
 * F2, or right after adding a node). Edits write straight into the project.
 * Enter finishes — after adding a child it also starts the next sibling; Tab
 * finishes and adds a child; Shift+Tab steps back out a level; Escape restores the
 * old title (or drops a node that was never named). Clicking away keeps a new node.
 * See [AppState.endTitleEdit].
 */
@Composable
private fun InlineTitleEditor(state: AppState, node: Node, typeLabel: String) {
    // Fresh per edit session: this composable only exists while editing, so
    // remember {} re-initializes each time editing starts.
    val originalTitle = remember { node.title }
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(node.title, selection = TextRange(0, node.title.length)))
    }
    val focusRequester = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    val appState = LocalAppState.current
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // Typos get a squiggle (fixes are on the node's toolbar); the word being typed is left alone.
    val typos = remember(fieldValue.text, fieldValue.selection, state.spell) {
        state.typosIn(fieldValue.text).filterNot { it.end == fieldValue.selection.start }
    }

    // Report focus so the window-level shortcuts (Delete, Tab, Enter…) pause while renaming.
    DisposableEffect(appState) {
        onDispose { if (hadFocus) appState?.let { it.activeTextFields-- } }
    }

    fun end(how: AppState.TitleEditEnd) = state.endTitleEdit(node.id, how, originalTitle)

    BasicTextField(
        value = fieldValue,
        onValueChange = { v ->
            // A phone keyboard's Enter arrives as a typed line break, not a key event: treat it as Enter.
            val typedEnter = v.text.length == fieldValue.text.length + 1 && '\n' in v.text
            // A title is one line; a pasted line break becomes a space.
            val text = if (typedEnter) v.text.replace("\n", "") else v.text.replace('\n', ' ').replace('\r', ' ')
            val changed = text != fieldValue.text
            fieldValue = if (typedEnter) v.copy(text = text, selection = TextRange(text.length)) else v.copy(text = text)
            if (changed) {
                state.titleEditedLocally(node.id)
                state.updateNode(node.id) { it.copy(title = text) }
            }
            if (typedEnter) end(AppState.TitleEditEnd.Enter)
        },
        textStyle = TextStyle(
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 16.sp,
        ),
        cursorBrush = SolidColor(Accent),
        onTextLayout = { layout = it },
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind { drawTypoSquiggles(layout, typos, fieldValue.text.length) }
            .focusRequester(focusRequester)
            .onFocusChanged { focusState ->
                if (focusState.isFocused && !hadFocus) {
                    hadFocus = true
                    appState?.let { it.activeTextFields++ }
                } else if (!focusState.isFocused && hadFocus) {
                    hadFocus = false
                    appState?.let { it.activeTextFields-- }
                    // Clicking anywhere else commits the edit.
                    end(AppState.TitleEditEnd.Blur)
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter, Key.NumPadEnter -> {
                        end(AppState.TitleEditEnd.Enter)
                        true
                    }
                    Key.Tab -> {
                        end(if (event.isShiftPressed) AppState.TitleEditEnd.ShiftTab else AppState.TitleEditEnd.Tab)
                        true
                    }
                    Key.Escape -> {
                        end(AppState.TitleEditEnd.Escape)
                        true
                    }
                    else -> false
                }
            }
            // Swallow leftover Delete/Backspace so they never bubble up to the
            // window-level "Delete = delete selected node" shortcut while typing.
            .onKeyEvent { it.key == Key.Delete || it.key == Key.Backspace },
        decorationBox = { inner ->
            Box {
                if (fieldValue.text.isEmpty()) {
                    Text("New $typeLabel: type a title", fontSize = 13.sp, color = TextFaint, lineHeight = 16.sp)
                }
                inner()
            }
        },
    )

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}
