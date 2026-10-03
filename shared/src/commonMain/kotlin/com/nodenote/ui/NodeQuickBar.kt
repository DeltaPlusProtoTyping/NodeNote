package com.nodenote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodenote.model.Node
import com.nodenote.model.NodeCategory
import com.nodenote.model.NodeStatus
import com.nodenote.model.pick
import com.nodenote.state.AppState
import com.nodenote.theme.Accent
import com.nodenote.theme.PanelBackground
import com.nodenote.theme.PanelBorder
import com.nodenote.theme.TextFaint
import com.nodenote.theme.TextPrimary
import com.nodenote.theme.TextSecondary
import com.nodenote.theme.color
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The selected node's toolbar, floating just above its card (below it near the top
 * edge): type, status, rating, private, size, and "+ Child". Everything the
 * Details tab can do that matters while mapping, one click from the card — and the
 * same on a phone, where there's no right panel next to the map.
 */
@Composable
fun NodeQuickBarOverlay(state: AppState, node: Node) {
    var barSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current.density
    val margin = 6f * density
    val topLeft = state.worldToScreen(Offset(node.x, node.y))
    val bottom = state.worldToScreen(Offset(node.x, node.y + node.height)).y
    val gap = 8f * density
    val above = topLeft.y - barSize.height - gap
    val y = if (above >= margin) above else bottom + gap
    val maxX = max(margin, state.canvasSize.width - barSize.width - margin)
    val x = topLeft.x.coerceIn(margin, maxX)
    val maxWidth = ((state.canvasSize.width - 2 * margin) / density).coerceAtLeast(120f).dp
    NodeQuickBar(
        state,
        node,
        Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .widthIn(max = maxWidth)
            .onSizeChanged { barSize = it },
    )
}

@Composable
private fun NodeQuickBar(state: AppState, node: Node, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier
            // A tap between the chips must not fall through to the canvas (which would deselect).
            .pointerInput(Unit) { detectTapGestures { } }
            .shadow(6.dp, shape)
            .clip(shape)
            .background(PanelBackground)
            .border(1.dp, PanelBorder, shape)
            .horizontalScroll(rememberScrollState())
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        TypeChip(state, node)
        StatusChip(state, node)
        RateChip(state, node)
        BarChip(onClick = { state.setNodePrivate(node.id, !node.isPrivate) }, highlight = if (node.isPrivate) PrivateAmber else null) {
            GlyphIcon(Glyph.Lock, if (node.isPrivate) PrivateAmber else TextFaint, 10.dp)
            Spacer(Modifier.width(4.dp))
            Text("Private", fontSize = 11.sp, color = if (node.isPrivate) TextPrimary else TextSecondary)
        }
        SizeChip(state, node)
        TypoChip(state, node)
        BarChip(onClick = { state.addChildNode(node.id) }) {
            GlyphIcon(Glyph.Plus, Accent, 10.dp)
            Spacer(Modifier.width(3.dp))
            Text("Child", fontSize = 11.sp, color = TextPrimary)
            Text("  Tab", fontSize = 9.sp, color = TextFaint)
        }
    }
}

