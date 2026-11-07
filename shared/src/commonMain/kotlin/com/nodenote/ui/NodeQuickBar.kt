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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.nodenote.model.areaOf
import com.nodenote.state.AppState
import com.nodenote.theme.Accent
import com.nodenote.theme.DangerRed
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
 * edge): status, who it's assigned to, and "..." for type, rating, size, private, child,
 * area and delete. What matters while mapping, one click from the card — and the
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
        StatusChip(state, node)
        AssignChip(state, node)
        TypoChip(state, node)
        MoreChip(state, node)
    }
}

/** Who's doing it: their initials once assigned; the menu lists the team. */
@Composable
private fun AssignChip(state: AppState, node: Node) {
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(state) { state.loadTeamNames() }
    Box {
        BarChip(onClick = { open = true }) {
            if (node.owner.isBlank()) {
                Text("Assign", fontSize = 11.sp, color = TextSecondary)
            } else {
                Box(Modifier.size(16.dp).background(authorColor(node.owner), CircleShape), contentAlignment = Alignment.Center) {
                    Text(node.owner.take(1).uppercase(), fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Spacer(Modifier.width(5.dp))
                Text(node.owner, fontSize = 11.sp, color = TextPrimary, maxLines = 1)
            }
            Spacer(Modifier.width(3.dp))
            GlyphIcon(Glyph.ChevronDown, TextFaint, 9.dp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val me = state.session.userName
            val people = (listOf(me) + state.teamNames + state.messages.map { it.author })
                .filter { it.isNotBlank() }.distinctBy { it.lowercase() }
            people.forEach { name ->
                val current = node.owner.equals(name, ignoreCase = true)
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).background(authorColor(name), CircleShape))
                            Spacer(Modifier.width(8.dp))
                            Text(if (name.equals(me, ignoreCase = true)) "$name (me)" else name, fontSize = 12.sp,
                                color = if (current) Accent else TextPrimary)
                        }
                    },
                    onClick = { state.assignNode(node.id, name); open = false },
                )
            }
            if (node.owner.isNotBlank()) {
                HorizontalDivider(color = PanelBorder)
                DropdownMenuItem(text = { Text("Nobody", fontSize = 12.sp, color = TextSecondary) }, onClick = { state.assignNode(node.id, ""); open = false })
            }
        }
    }
}

/** Which of the "..." menus is open. */
private enum class MoreMenu { Main, Type, Rate, Size }

/** "..." on the card's bar: type, rating, size, private, child, area, delete. */
@Composable
private fun MoreChip(state: AppState, node: Node) {
    var open by remember { mutableStateOf<MoreMenu?>(null) }
    val close = { open = null }
    Box {
        BarChip(onClick = { open = MoreMenu.Main }) {
            Text("•••", fontSize = 11.sp, color = TextSecondary)
        }
        DropdownMenu(expanded = open == MoreMenu.Main, onDismissRequest = close) {
            val def = state.nodeTypeOf(node.type)
            MoreRow("Type: ${def.label}") { open = MoreMenu.Type }
            MoreRow(if (node.importance == 0) "Rate importance" else "Importance: ${node.importance}") { open = MoreMenu.Rate }
            MoreRow("Size") { open = MoreMenu.Size }
            HorizontalDivider(color = PanelBorder)
            MoreRow("Add a child card  (Tab)") { close(); state.addChildNode(node.id) }
            val home = state.shownProject.areaOf(node)
            if (home != null) {
                MoreRow("Select its area: ${home.title.ifBlank { "Area" }}") { close(); state.selectArea(home.id) }
            } else {
                MoreRow("Put it in a new area") { close(); state.addAreaAroundBranch(node.id) }
            }
            MoreRow(if (node.isPrivate) "Make it public" else "Make it private") { close(); state.setNodePrivate(node.id, !node.isPrivate) }
            if (state.selectedNodeIds.size > 1) MoreRow("Line up the selected cards") { close(); state.alignSelected() }
            MoreRow("Details and notes") {
                close()
                state.rightTab = AppState.RightTab.Details
                state.inspectorVisible = true
            }
            HorizontalDivider(color = PanelBorder)
            MoreRow("Delete", color = DangerRed) { close(); state.deleteSelected() }
        }
        TypeMenu(state, node, expanded = open == MoreMenu.Type, onDismiss = close)
        RatingMenu(state, node.id, expanded = open == MoreMenu.Rate, onDismiss = close)
        SizeMenu(state, node, expanded = open == MoreMenu.Size, onDismiss = close)
    }
}

@Composable
private fun MoreRow(label: String, color: Color = TextPrimary, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label, fontSize = 12.sp, color = color) }, onClick = onClick)
}

@Composable
internal fun BarChip(onClick: () -> Unit, highlight: Color? = null, content: @Composable RowScope.() -> Unit) {
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
private fun TypeMenu(state: AppState, node: Node, expanded: Boolean, onDismiss: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
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
                    onDismiss()
                },
            )
        }
        HorizontalDivider(color = PanelBorder)
        DropdownMenuItem(
            text = { Text("More types… (Details)", fontSize = 12.sp, color = TextSecondary) },
            onClick = {
                state.rightTab = AppState.RightTab.Details
                state.inspectorVisible = true
                onDismiss()
            },
        )
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
            val everyday = listOf(NodeStatus.Open, NodeStatus.InProgress, NodeStatus.Blocked, NodeStatus.Solved)
            (everyday + listOf(NodeStatus.Parked, NodeStatus.None)).forEachIndexed { i, s ->
                if (i == everyday.size) HorizontalDivider(color = PanelBorder)
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

/** The size presets, each an importance level: a card's size is its importance. */
private val SizePresets = listOf("Small" to 1, "Normal" to 3, "Large" to 7, "Huge" to 10, "Urgent" to 12)

@Composable
private fun SizeMenu(state: AppState, node: Node, expanded: Boolean, onDismiss: () -> Unit) {
    run {
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            SizePresets.forEach { (label, level) ->
                val current = node.importance == level
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(label, fontSize = 12.sp, color = if (current) Accent else TextPrimary, modifier = Modifier.width(58.dp))
                            ImportanceBadge(level, size = 16.dp)
                        }
                    },
                    onClick = {
                        state.setNodeImportance(node.id, level)
                        onDismiss()
                    },
                )
            }
            HorizontalDivider(color = PanelBorder)
            Text(
                "Size sets importance (1-12).\nOr drag the card's bottom-right corner.",
                fontSize = 10.sp,
                color = TextFaint,
                lineHeight = 13.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

/** Importance for one node. */
@Composable
fun RatingMenu(state: AppState, nodeId: String, expanded: Boolean, onDismiss: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        val node = state.project.nodes.find { it.id == nodeId } ?: return@DropdownMenu
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Importance: how much it matters", fontSize = 11.sp, color = TextSecondary)
            ImportancePicker(node.importance, onChange = { state.setNodeImportance(nodeId, it) }, cell = 22.dp)
            Text("1 minor · 10 critical · 11-12 urgent. The card's size follows.", fontSize = 10.sp, color = TextFaint)
        }
    }
}
