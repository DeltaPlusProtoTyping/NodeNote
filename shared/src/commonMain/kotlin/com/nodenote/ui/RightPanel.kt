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
 * Right side of the window: Chat (the main event), Sort (the unsorted pile),
 * Priorities (quick wins, wins, key details), and Details (the node inspector).
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
            state.rightTab == AppState.RightTab.Priorities -> PrioritiesView(state, Modifier.fillMaxSize())
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