@Composable
private fun BarChip(onClick: () -> Unit, highlight: Color? = null, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .height(26.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(highlight?.copy(alpha = 0.16f) ?: Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun TypeChip(state: AppState, node: Node) {
    var open by remember { mutableStateOf(false) }
    val def = state.nodeTypeOf(node.type)
    Box {
        BarChip(onClick = { open = true }) {
            TypeDot(def.color, 8.dp)
            Spacer(Modifier.width(5.dp))
            Text(def.label, fontSize = 11.sp, color = TextPrimary, maxLines = 1)
            Spacer(Modifier.width(3.dp))
            GlyphIcon(Glyph.ChevronDown, TextFaint, 9.dp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // The work types first (what boards are made of), then whatever else this board uses.
            val work = state.allNodeTypes.filter { it.category == NodeCategory.Work }
            val recent = state.recentNodeTypeIds.mapNotNull { id -> state.allNodeTypes.find { it.id == id } }.filter { it !in work }
            val custom = state.allNodeTypes.filter { it.category == NodeCategory.Custom }.filter { it !in recent }
            (work + recent + custom).forEach { t ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TypeDot(t.color, 8.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(t.label, fontSize = 12.sp, color = if (t.id == node.type) Accent else TextPrimary)
                        }
                    },
                    onClick = {
                        if (t.id != node.type) {
                            state.pushUndo()
                            state.setNodeType(node.id, t.id)
                        }
                        open = false
                    },
                )
            }
            HorizontalDivider(color = PanelBorder)
            DropdownMenuItem(
                text = { Text("More types… (Details)", fontSize = 12.sp, color = TextSecondary) },
                onClick = {
                    state.rightTab = AppState.RightTab.Details
                    state.inspectorVisible = true
                    open = false
                },
            )
        }
    }
}

@Composable
private fun StatusChip(state: AppState, node: Node) {
    var open by remember { mutableStateOf(false) }
    Box {
        BarChip(onClick = { open = true }) {
            if (node.status == NodeStatus.None) {
                Text("Status", fontSize = 11.sp, color = TextSecondary)
            } else {
                Box(Modifier.size(7.dp).background(node.status.color, RoundedCornerShape(4.dp)))
                Spacer(Modifier.width(5.dp))
                Text(node.status.label, fontSize = 11.sp, color = TextPrimary, maxLines = 1)
            }
            Spacer(Modifier.width(3.dp))
            GlyphIcon(Glyph.ChevronDown, TextFaint, 9.dp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            NodeStatus.entries.forEach { s ->
                DropdownMenuItem(
                    text = { StatusPill(s, filled = s == node.status) },
                    onClick = {
                        state.setNodeStatus(node.id, s)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun RateChip(state: AppState, node: Node) {
    var open by remember { mutableStateOf(false) }
    Box {
        BarChip(onClick = { open = true }, highlight = if (node.importance == 0 && node.effort == 0) Accent else null) {
            if (node.importance == 0 && node.effort == 0) {
                Text("Rate", fontSize = 11.sp, color = TextPrimary)
            } else {
                ImportanceBadge(node.importance, size = 16.dp, showUnrated = true)
                Spacer(Modifier.width(3.dp))
                if (node.effort > 0) EffortBadge(node.effort, height = 16.dp) else Text("E?", fontSize = 10.sp, color = TextFaint)
            }
        }
        RatingMenu(state, node.id, expanded = open, onDismiss = { open = false })
    }
}

/** Shown when the title has a misspelled word: pick the fix (or teach the dictionary the word). */
@Composable
private fun TypoChip(state: AppState, node: Node) {
    val typos = remember(node.title, state.spell) { state.typosIn(node.title) }
    if (typos.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        BarChip(onClick = { open = true }, highlight = TypoRed) {
            Text("Typo? ", fontSize = 11.sp, color = TextSecondary)
            Text(typos.first().word, fontSize = 11.sp, color = TypoRed, maxLines = 1)
            Spacer(Modifier.width(3.dp))
            GlyphIcon(Glyph.ChevronDown, TextFaint, 9.dp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val spell = state.spell
            typos.take(4).forEachIndexed { i, typo ->
                if (i > 0) HorizontalDivider(color = PanelBorder)
                Text(typo.word, fontSize = 11.sp, color = TypoRed, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                spell?.suggest(typo.word, 3).orEmpty().forEach { fix ->
                    DropdownMenuItem(
                        text = { Text(fix, fontSize = 12.sp, color = TextPrimary) },
                        onClick = {
                            state.fixTitleTypo(node.id, typo, fix)
                            open = false
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("It's spelled right: add to dictionary", fontSize = 12.sp, color = TextSecondary) },
                    onClick = {
                        state.addToDictionary(typo.word)
                        open = false
                    },
                )
            }
        }
    }
}

private val SizePresets = listOf("Small" to 0.8f, "Normal" to 1f, "Large" to 1.5f, "Huge" to 2.2f)

@Composable
private fun SizeChip(state: AppState, node: Node) {
    var open by remember { mutableStateOf(false) }
    Box {
        BarChip(onClick = { open = true }) {
            Text("Size", fontSize = 11.sp, color = TextSecondary)
            Spacer(Modifier.width(3.dp))
            GlyphIcon(Glyph.ChevronDown, TextFaint, 9.dp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SizePresets.forEach { (label, scale) ->
                val current = kotlin.math.abs(node.scale - scale) < 0.05f
                DropdownMenuItem(
                    text = { Text(label, fontSize = 12.sp, color = if (current) Accent else TextPrimary) },
                    onClick = {
                        state.setNodeScale(node.id, scale)
                        open = false
                    },
                )
            }
            HorizontalDivider(color = PanelBorder)
            Text(
                "Or drag the card's bottom-right corner",
                fontSize = 10.sp,
                color = TextFaint,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

/** Importance and effort for one node, side by side; stays open so both can be set. */
@Composable
fun RatingMenu(state: AppState, nodeId: String, expanded: Boolean, onDismiss: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        val node = state.project.nodes.find { it.id == nodeId } ?: return@DropdownMenu
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Importance: how much it matters", fontSize = 11.sp, color = TextSecondary)
            ImportancePicker(node.importance, onChange = { state.setNodeImportance(nodeId, it) }, cell = 22.dp)
            Spacer(Modifier.height(2.dp))
            Text("Effort: how big a job", fontSize = 11.sp, color = TextSecondary)
            EffortPicker(node.effort, onChange = { state.setNodeEffort(nodeId, it) }, cell = 22.dp)
            Text("1 an afternoon · 5 a few weeks · 10 a major project", fontSize = 10.sp, color = TextFaint)
            node.pick?.let { pick ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(pick.label.removeSuffix("s"), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = pickColor(pick))
                    Text(": ${pick.hint}", fontSize = 11.sp, color = TextSecondary)
                }
            }
        }
    }
}

fun pickColor(pick: com.nodenote.model.Pick): Color = when (pick) {
    com.nodenote.model.Pick.QuickWin -> Color(0xFF53C27C)
    com.nodenote.model.Pick.BigBet -> Color(0xFF5B9CFF)
    com.nodenote.model.Pick.FillIn -> Color(0xFFE5B25B)
    com.nodenote.model.Pick.Rethink -> Color(0xFF9AA5B1)
}
