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
import androidx.compose.runtime.remember
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
import com.nodenote.state.AppState
import com.nodenote.theme.Accent
import com.nodenote.theme.PanelBackground
import com.nodenote.theme.PanelBorder
import com.nodenote.theme.TextFaint
import com.nodenote.theme.TextPrimary
import com.nodenote.theme.TextSecondary
import com.nodenote.theme.color

/**
 * Right side of the window: Chat (the main event), Key details (everything
 * rated at or above a threshold), and Details (the node inspector).
 */
@Composable
fun RightPanel(state: AppState, modifier: Modifier = Modifier, compact: Boolean = false) {
    Column(modifier.background(PanelBackground)) {
        Row(
            Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppState.RightTab.entries.forEach { tab ->
                val badge = when (tab) {
                    AppState.RightTab.Chat -> state.totalUnread
                    AppState.RightTab.Sort -> state.unsorted.size
                    else -> 0
                }
                RightTabButton(tab.label, selected = state.rightTab == tab, badge = badge) { state.rightTab = tab }
                Spacer(Modifier.width(2.dp))
            }
            Spacer(Modifier.weight(1f))
            if (!compact) {
                ToolbarButton(
                    if (state.chatExpanded) "Show map" else "Expand",
                    onClick = { state.chatExpanded = !state.chatExpanded },
                    color = TextSecondary,
                )
                if (!state.chatExpanded) PanelToggleButton(Glyph.ChevronRight, onClick = { state.inspectorVisible = false })
            }
        }
        HorizontalDivider(color = PanelBorder)
        when {
            !state.isLoaded && state.boardId == null -> Box(Modifier.fillMaxSize().padding(16.dp)) {
                Text("Open a board to start chatting.", fontSize = 12.sp, color = TextFaint)
            }
            state.rightTab == AppState.RightTab.Chat -> ChatView(state, Modifier.fillMaxSize())
            state.rightTab == AppState.RightTab.Sort -> SortView(state, Modifier.fillMaxSize())
            state.rightTab == AppState.RightTab.KeyDetails -> KeyDetailsView(state, Modifier.fillMaxSize())
            else -> DetailsView(state, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun RightTabButton(label: String, selected: Boolean, badge: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .height(26.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Accent.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = if (selected) TextPrimary else TextSecondary)
        if (badge > 0) {
            Spacer(Modifier.width(5.dp))
            UnreadBadge(badge)
        }
    }
}

@Composable
fun UnreadBadge(count: Int) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(Accent).padding(horizontal = 5.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (count > 99) "99+" else count.toString(), style = tightText(9.sp, FontWeight.Bold, Color.White))
    }
}

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
    val source = if (wholeBoard) state.messages else state.threadOf(node!!.id)
    val details = remember(source, state.keyDetailThreshold) {
        source.filter { it.importance >= state.keyDetailThreshold }
            .sortedWith(compareByDescending<Message> { it.importance }.thenByDescending { it.createdAt })
    }
    val clipboard = LocalClipboardManager.current

    Column(modifier) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Rated", fontSize = 11.sp, color = TextSecondary)
                listOf(3, 5, 7, 9).forEach { t ->
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
                linkifyMessage(m.text, state.project, Accent) { state.focusNode(it) },
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
        appendLine("- [${m.importance}/10]$kind ${m.text.replace('\n', ' ')} — $node (${m.author}, ${formatStamp(m.createdAt)})")
    }
}
