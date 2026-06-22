package com.nodenote.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodenote.model.CanvasElement
import com.nodenote.model.ElementKind
import com.nodenote.state.AppState
import com.nodenote.state.LocalAppState
import com.nodenote.storage.decodeImageOrNull
import com.nodenote.theme.Accent
import com.nodenote.theme.PanelBorder
import com.nodenote.theme.TextFaint
import com.nodenote.theme.TextPrimary
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.roundToInt

/**
 * A free-floating canvas element (text block or image). Positioned/scaled
 * exactly like NodeCard; selection, group-drag and deletion share the same
 * selection set as nodes.
 */
@OptIn(ExperimentalEncodingApi::class)
@Composable
fun ElementView(element: CanvasElement, state: AppState) {
    val selected = element.id in state.selectedNodeIds
    val shape = RoundedCornerShape(6.dp)

    Box(
        Modifier
            .offset {
                val screenPos = state.worldToScreen(Offset(element.x, element.y))
                IntOffset(screenPos.x.roundToInt(), screenPos.y.roundToInt())
            }
            .graphicsLayer {
                scaleX = state.zoom
                scaleY = state.zoom
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .size(element.width.dp, element.height.dp)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = when {
                    selected -> Accent
                    // Text blocks are chromeless until selected; images get a whisper of a border.
                    element.kind == ElementKind.Image -> PanelBorder.copy(alpha = 0.6f)
                    else -> Color.Transparent
                },
                shape = shape,
            )
            // Keyed on [state] too so the handler rebinds if the rendered document changes.
            .pointerInput(state, element.id) {
                detectTapGestures(
                    onTap = { state.nodeTapped(element.id) },
                    onDoubleTap = {
                        if (element.kind == ElementKind.Text) {
                            state.selectOnly(element.id)
                            state.editingElementId = element.id
                        }
                    },
                )
            }
            .pointerInput(state, element.id) {
                detectDragGestures(
                    onDragStart = {
                        state.pushUndo()
                        if (element.id !in state.selectedNodeIds) state.selectOnly(element.id)
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        state.moveSelected(dragAmount / state.viewDensity)
                    },
                )
            },
    ) {
        when (element.kind) {
            ElementKind.Text -> {
                if (state.editingElementId == element.id) {
                    TextElementEditor(state, element)
                } else {
                    Text(
                        element.text.ifBlank { "Double-click to edit text" },
                        fontSize = element.fontSize.sp,
                        lineHeight = (element.fontSize * 1.35f).sp,
                        color = if (element.text.isBlank()) TextFaint else TextPrimary,
                        modifier = Modifier.fillMaxSize().padding(6.dp),
                    )
                }
            }
            ElementKind.Image -> {
                val bitmap = remember(element.id, element.content.length) {
                    runCatching { decodeImageOrNull(Base64.decode(element.content)) }.getOrNull()
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = element.text,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("(image could not be decoded)", fontSize = 10.sp, color = TextFaint)
                    }
                }
            }
        }
    }
}

/** In-place multiline editor for a text element; Escape or clicking away finishes. */
@Composable
private fun TextElementEditor(state: AppState, element: CanvasElement) {
    val focusRequester = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    val appState = LocalAppState.current

    DisposableEffect(appState) {
        onDispose { if (hadFocus) appState?.let { it.activeTextFields-- } }
    }

    BasicTextField(
        value = element.text,
        onValueChange = { v -> state.updateElement(element.id) { it.copy(text = v) } },
        textStyle = TextStyle(
            color = TextPrimary,
            fontSize = element.fontSize.sp,
            lineHeight = (element.fontSize * 1.35f).sp,
        ),
        cursorBrush = SolidColor(Accent),
        modifier = Modifier
            .fillMaxSize()
            .padding(6.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { focusState ->
                if (focusState.isFocused && !hadFocus) {
                    hadFocus = true
                    appState?.let { it.activeTextFields++ }
                } else if (!focusState.isFocused && hadFocus) {
                    hadFocus = false
                    appState?.let { it.activeTextFields-- }
                    state.editingElementId = null
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    state.editingElementId = null
                    true
                } else {
                    false
                }
            }
            .onKeyEvent { it.key == Key.Delete || it.key == Key.Backspace },
    )

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}
