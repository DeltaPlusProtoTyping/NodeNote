package com.nodenote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodenote.state.AppState
import com.nodenote.state.LocalAppState
import com.nodenote.state.Workspace
import com.nodenote.storage.isWebPlatform
import com.nodenote.storage.reloadPage
import com.nodenote.theme.Accent
import com.nodenote.theme.CanvasBackground
import com.nodenote.theme.PanelBackground
import com.nodenote.theme.PanelBorder
import com.nodenote.theme.TextFaint
import com.nodenote.theme.TextPrimary
import com.nodenote.theme.TextSecondary
import kotlin.math.roundToInt

/**
 * Overall layout: toolbar + tab bar on top, then boards panel | canvas area |
 * chat panel, with a status bar at the bottom.
 *
 * The app is multi-board: [Workspace] holds the open tabs (each a full
 * [AppState] bound to a server board); the toolbar, canvas, right panel,
 * status bar and dialogs all follow the focused tab.
 */
@Composable
fun MainScreen(workspace: Workspace, modifier: Modifier = Modifier) {
    val focused = workspace.focused
    CompositionLocalProvider(LocalAppState provides focused) {
        BoxWithConstraints(modifier.fillMaxSize()) {
            // Phone-width browsers get a one-page-at-a-time layout.
            // Phones in either orientation (landscape is wide but very short).
            if (maxWidth < 760.dp || maxHeight < 520.dp) CompactLayout(workspace) else MainContent(workspace, maxWidth.value)
            // Dialogs operate on the focused tab.
            ExportDialog(focused)
            ShortcutsDialog(focused)
            NewTypeDialog(focused)
            NewBoardDialog(workspace)
            SettingsDialog(workspace)
        }
    }
}

@Composable
private fun MainContent(workspace: Workspace, windowWidthDp: Float) {
    val focused = workspace.focused
    Column(Modifier.fillMaxSize().background(CanvasBackground)) {
        TopBar(workspace)
        UpdateBanner(workspace)
        HorizontalDivider(color = PanelBorder)
        TabBar(workspace)
        HorizontalDivider(color = PanelBorder)

        Row(Modifier.weight(1f)) {
            if (workspace.boardsPanelVisible) {
                BoardsPanel(workspace, Modifier.width(workspace.boardsPanelWidth.dp).fillMaxHeight())
                VerticalResizeHandle(onDragDp = workspace::resizeBoardsPanel)
            } else {
                CollapsedSideStrip(Glyph.ChevronRight, onExpand = { workspace.boardsPanelVisible = true })
                VerticalDivider(color = PanelBorder)
            }

            val chatExpanded = focused.boardId != null && focused.inspectorVisible && focused.chatExpanded
            if (chatExpanded) {
                // Expanded chat: the map is hidden and the chat fills the middle.
                VerticalDivider(color = PanelBorder)
                RightPanel(focused, Modifier.weight(1f).fillMaxHeight())
            } else {
                // Center: one or two canvas panes.
                CanvasArea(workspace, Modifier.weight(1f).fillMaxHeight())
            }

            if (focused.boardId != null && !chatExpanded) {
                if (focused.inspectorVisible) {
                    VerticalResizeHandle(onDragDp = focused::resizeInspector)
                    // Never wider than the window allows — leave the map a usable strip.
                    val leftWidth = if (workspace.boardsPanelVisible) workspace.boardsPanelWidth else 20f
                    val maxChat = (windowWidthDp - leftWidth - 160f).coerceAtLeast(280f)
                    RightPanel(focused, Modifier.width(minOf(focused.inspectorWidth, maxChat).dp).fillMaxHeight())
                } else {
                    VerticalDivider(color = PanelBorder)
                    CollapsedSideStrip(Glyph.ChevronLeft, onExpand = { focused.inspectorVisible = true })
                }
            }
        }

        HorizontalDivider(color = PanelBorder)
        StatusBar(focused)
    }
}

/**
 * The canvas area: the focused tab's board, or its Home view. LocalAppState is
 * set to this tab so inline editors report to the right document.
 */
@Composable
private fun CanvasArea(workspace: Workspace, modifier: Modifier) {
    val tab = workspace.focused
    Box(modifier) {
        CompositionLocalProvider(LocalAppState provides tab) {
            if (tab.boardId == null) {
                HomeView(workspace, Modifier.fillMaxSize())
            } else {
                GraphCanvas(tab, Modifier.fillMaxSize())
                ConnectionBanner(tab, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
            }
        }
    }
}

/**
 * Shown when the server got a newer version than this page/window. The browser
 * also reloads by itself the next time the tab is in the background.
 */
@Composable
fun UpdateBanner(workspace: Workspace) {
    if (!workspace.session.updateAvailable) return
    Row(
        Modifier.fillMaxWidth().background(Accent.copy(alpha = 0.18f)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (isWebPlatform) "NodeNote was updated — reload to get the new version." else "The server was updated — restart the app to stay in sync.",
            fontSize = 12.sp,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
        )
        if (isWebPlatform) {
            Text(
                "Reload",
                fontSize = 12.sp,
                color = Accent,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { reloadPage() }.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/** Floating hint shown while a connection is pending ("click a target node"). */
@Composable
private fun ConnectionBanner(state: AppState, modifier: Modifier = Modifier) {
    val fromId = state.connectFromId ?: return
    val fromTitle = state.project.nodes.find { it.id == fromId }?.title ?: "?"
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF24364F),
        border = androidx.compose.foundation.BorderStroke(1.dp, Accent.copy(alpha = 0.5f)),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Connecting from \"$fromTitle\" — click a target node",
                fontSize = 12.sp,
                color = Color(0xFFCBDEFF),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "Cancel",
                fontSize = 12.sp,
                color = Accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { state.cancelConnection() }
                    .padding(2.dp),
            )
        }
    }
}

@Composable
private fun StatusBar(state: AppState) {
    Row(
        Modifier.fillMaxWidth().height(26.dp).background(PanelBackground).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            state.status,
            fontSize = 11.sp,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            buildString {
                if (state.boardId != null) {
                    append(state.syncStatus.label).append(" · ")
                    append("${state.project.nodes.size} nodes · ${state.messages.size} messages · ")
                }
                append("${(state.zoom * 100).roundToInt()}%")
            },
            fontSize = 11.sp,
            color = TextFaint,
        )
    }
}
