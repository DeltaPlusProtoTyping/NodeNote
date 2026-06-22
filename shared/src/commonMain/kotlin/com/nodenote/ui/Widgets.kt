package com.nodenote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodenote.model.EdgeCategory
import com.nodenote.state.AppState
import com.nodenote.state.LocalAppState
import com.nodenote.theme.Accent
import com.nodenote.theme.PanelBackground
import com.nodenote.theme.PanelBackgroundRaised
import com.nodenote.theme.PanelBorder
import com.nodenote.theme.TextFaint
import com.nodenote.theme.TextPrimary
import com.nodenote.theme.TextSecondary
import com.nodenote.theme.color

/**
 * Small reusable pieces shared by the panels. These exist mostly because stock
 * Material 3 components are sized for touch; a desktop tool wants tighter rows.
 */

/** Uppercase section caption, e.g. "INSPECTOR". */
@Composable
fun PanelCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        fontSize = 10.sp,
        letterSpacing = 1.2.sp,
        fontWeight = FontWeight.SemiBold,
        color = TextFaint,
    )
}

/** Label above a form field. */
@Composable
fun FieldLabel(text: String) {
    Text(text, fontSize = 10.sp, color = TextFaint, fontWeight = FontWeight.Medium)
}

/** Colored type indicator dot. */
@Composable
fun TypeDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).background(color, CircleShape))
}

/** Compact dark text field (Material text fields are too tall for a tool UI). */
@Composable
fun CompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    minHeight: Dp = 30.dp,
    textStyle: TextStyle = TextStyle(color = TextPrimary, fontSize = 12.sp),
) {
    var focused by remember { mutableStateOf(false) }
    val appState = LocalAppState.current
    // Keep AppState.activeTextFields accurate: the window-level Delete-node
    // shortcut is disabled while any text field holds keyboard focus.
    DisposableEffect(appState) {
        onDispose { if (focused) appState?.let { it.activeTextFields-- } }
    }
    val shape = RoundedCornerShape(6.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = textStyle,
        cursorBrush = SolidColor(Accent),
        modifier = modifier
            .defaultMinSize(minHeight = minHeight)
            .onFocusChanged {
                if (it.isFocused != focused) {
                    appState?.let { s -> s.activeTextFields += if (it.isFocused) 1 else -1 }
                }
                focused = it.isFocused
            }
            .background(PanelBackgroundRaised, shape)
            .border(1.dp, if (focused) Accent else PanelBorder, shape)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(placeholder, fontSize = textStyle.fontSize, color = TextFaint)
                }
                inner()
            }
        },
    )
}

/** Compact text button for the toolbar. */
@Composable
fun ToolbarButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = TextPrimary,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(28.dp),
        shape = RoundedCornerShape(6.dp),
        contentPadding = PaddingValues(horizontal = 10.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = color,
            disabledContentColor = TextFaint.copy(alpha = 0.5f),
        ),
    ) {
        Text(text, fontSize = 12.sp)
    }
}

/** Compact bordered button for panel actions (Material buttons are too tall for tight panel rows). */
@Composable
fun CompactOutlinedButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(26.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, PanelBorder, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 11.sp, color = TextPrimary, modifier = Modifier.padding(horizontal = 8.dp))
    }
}

/** Tiny square button, used for the zoom +/- controls. */
@Composable
fun SmallIconButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, PanelBorder, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 13.sp, color = TextSecondary)
    }
}

/**
 * Draggable divider between a side panel and the canvas. Reports horizontal
 * drag distance in dp (positive = right); highlights on hover so it reads as
 * a grab target.
 */
@Composable
fun VerticalResizeHandle(onDragDp: (Float) -> Unit) {
    val density = LocalDensity.current.density
    var active by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxHeight()
            .width(10.dp)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Enter -> active = true
                            PointerEventType.Exit -> active = false
                            else -> Unit
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDragDp(dragAmount.x / density)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxHeight().width(1.dp).background(if (active) Accent else PanelBorder))
    }
}

/** Horizontal counterpart for the notes panel. Reports vertical drag in dp (positive = down). */
@Composable
fun HorizontalResizeHandle(onDragDp: (Float) -> Unit) {
    val density = LocalDensity.current.density
    var active by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Enter -> active = true
                            PointerEventType.Exit -> active = false
                            else -> Unit
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDragDp(dragAmount.y / density)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(if (active) Accent else PanelBorder))
    }
}

