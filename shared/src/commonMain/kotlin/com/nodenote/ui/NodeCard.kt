package com.nodenote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodenote.model.Node
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

/**
 * One node on the canvas, rendered as a rounded card.
 *
 * Positioning: the card is laid out at its natural dp size, offset to the node's
 * screen position, then visually scaled by the zoom factor with a top-left
 * transform origin. graphicsLayer also transforms pointer input, so hit testing
 * and drag deltas automatically match what's on screen at any zoom level.
 */
@Composable
fun NodeCard(node: Node, state: AppState) {
    val selected = node.id in state.selectedNodeIds
    val isConnectSource = state.connectFromId == node.id
    val shape = RoundedCornerShape(10.dp)

    Box(
        Modifier
            .offset {
                val screenPos = state.worldToScreen(Offset(node.x, node.y))
                IntOffset(screenPos.x.roundToInt(), screenPos.y.roundToInt())
            }
            .graphicsLayer {
                scaleX = state.zoom
                scaleY = state.zoom
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .size(node.width.dp, node.height.dp)
            .shadow(if (selected) 10.dp else 4.dp, shape)
            .background(PanelBackgroundRaised, shape)
            .border(
                width = if (selected || isConnectSource) 1.5.dp else 1.dp,
                color = when {
                    selected -> Accent
                    isConnectSource -> Accent.copy(alpha = 0.7f)
                    else -> PanelBorder
                },
                shape = shape,
            )
            // Tap: select, or complete a pending connection (see AppState.nodeTapped).
            // Double-tap: edit the title inline.
            .pointerInput(node.id) {
                detectTapGestures(
                    onTap = { state.nodeTapped(node.id) },
                    onDoubleTap = {
                        state.selectOnly(node.id)
                        state.editingNodeId = node.id
                    },
                )
            }
            // Drag: move the whole selection (dragging an unselected card first
            // makes it the selection). The drag delta arrives in the card's local
            // (pre-zoom) pixels, so dividing by density converts it to world units.
            .pointerInput(node.id) {
                detectDragGestures(
                    onDragStart = {
                        state.pushUndo() // one undo step per drag gesture
                        if (node.id !in state.selectedNodeIds) state.selectOnly(node.id)
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        state.moveSelected(dragAmount / state.viewDensity)
                    },
                )
            },
    ) {
        val typeDef = state.nodeTypeOf(node.type)
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 9.dp)) {
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
                if (node.attachments.isNotEmpty()) {
                    Text(
                        "📎${node.attachments.size}",
                        fontSize = 9.sp,
                        color = TextSecondary,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(5.dp))
            if (state.editingNodeId == node.id) {
                InlineTitleEditor(state, node)
            } else {
                Text(
                    node.title.ifBlank { "(untitled)" },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                    lineHeight = 16.sp,
                    maxLines = 2,
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
                .pointerInput(node.id) {
                    detectDragGestures(
                        onDragStart = { state.startConnection(node.id) },
                        onDrag = { change, _ -> change.consume() }, // pointerPos is tracked by the canvas
                        onDragEnd = { state.completeConnectionAtPointer() },
                        onDragCancel = { state.cancelConnection() },
                    )
                },
        )
    }
}

/**
 * The title text field shown in place of the title after double-clicking a card.
 * Starts focused with the whole title selected (rename-style). Edits write
 * straight into the project; Enter or clicking elsewhere finishes, Escape
 * restores the title as it was when editing started.
 */
@Composable
private fun InlineTitleEditor(state: AppState, node: Node) {
    // Fresh per edit session: this composable only exists while editing, so
    // remember {} re-initializes each time editing starts.
    val originalTitle = remember { node.title }
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(node.title, selection = TextRange(0, node.title.length)))
    }
    val focusRequester = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    val appState = LocalAppState.current

    // Report focus so the window-level Delete shortcut pauses while renaming.
    DisposableEffect(appState) {
        onDispose { if (hadFocus) appState?.let { it.activeTextFields-- } }
    }

    BasicTextField(
        value = fieldValue,
        onValueChange = { v ->
            fieldValue = v
            state.updateNode(node.id) { it.copy(title = v.text) }
        },
        singleLine = true,
        textStyle = TextStyle(
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 16.sp,
        ),
        cursorBrush = SolidColor(Accent),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { focusState ->
                if (focusState.isFocused && !hadFocus) {
                    hadFocus = true
                    appState?.let { it.activeTextFields++ }
                } else if (!focusState.isFocused && hadFocus) {
                    hadFocus = false
                    appState?.let { it.activeTextFields-- }
                    // Clicking anywhere else commits the edit.
                    state.editingNodeId = null
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter, Key.NumPadEnter -> {
                        state.editingNodeId = null
                        true
                    }
                    Key.Escape -> {
                        state.updateNode(node.id) { it.copy(title = originalTitle) }
                        state.editingNodeId = null
                        true
                    }
                    else -> false
                }
            }
            // Swallow leftover Delete/Backspace so they never bubble up to the
            // window-level "Delete = delete selected node" shortcut while typing.
            .onKeyEvent { it.key == Key.Delete || it.key == Key.Backspace },
    )

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}
