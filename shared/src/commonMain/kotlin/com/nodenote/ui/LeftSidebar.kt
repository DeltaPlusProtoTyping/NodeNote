package com.nodenote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodenote.state.AppState
import com.nodenote.theme.Accent
import com.nodenote.theme.PanelBackground
import com.nodenote.theme.PanelBorder
import com.nodenote.theme.TextFaint
import com.nodenote.theme.TextPrimary
import com.nodenote.theme.color

/**
 * Left panel: project name, type filter, and the node list.
 * Clicking a node selects it and centers the canvas on it.
 */
@Composable
fun LeftSidebar(state: AppState, modifier: Modifier = Modifier) {
    val nodes = state.project.nodes
    var searchQuery by remember { mutableStateOf("") }
    val filtered = nodes
        .filter { state.typeFilter == null || it.type == state.typeFilter }
        .filter {
            searchQuery.isBlank() ||
                it.title.contains(searchQuery, ignoreCase = true) ||
                it.description.contains(searchQuery, ignoreCase = true)
        }

    Column(modifier.background(PanelBackground).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PanelCaption("Project", modifier = Modifier.weight(1f))
            PanelToggleButton("◂", onClick = { state.leftSidebarVisible = false })
        }
        Spacer(Modifier.height(6.dp))
        CompactTextField(
            value = state.project.name,
            onValueChange = state::renameProject,
            modifier = Modifier.fillMaxWidth(),
            placeholder = "Project name",
            textStyle = TextStyle(color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
        )

        Spacer(Modifier.height(14.dp))
        PanelCaption(
            if (state.typeFilter == null) "Nodes (${nodes.size})"
            else "Nodes (${filtered.size} of ${nodes.size})",
        )
        Spacer(Modifier.height(6.dp))
        TypeSelector(
            state = state,
            selectedId = state.typeFilter,
            allowAll = true,
            onSelect = { state.typeFilter = it },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        CompactTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = "Search nodes…",
            minHeight = 26.dp,
        )
        Spacer(Modifier.height(6.dp))

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(filtered, key = { it.id }) { node ->
                val selected = node.id in state.selectedNodeIds
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selected) Accent.copy(alpha = 0.14f) else Color.Transparent)
                        .clickable { state.focusNode(node.id) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val typeDef = state.nodeTypeOf(node.type)
                    TypeDot(typeDef.color, 7.dp)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            node.title.ifBlank { "(untitled)" },
                            fontSize = 12.sp,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(typeDef.label, fontSize = 10.sp, color = TextFaint)
                    }
                }
            }
            if (filtered.isEmpty()) {
                item {
                    Text(
                        if (nodes.isEmpty()) "No nodes yet" else "No nodes of this type",
                        fontSize = 11.sp,
                        color = TextFaint,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }

        HorizontalDivider(color = PanelBorder)
        Spacer(Modifier.height(8.dp))
        // Project navigation placeholder — multiple views/pages would live here.
        Text(
            "Open sample project",
            fontSize = 12.sp,
            color = Accent,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable { state.openSampleProject() }
                .padding(4.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text("Views & pages — coming soon", fontSize = 10.sp, color = TextFaint, modifier = Modifier.padding(4.dp))
    }
}
