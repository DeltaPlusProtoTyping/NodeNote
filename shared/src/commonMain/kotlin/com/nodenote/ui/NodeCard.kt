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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodenote.model.Node
import com.nodenote.model.NodeStatus
import com.nodenote.model.NodeTypeDef
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

/**
 * One node on the canvas, rendered as a rounded card.
 *
 * Positioning: the card is laid out at its natural size at scale 1 (width/scale ×
 * height/scale dp), offset to the node's screen position, then visually scaled by
 * zoom × the node's own scale with a top-left transform origin — so a bigger node is
 * the same card, text and all, just larger. graphicsLayer also transforms pointer
 * input, so hit testing and drag deltas match what's on screen at any zoom.
 *
 * The card grows to fit its whole title (see [AppState.fitNodeHeight]).
 */
@Composable
fun NodeCard(node: Node, state: AppState) {
    val selected = node.id in state.selectedNodeIds
    val isConnectSource = state.connectFromId == node.id
    val shape = RoundedCornerShape(10.dp)
    // On-screen size of one card unit.
    val k = state.zoom * node.scale

    Box(
        Modifier
            .offset {
                val screenPos = state.worldToScreen(Offset(node.x, node.y))
                IntOffset(screenPos.x.roundToInt(), screenPos.y.roundToInt())
            }
            .graphicsLayer {
                scaleX = k
                scaleY = k
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .size((node.width / node.scale).dp, (node.height / node.scale).dp)
            .shadow(if (selected) 10.dp else 4.dp, shape)
            .background(PanelBackgroundRaised, shape)
            .border(
                width = if (selected || isConnectSource) 1.5.dp else 1.dp,
                color = when {
                    selected -> Accent
                    isConnectSource -> Accent.copy(alpha = 0.7f)
                    node.isPrivate -> PrivateAmber.copy(alpha = 0.55f)
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
        if (k < FAR_ZOOM && state.editingNodeId != node.id) {
            Row(Modifier.fillMaxSize().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size((10f / state.zoom).coerceAtMost(40f).dp).background(node.status.takeIf { it != NodeStatus.None }?.color ?: typeDef.color, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(
                    node.title.ifBlank { "(untitled)" },
                    fontSize = (9f / state.zoom).coerceAtMost(40f).sp,
                    lineHeight = (10.5f / state.zoom).coerceAtMost(46f).sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
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
            TypeDot(typeDef.color, 7.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                typeDef.label.uppercase(),
                fontSize = 9.sp,
                letterSpacing = 1.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextFaint,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = true),
            )
            if (node.isPrivate) {
                GlyphIcon(Glyph.Lock, PrivateAmber, 9.dp)
                Spacer(Modifier.width(5.dp))
            }
            if (node.status != NodeStatus.None) {
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
            if (node.importance > 0 || node.effort > 0) {
                Spacer(Modifier.width(5.dp))
                CardRating(state, node)
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
                color = if (node.title.isBlank()) TextFaint else TextPrimary,
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

/** Bottom-right handle of the selected card: drag to make the node bigger or smaller (text scales with it). */
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
 * old title (or drops a node that was never named). See [AppState.endTitleEdit].
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
