package com.nodenote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodenote.model.Message
import com.nodenote.model.MessageKind
import com.nodenote.model.Node
import com.nodenote.model.NodeStatus
import com.nodenote.model.isOpenWork
import com.nodenote.state.AppState
import com.nodenote.theme.Accent
import com.nodenote.theme.PanelBorder
import com.nodenote.theme.TextFaint
import com.nodenote.theme.TextPrimary
import com.nodenote.theme.TextSecondary
import com.nodenote.theme.color

/**
 * The Priorities tab: what to work on (open work, most important first), what got done (solved nodes, dated —
 * the list that earns trust), and the key details from the chat.
 */
@Composable
fun PrioritiesView(state: AppState, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AppState.PrioritySection.entries.forEach { s ->
                FilterChip(s.label, selected = state.prioritySection == s, color = Accent) { state.prioritySection = s }
            }
        }
        HorizontalDivider(color = PanelBorder)
        when (state.prioritySection) {
            AppState.PrioritySection.OpenWork -> OpenWorkView(state, Modifier.fillMaxSize())
            AppState.PrioritySection.Wins -> WinsView(state, Modifier.fillMaxSize())
            AppState.PrioritySection.KeyDetails -> KeyDetailsView(state, Modifier.fillMaxSize())
        }
    }
}

// ---- Open work ----

/** Work-item types that count as open work even with no status set. */
private val WorkTypes = setOf("Issue", "Improvement", "Idea", "ActionItem", "Project", "Decision")

private fun openWork(state: AppState): List<Node> = state.shownProject.nodes.filter {
    it.isOpenWork && (it.status.isActive || it.type in WorkTypes || it.importance > 0)
}

@Composable
private fun OpenWorkView(state: AppState, modifier: Modifier) {
    val (rated, unrated) = openWork(state).partition { it.importance > 0 }
    val ranked = rated.sortedWith(compareByDescending<Node> { it.importance }.thenBy { it.title.lowercase() })
    val clipboard = LocalClipboardManager.current

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Most important first.", fontSize = 11.sp, color = TextFaint, modifier = Modifier.weight(1f))
            if (ranked.isNotEmpty()) {
                CompactOutlinedButton("Copy as list", onClick = {
                    clipboard.setText(AnnotatedString(openWorkText(state, ranked)))
                    state.status = "Copied the priority list (private nodes left out)"
                })
            }
        }
        HorizontalDivider(color = PanelBorder)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(ranked, key = { it.id }) { PriorityRow(state, it) }
            if (unrated.isNotEmpty()) {
                item(key = "h-unrated") { GroupHeader("Not rated", "give each an importance to rank it", unrated.size, TextSecondary) }
                items(unrated.sortedBy { it.title.lowercase() }, key = { it.id }) { PriorityRow(state, it) }
            }
            if (ranked.isEmpty() && unrated.isEmpty()) {
                item {
                    Text(
                        "No open work yet. Issues, ideas and improvements show up here once they're on the map.",
                        fontSize = 12.sp,
                        color = TextFaint,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun GroupHeader(title: String, hint: String, count: Int, color: Color) {
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold, color = color)
            Text("  $count", fontSize = 10.sp, color = TextFaint)
        }
        Text(hint, fontSize = 10.sp, color = TextFaint)
    }
}

