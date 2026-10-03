package com.nodenote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodenote.model.Message
import com.nodenote.model.MessageKind
import com.nodenote.state.AppState
import com.nodenote.theme.Accent
import com.nodenote.theme.DangerRed
import com.nodenote.theme.PanelBackgroundRaised
import com.nodenote.theme.PanelBorder
import com.nodenote.theme.TextFaint
import com.nodenote.theme.TextPrimary
import com.nodenote.theme.TextSecondary

/**
 * The Sort tab — the temporary vault. Thoughts dumped without a node wait here
 * (oldest first) until someone files them: one tap on a suggested node, any
 * other node, or a brand-new node hung under the right branch of the map.
 * Nothing is filed automatically; the suggestions just make it fast, and every
 * thought filed makes the next suggestions better.
 */
@Composable
fun SortView(state: AppState, modifier: Modifier = Modifier) {
    val pile = state.unsorted
    Column(modifier) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                if (pile.isEmpty()) "Nothing to sort" else "${pile.size} unsorted thought${if (pile.size == 1) "" else "s"}",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Tap where each one belongs. Every thought you file teaches the suggestions.",
                fontSize = 11.sp,
                color = TextFaint,
            )
        }
        HorizontalDivider(color = PanelBorder)
        if (pile.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Dump thoughts below without picking a node — they wait here until someone files them.",
                    fontSize = 12.sp,
                    color = TextFaint,
                    lineHeight = 17.sp,
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(pile, key = { it.id }) { m -> SortCard(state, m) }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
        HorizontalDivider(color = PanelBorder)
        Composer(state, node = null)
    }
}

@Composable
private fun SortCard(state: AppState, m: Message) {
    val suggestions = remember(m.text, m.linkedNodeIds, state.matcher) { state.suggestionsFor(m.text, m.linkedNodeIds) }
    val top = suggestions.firstOrNull()
    val mine = m.author == state.session.userName
    var otherOpen by remember { mutableStateOf(false) }
    var parentOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(m.author, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = authorColor(m.author))
            Spacer(Modifier.width(6.dp))
            Text(formatStamp(m.createdAt), fontSize = 10.sp, color = TextFaint)
            if (m.kind != MessageKind.Note) {
                Spacer(Modifier.width(6.dp))
                KindPill(m.kind)
            }
            Spacer(Modifier.weight(1f))
            ImportanceBadge(m.importance)
            if (mine) {
                Spacer(Modifier.width(6.dp))
                SortAction("Delete", DangerRed) { state.deleteMessage(m.id) }
            }
        }
        Text(
            linkifyMessage(m.text, state.project, Accent, state.hiddenNodeIds, onPerson = { state.showPerson(it) }) { state.focusNode(it) },
            fontSize = 13.sp,
            color = TextPrimary,
            lineHeight = 18.sp,
        )
        if (suggestions.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                suggestions.forEachIndexed { i, n ->
                    FileChip(state, n, primary = i == 0) { state.moveMessage(m.id, n.id) }
                }
            }
        } else if (state.project.nodes.isNotEmpty()) {
            Text("No obvious match — pick a node, or make it a new one.", fontSize = 11.sp, color = TextFaint)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box {
                SortAction("Other node…", TextSecondary) { otherOpen = true }
                NodePickerMenu(
                    state = state,
                    expanded = otherOpen,
                    includeInbox = false,
                    onDismiss = { otherOpen = false },
                    onPick = { id -> state.moveMessage(m.id, id); otherOpen = false },
                )
            }
            SortAction(if (top != null) "+ New node under ${top.title.take(28)}" else "+ New node", Accent) {
                state.makeNodeFromMessage(m.id, parentId = top?.id)
            }
            if (state.project.nodes.isNotEmpty()) {
                Box {
                    SortAction(if (top != null) "under…" else "+ New node under…", TextSecondary) { parentOpen = true }
                    NodePickerMenu(
                        state = state,
                        expanded = parentOpen,
                        includeInbox = false,
                        onDismiss = { parentOpen = false },
                        onPick = { id -> state.makeNodeFromMessage(m.id, parentId = id); parentOpen = false },
                    )
                }
            }
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = PanelBorder.copy(alpha = 0.6f))
}

@Composable
private fun SortAction(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 11.sp,
        color = color,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .border(1.dp, PanelBorder, RoundedCornerShape(5.dp))
            .background(PanelBackgroundRaised)
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}