/** Small chevron used in panel headers to collapse the panel. */
@Composable
fun PanelToggleButton(glyph: String, onClick: () -> Unit) {
    Text(
        glyph,
        fontSize = 11.sp,
        color = TextSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** Thin clickable strip shown in place of a collapsed side panel; clicking reopens it. */
@Composable
fun CollapsedSideStrip(glyph: String, onExpand: () -> Unit) {
    Box(
        Modifier
            .fillMaxHeight()
            .width(20.dp)
            .background(PanelBackground)
            .clickable(onClick = onExpand),
        contentAlignment = Alignment.TopCenter,
    ) {
        Text(glyph, fontSize = 11.sp, color = TextSecondary, modifier = Modifier.padding(top = 10.dp))
    }
}

/** Tiny "x" used to remove list rows (properties, connections). */
@Composable
fun RemoveButton(onClick: () -> Unit) {
    Text(
        "✕",
        fontSize = 11.sp,
        color = TextFaint,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
    )
}

/** Small uppercase header inside a type dropdown (e.g. "RECENT"). */
@Composable
private fun TypeMenuHeader(text: String) {
    Text(
        text.uppercase(),
        fontSize = 9.sp,
        color = TextFaint,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 2.dp),
    )
}

/** A coloured-dot + label row in a type dropdown. */
@Composable
private fun TypeMenuRow(label: String, dot: Color, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypeDot(dot, 7.dp)
                Spacer(Modifier.width(8.dp))
                Text(label, fontSize = 12.sp)
            }
        },
        onClick = onClick,
    )
}

/** Compact clickable row used inside a categorized type column. */
@Composable
private fun TypeColumnRow(label: String, dot: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TypeDot(dot, 7.dp)
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 12.sp, color = TextPrimary, maxLines = 1)
    }
}

/** Bordered chip used in a "Recent" row. */
@Composable
private fun TypeChip(label: String, dot: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, PanelBorder, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TypeDot(dot, 6.dp)
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 11.sp, color = TextPrimary, maxLines = 1)
    }
}

/**
 * Connection-type dropdown for the inspector's connection rows. Lists recently
 * used types on top, then all types, then "New type…" to create a custom one.
 */
@Composable
fun EdgeTypeSelector(
    state: AppState,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.edgeTypeOf(selectedId)
    val shape = RoundedCornerShape(6.dp)
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(PanelBackgroundRaised, shape)
                .border(1.dp, PanelBorder, shape)
                .clickable { expanded = true }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TypeDot(selected.color, 6.dp)
            Spacer(Modifier.width(5.dp))
            Text(selected.label, fontSize = 11.sp, color = TextPrimary, modifier = Modifier.weight(1f))
            Text("▾", fontSize = 9.sp, color = TextFaint)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val all = state.allEdgeTypes
            val byCategory = all.groupBy { it.category }

            val recent = state.recentEdgeTypeIds.mapNotNull { id -> all.find { it.id == id } }
            if (recent.isNotEmpty()) {
                TypeMenuHeader("Recent")
                Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    recent.forEach { def -> TypeChip(def.label, def.color) { onSelect(def.id); expanded = false } }
                }
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = PanelBorder)
            }

            // One column per non-empty category, in enum order.
            Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                EdgeCategory.entries.forEach { category ->
                    val items = byCategory[category].orEmpty()
                    if (items.isNotEmpty()) {
                        Column(Modifier.width(132.dp)) {
                            TypeMenuHeader(category.label)
                            items.forEach { def ->
                                TypeColumnRow(def.label, def.color) { onSelect(def.id); expanded = false }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = PanelBorder)
            DropdownMenuItem(
                text = { Text("+ New type…", fontSize = 12.sp, color = Accent) },
                onClick = {
                    expanded = false
                    state.openNewType(AppState.NewTypeKind.Edge) { id -> onSelect(id) }
                },
            )
        }
    }
}

/**
 * Node-type dropdown. With [allowAll] it includes an "All types" entry (the
 * sidebar filter); without it, it's the inspector's type editor and offers
 * "New type…". Recently used types are shown on top.
 */
@Composable
fun TypeSelector(
    state: AppState,
    selectedId: String?,
    allowAll: Boolean,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = selectedId?.let { state.nodeTypeOf(it) }
    val shape = RoundedCornerShape(6.dp)
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(PanelBackgroundRaised, shape)
                .border(1.dp, PanelBorder, shape)
                .clickable { expanded = true }
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected != null) {
                TypeDot(selected.color, 7.dp)
                Spacer(Modifier.width(6.dp))
            }
            Text(selected?.label ?: "All types", fontSize = 12.sp, color = TextPrimary, modifier = Modifier.weight(1f))
            Text("▾", fontSize = 10.sp, color = TextFaint)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (allowAll) {
                DropdownMenuItem(
                    text = { Text("All types", fontSize = 12.sp) },
                    onClick = { onSelect(null); expanded = false },
                )
            }
            val recent = state.recentNodeTypeIds.mapNotNull { id -> state.allNodeTypes.find { it.id == id } }
            if (recent.isNotEmpty()) {
                TypeMenuHeader("Recent")
                recent.forEach { def -> TypeMenuRow(def.label, def.color) { onSelect(def.id); expanded = false } }
                HorizontalDivider(color = PanelBorder)
            }
            state.allNodeTypes.forEach { def -> TypeMenuRow(def.label, def.color) { onSelect(def.id); expanded = false } }
            if (!allowAll) {
                HorizontalDivider(color = PanelBorder)
                DropdownMenuItem(
                    text = { Text("+ New type…", fontSize = 12.sp, color = Accent) },
                    onClick = {
                        expanded = false
                        state.openNewType(AppState.NewTypeKind.Node) { id -> onSelect(id) }
                    },
                )
            }
        }
    }
}