/** One node: type, title, status; its importance on the right (click to change). Clicking the row shows it on the map. */
@Composable
private fun PriorityRow(state: AppState, node: Node, trailing: (@Composable () -> Unit)? = null) {
    var rateOpen by remember { mutableStateOf(false) }
    val selected = node.id in state.selectedNodeIds
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) Accent.copy(alpha = 0.10f) else Color.Transparent)
            .clickable { state.focusNode(node.id) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TypeDot(state.nodeTypeOf(node.type).color, 7.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                node.title.ifBlank { "(untitled)" },
                fontSize = 12.sp,
                color = TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 16.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.nodeTypeOf(node.type).label, fontSize = 10.sp, color = TextFaint)
                if (node.status != NodeStatus.None) Text(" · ${node.status.label}", fontSize = 10.sp, color = node.status.color)
                state.parentOf(node.id)?.let { p ->
                    Text(" · in ${p.title.ifBlank { "(untitled)" }}", fontSize = 10.sp, color = TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        if (trailing != null) {
            trailing()
        } else {
            Box {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .border(1.dp, if (node.importance == 0) Accent.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(5.dp))
                        .clickable { rateOpen = true }
                        .padding(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (node.importance == 0) {
                        Text("Rate", fontSize = 11.sp, color = Accent, modifier = Modifier.padding(horizontal = 4.dp))
                    } else {
                        ImportanceBadge(node.importance, size = 18.dp)
                    }
                }
                RatingMenu(state, node.id, expanded = rateOpen, onDismiss = { rateOpen = false })
            }
        }
    }
}

private fun openWorkText(state: AppState, ranked: List<Node>): String = buildString {
    appendLine("Priorities — ${state.project.name}")
    ranked.filterNot { it.id in state.privateIds }.forEach { appendLine("- ${it.title.trim()} (importance ${it.importance})") }
}.trimEnd()

// ---- Wins ----

@Composable
private fun WinsView(state: AppState, modifier: Modifier) {
    val wins = state.shownProject.nodes.filter { it.status == NodeStatus.Solved }
        .sortedWith(compareByDescending<Node> { it.solvedAt }.thenBy { it.title.lowercase() })
    val clipboard = LocalClipboardManager.current
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (wins.isEmpty()) "No wins yet" else "${wins.size} win${if (wins.size == 1) "" else "s"}",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
            )
            if (wins.isNotEmpty()) {
                CompactOutlinedButton("Copy as list", onClick = {
                    clipboard.setText(AnnotatedString(winsText(state, wins)))
                    state.status = "Copied the wins (private nodes left out)"
                })
            }
        }
        HorizontalDivider(color = PanelBorder)
        if (wins.isEmpty()) {
            Text(
                "Set a node's status to Solved and it lands here with the date: a running record of what's been fixed, " +
                    "ready to copy into an email or a report.",
                fontSize = 12.sp,
                color = TextFaint,
                lineHeight = 17.sp,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(wins, key = { it.id }) { node ->
                    PriorityRow(state, node) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                if (node.solvedAt > 0) formatDay(node.solvedAt) else "Solved",
                                fontSize = 11.sp,
                                color = NodeStatus.Solved.color,
                            )
                            if (node.id in state.privateIds) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    GlyphIcon(Glyph.Lock, PrivateAmber, 9.dp)
                                    Text(" private", fontSize = 9.sp, color = TextFaint)
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

private fun winsText(state: AppState, wins: List<Node>): String = buildString {
    appendLine("Wins — ${state.project.name}")
    for (n in wins.filterNot { it.id in state.privateIds }) {
        val day = if (n.solvedAt > 0) formatStamp(n.solvedAt).substringBeforeLast(' ') else "Solved"
        appendLine("- $day: ${n.title.trim()} (${state.nodeTypeOf(n.type).label})")
    }
}.trimEnd()

// ---- Key details ----

/**
 * Key details: messages rated at or above the threshold, most important
 * first — for the selected node's thread or the whole board. "Copy" puts them
 * on the clipboard as a markdown list (ready for an email, a report, or an AI
 * summary prompt).
 */
@Composable
private fun KeyDetailsView(state: AppState, modifier: Modifier) {
    val node = state.selectedNode
    val wholeBoard = node == null || state.keyDetailWholeBoard
    val source = if (wholeBoard) state.visibleMessages else state.threadOf(node!!.id)
    val details = remember(source, state.keyDetailThreshold) {
        source.filter { it.importance >= state.keyDetailThreshold }
            .sortedWith(compareByDescending<Message> { it.importance }.thenByDescending { it.createdAt })
    }
    val clipboard = LocalClipboardManager.current

    Column(modifier) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Rated", fontSize = 11.sp, color = TextSecondary)
                listOf(3, 5, 7, 9, 11).forEach { t ->
                    FilterChip("$t+", selected = state.keyDetailThreshold == t, color = importanceColor(t)) { state.keyDetailThreshold = t }
                }
                Spacer(Modifier.weight(1f))
                if (node != null) {
                    FilterChip("This node", selected = !state.keyDetailWholeBoard, color = Accent) { state.keyDetailWholeBoard = false }
                    FilterChip("Board", selected = state.keyDetailWholeBoard, color = Accent) { state.keyDetailWholeBoard = true }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${details.size} key detail${if (details.size == 1) "" else "s"}" +
                        if (wholeBoard) " on this board" else " for ${node!!.title.ifBlank { "(untitled)" }}",
                    fontSize = 11.sp,
                    color = TextFaint,
                    modifier = Modifier.weight(1f),
                )
                if (details.isNotEmpty()) {
                    CompactOutlinedButton("Copy as list", onClick = {
                        clipboard.setText(AnnotatedString(keyDetailsMarkdown(state, details)))
                        state.status = "Copied ${details.size} key details"
                    })
                }
            }
        }
        HorizontalDivider(color = PanelBorder)
        if (details.isEmpty()) {
            Text(
                "Nothing rated ${state.keyDetailThreshold}+ yet. Rate messages with the 1–10 scale " +
                    "(\"Rate\" on hover, or pick it before sending).",
                fontSize = 12.sp,
                color = TextFaint,
                lineHeight = 17.sp,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(details, key = { it.id }) { m -> KeyDetailRow(state, m, showNode = wholeBoard) }
            }
        }
    }
}

@Composable
private fun KeyDetailRow(state: AppState, m: Message, showNode: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                if (m.nodeId.isNotEmpty()) state.focusNode(m.nodeId)
                state.rightTab = AppState.RightTab.Chat
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        ImportanceBadge(m.importance, size = 22.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.author, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = authorColor(m.author))
                Text("  ${formatStamp(m.createdAt)}", fontSize = 10.sp, color = TextFaint)
                if (m.kind != MessageKind.Note) {
                    Spacer(Modifier.width(6.dp))
                    KindPill(m.kind)
                }
            }
            if (showNode) {
                val node = state.project.nodes.find { it.id == m.nodeId }
                Text(
                    node?.title?.ifBlank { "(untitled)" } ?: if (m.nodeId.isEmpty()) "Unsorted" else "(removed node)",
                    fontSize = 10.sp,
                    color = node?.let { state.nodeTypeOf(it.type).color } ?: TextFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                linkifyMessage(m.text, state.project, Accent, state.hiddenNodeIds) { state.focusNode(it) },
                fontSize = 12.sp,
                color = TextPrimary,
                lineHeight = 17.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    HorizontalDivider(Modifier.padding(start = 44.dp), color = PanelBorder.copy(alpha = 0.5f))
}

private fun keyDetailsMarkdown(state: AppState, details: List<Message>): String = buildString {
    appendLine("Key details — ${state.project.name}")
    for (m in details) {
        val node = state.project.nodes.find { it.id == m.nodeId }?.title ?: "Unsorted"
        val kind = if (m.kind != MessageKind.Note) " ${m.kind.label}:" else ""
        appendLine("- [★${m.importance}]$kind ${m.text.replace('\n', ' ')} — $node (${m.author}, ${formatStamp(m.createdAt)})")
    }
}
