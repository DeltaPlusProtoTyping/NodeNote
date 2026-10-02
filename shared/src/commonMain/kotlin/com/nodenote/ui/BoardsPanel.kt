package com.nodenote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodenote.api.BoardSummary
import com.nodenote.model.Node
import com.nodenote.model.NodeStatus
import com.nodenote.state.AppState
import com.nodenote.state.Workspace
import com.nodenote.theme.Accent
import com.nodenote.theme.PanelBackground
import com.nodenote.theme.PanelBorder
import com.nodenote.theme.TextFaint
import com.nodenote.theme.TextPrimary
import com.nodenote.theme.TextSecondary
import com.nodenote.theme.color

/**
 * Left panel: the shared boards on the server (top), and the nodes of the
 * focused board (bottom) with status/importance/unread at a glance — the
 * work-queue view of a board.
 */
@Composable
fun BoardsPanel(workspace: Workspace, modifier: Modifier = Modifier, compact: Boolean = false, onPicked: () -> Unit = {}) {
    val state = workspace.focused
    Column(modifier.background(PanelBackground).padding(start = 10.dp, end = 10.dp, top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PanelCaption("Boards", modifier = Modifier.weight(1f))
            SmallIconButton("+", onClick = { workspace.newBoardOpen = true })
            if (!compact) {
                Spacer(Modifier.width(4.dp))
                PanelToggleButton(Glyph.ChevronLeft, onClick = { workspace.boardsPanelVisible = false })
            }
        }
        Spacer(Modifier.height(6.dp))
        Column(Modifier.heightIn(max = if (compact) 170.dp else 220.dp)) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                items(workspace.boards, key = { it.id }) { b -> BoardRow(workspace, b) }
                if (workspace.boards.isEmpty()) {
                    item {
                        Text(
                            if (workspace.session.connection == com.nodenote.state.ConnectionState.Online) "No boards yet — + to create one"
                            else "Server not reachable",
                            fontSize = 11.sp,
                            color = TextFaint,
                            modifier = Modifier.padding(6.dp),
                        )
                    }
                }
            }
        }
        Text(
            if (workspace.showArchived) "Hide archived" else "Show archived",
            fontSize = 10.sp,
            color = TextFaint,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { workspace.toggleShowArchived() }.padding(4.dp),
        )
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = PanelBorder)
        Spacer(Modifier.height(8.dp))
        if (state.isLoaded) NodeList(state, Modifier.weight(1f), onPicked) else Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun BoardRow(workspace: Workspace, b: BoardSummary) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val open = workspace.focused.boardId == b.id
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .hoverable(interaction)
            .background(if (open) Accent.copy(alpha = 0.14f) else Color.Transparent)
            .clickable { workspace.openBoard(b.id) }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                b.name.ifBlank { "Untitled" },
                fontSize = 12.sp,
                fontWeight = if (open) FontWeight.SemiBold else FontWeight.Normal,
                fontStyle = if (b.archived) FontStyle.Italic else FontStyle.Normal,
                color = if (b.archived) TextFaint else TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${b.openCount} open · ${b.nodeCount} nodes · ${b.messageCount} msgs",
                fontSize = 10.sp,
                color = TextFaint,
                maxLines = 1,
            )
        }
        if (hovered) {
            Text(
                if (b.archived) "Restore" else "Archive",
                fontSize = 10.sp,
                color = TextSecondary,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { workspace.setArchived(b.id, !b.archived) }.padding(3.dp),
            )
        }
    }
}

private enum class NodeSort(val label: String) { Importance("Importance"), Activity("Recent chat"), Status("Status"), Name("Name") }

private enum class NodeScope(val label: String) { All("All"), Active("Open work"), Solved("Solved"), Unread("Unread") }

