package com.nodenote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodenote.state.ConnectionState
import com.nodenote.state.Workspace
import com.nodenote.theme.Accent
import com.nodenote.theme.CanvasBackground
import com.nodenote.theme.DangerRed
import com.nodenote.theme.PanelBackground
import com.nodenote.theme.PanelBorder
import com.nodenote.theme.SuccessGreen
import com.nodenote.theme.TextFaint
import com.nodenote.theme.TextPrimary
import com.nodenote.theme.TextSecondary
import com.nodenote.theme.WarnAmber

private enum class CompactPage(val label: String) { Nodes("Boards & nodes"), Chat("Chat"), Map("Map") }

/**
 * Phone-width layout (browser on a phone, out on the shop floor): one page at
 * a time — boards/nodes, the chat, or the node map — with a bottom switcher.
 * Picking a node jumps to its chat, so "open the app, pick the machine, post
 * the problem, rate it" is three taps.
 */
@Composable
fun CompactLayout(workspace: Workspace) {
    val state = workspace.focused
    // With a board open, start on the chat — the dump bar is the first thing you see.
    var page by remember { mutableStateOf(if (state.boardId != null) CompactPage.Chat else CompactPage.Nodes) }

    Column(Modifier.fillMaxSize().background(CanvasBackground)) {
        CompactTopBar(workspace)
        UpdateBanner(workspace)
        HorizontalDivider(color = PanelBorder)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                page == CompactPage.Nodes || state.boardId == null && page != CompactPage.Map ->
                    if (state.boardId == null && workspace.boards.isEmpty()) HomeView(workspace, Modifier.fillMaxSize())
                    else BoardsPanel(workspace, Modifier.fillMaxSize(), compact = true, onPicked = { page = CompactPage.Chat })
                page == CompactPage.Chat -> RightPanel(state, Modifier.fillMaxSize(), compact = true)
                state.boardId == null -> HomeView(workspace, Modifier.fillMaxSize())
                else -> CompactMap(state)
            }
        }
        HorizontalDivider(color = PanelBorder)
        Row(Modifier.fillMaxWidth().height(52.dp).background(PanelBackground)) {
            CompactPage.entries.forEach { p ->
                val badge = if (p == CompactPage.Chat) state.totalUnread else 0
                Box(
                    Modifier.weight(1f).fillMaxHeight().clickable { page = p },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            p.label,
                            fontSize = 13.sp,
                            fontWeight = if (page == p) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (page == p) Accent else TextSecondary,
                        )
                        if (badge > 0) {
                            Spacer(Modifier.width(5.dp))
                            UnreadBadge(badge)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The map on a phone: framed to fit whenever it's opened or the phone rotates,
 * with thumb-sized Fit / zoom buttons (pinch works too).
 */
@Composable
private fun CompactMap(state: com.nodenote.state.AppState) {
    Box(Modifier.fillMaxSize()) {
        GraphCanvas(state, Modifier.fillMaxSize())
        LaunchedEffect(state, state.canvasSize) {
            if (state.canvasSize.width > 0 && state.project.nodes.isNotEmpty()) {
                state.zoomToFit()
                if (state.zoom > 1f) state.resetZoomKeepingCenter() // a small board shouldn't balloon
            }
        }
        Row(
            Modifier.align(Alignment.BottomEnd).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MapButton("-") { state.zoomOut() }
            MapButton("+") { state.zoomIn() }
            MapButton("Fit") { state.zoomToFit() }
        }
    }
}

@Composable
private fun MapButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(width = if (label.length > 1) 56.dp else 44.dp, height = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(PanelBackground.copy(alpha = 0.92f))
            .border(1.dp, PanelBorder, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
    }
}

@Composable
private fun CompactTopBar(workspace: Workspace) {
    val state = workspace.focused
    val session = workspace.session
    Row(
        Modifier.fillMaxWidth().height(44.dp).background(PanelBackground).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(Accent, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(8.dp))
        Text(
            if (state.boardId != null) state.project.name else "NodeNote",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val dot = when (session.connection) {
            ConnectionState.Online -> SuccessGreen
            ConnectionState.Connecting -> WarnAmber
            ConnectionState.Offline -> DangerRed
        }
        Box(Modifier.size(8.dp).background(dot, RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(10.dp))
        val name = session.userName
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(13.dp))
                .background(if (name.isBlank()) PanelBorder else authorColor(name))
                .clickable { workspace.settingsOpen = true },
            contentAlignment = Alignment.Center,
        ) {
            Text(name.take(1).uppercase().ifBlank { "?" }, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
    if (session.connection == ConnectionState.Offline) {
        Text(
            if (session.onThisDevice) "Couldn't save on this phone yet — will retry" else "Offline — messages will retry",
            fontSize = 11.sp,
            color = TextFaint,
            modifier = Modifier.fillMaxWidth().background(PanelBackground).padding(horizontal = 12.dp, vertical = 2.dp),
        )
    }
}