@Composable
private fun NodeList(state: AppState, modifier: Modifier, onPicked: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(NodeSort.Importance) }
    var scope by remember { mutableStateOf(NodeScope.All) }
    val stats = state.threadStats
    val nodes = state.project.nodes
        .filter {
            when (scope) {
                NodeScope.All -> true
                NodeScope.Active -> it.status.isActive
                NodeScope.Solved -> it.status == NodeStatus.Solved
                NodeScope.Unread -> (stats[it.id]?.unread ?: 0) > 0
            }
        }
        .filter {
            query.isBlank() || it.title.contains(query, ignoreCase = true) ||
                it.description.contains(query, ignoreCase = true) || it.owner.contains(query, ignoreCase = true)
        }
        .let { list ->
            when (sort) {
                NodeSort.Importance -> list.sortedWith(compareByDescending<Node> { it.importance }.thenBy { it.title.lowercase() })
                NodeSort.Activity -> list.sortedByDescending { stats[it.id]?.lastAt ?: 0L }
                NodeSort.Status -> list.sortedWith(compareBy<Node> { it.status.ordinal }.thenByDescending { it.importance })
                NodeSort.Name -> list.sortedBy { it.title.lowercase() }
            }
        }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PanelCaption("Nodes (${state.project.nodes.size})", modifier = Modifier.weight(1f))
            SortMenu(sort) { sort = it }
        }
        Spacer(Modifier.height(6.dp))
        CompactTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = "Search nodes…",
            minHeight = 26.dp,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            NodeScope.entries.forEach { s -> FilterChip(s.label, selected = scope == s, color = Accent) { scope = s } }
        }
        Spacer(Modifier.height(6.dp))

        // The inbox: unfiled braindumps posted with no node selected.
        val inbox = stats[""]
        InboxRow(state, inbox?.count ?: 0, inbox?.unread ?: 0, onPicked)

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            items(nodes, key = { it.id }) { node -> NodeRow(state, node, onPicked) }
            if (nodes.isEmpty()) {
                item {
                    Text(
                        if (state.project.nodes.isEmpty()) "No nodes yet" else "No nodes match",
                        fontSize = 11.sp,
                        color = TextFaint,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun SortMenu(sort: NodeSort, onSort: (NodeSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            "Sort: ${sort.label}",
            fontSize = 10.sp,
            color = TextSecondary,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { open = true }.padding(3.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            NodeSort.entries.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.label, fontSize = 12.sp, color = if (s == sort) Accent else TextPrimary) },
                    onClick = { onSort(s); open = false },
                )
            }
        }
    }
}

@Composable
private fun InboxRow(state: AppState, count: Int, unread: Int, onPicked: () -> Unit) {
    val active = state.rightTab == AppState.RightTab.Sort
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) Accent.copy(alpha = 0.14f) else Color.Transparent)
            .clickable {
                // The pile of dumped thoughts opens in the Sort tab.
                state.rightTab = AppState.RightTab.Sort
                state.inspectorVisible = true
                onPicked()
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(Accent, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(8.dp))
        Text("Unsorted", fontSize = 12.sp, color = TextPrimary, modifier = Modifier.weight(1f))
        if (count > 0) UnreadBadge(count)
    }
}

@Composable
private fun NodeRow(state: AppState, node: Node, onPicked: () -> Unit) {
    val selected = node.id in state.selectedNodeIds
    val stats = state.threadStats[node.id]
    val typeDef = state.nodeTypeOf(node.type)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Accent.copy(alpha = 0.14f) else Color.Transparent)
            .clickable {
                state.focusNode(node.id)
                state.feedInboxOnly = false
                onPicked()
            }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TypeDot(typeDef.color, 7.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                node.title.ifBlank { "(untitled)" },
                fontSize = 12.sp,
                color = if (node.status == NodeStatus.Solved) TextSecondary else TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (node.status != NodeStatus.None) {
                    Box(Modifier.size(5.dp).background(node.status.color, RoundedCornerShape(3.dp)))
                    Spacer(Modifier.width(4.dp))
                    Text(node.status.label, fontSize = 10.sp, color = TextSecondary)
                    Text(" · ", fontSize = 10.sp, color = TextFaint)
                }
                Text(
                    buildString {
                        append(typeDef.label)
                        if (node.owner.isNotBlank()) append(" · ").append(node.owner)
                        if ((stats?.count ?: 0) > 0) append(" · ${stats!!.count} msg")
                    },
                    fontSize = 10.sp,
                    color = TextFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if ((stats?.unread ?: 0) > 0) {
            Spacer(Modifier.width(4.dp))
            UnreadBadge(stats!!.unread)
        }
        if (node.importance > 0) {
            Spacer(Modifier.width(4.dp))
            ImportanceBadge(node.importance, size = 17.dp)
        }
    }
}
