package com.nodenote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import com.nodenote.state.SplitMode
import com.nodenote.state.Workspace
import com.nodenote.theme.Accent
import com.nodenote.theme.CanvasBackground
import com.nodenote.theme.PanelBackground
import com.nodenote.theme.PanelBorder
import com.nodenote.theme.TextFaint
import com.nodenote.theme.TextSecondary
import kotlin.math.roundToInt

/**
 * Overall layout: toolbar + tab bar on top, then file explorer | canvas area |
 * inspector, with a notes panel and status bar at the bottom.
 *
 * The app is multi-document: [Workspace] holds the open tabs (each a full
 * [AppState]); the toolbar, inspector, notes, status bar and dialogs all follow
 * the focused tab, while the canvas area can show one or two tabs at once
 * (split view). LocalAppState is provided as the focused tab here and overridden
 * per canvas pane so inline text editors report focus to the right document.
 */
@Composable
fun MainScreen(workspace: Workspace) {
    val focused = workspace.focused
    CompositionLocalProvider(LocalAppState provides focused) {
        Box(Modifier.fillMaxSize()) {
            MainContent(workspace)
            // Dialogs operate on the focused tab.
            ExportDialog(focused)
            CompareDialog(focused)
            ShortcutsDialog(focused)
            NewTypeDialog(focused)
        }
    }
}

@Composable
private fun MainContent(workspace: Workspace) {
    val focused = workspace.focused
    Column(Modifier.fillMaxSize().background(CanvasBackground)) {
        TopBar(workspace)
        HorizontalDivider(color = PanelBorder)
        TabBar(workspace)
        HorizontalDivider(color = PanelBorder)

        Row(Modifier.weight(1f)) {
            if (workspace.fileExplorerVisible) {
                FileExplorer(workspace, Modifier.width(workspace.fileExplorerWidth.dp).fillMaxHeight())
                VerticalResizeHandle(onDragDp = workspace::resizeFileExplorer)
            } else {
                CollapsedSideStrip("▸", onExpand = { workspace.fileExplorerVisible = true })
                VerticalDivider(color = PanelBorder)
            }

            // Center: one or two canvas panes.
            Box(Modifier.weight(1f).fillMaxHeight()) {
                SplitArea(workspace)
            }

            if (focused.inspectorVisible) {
                VerticalResizeHandle(onDragDp = focused::resizeInspector)
                InspectorPanel(focused, Modifier.width(focused.inspectorWidth.dp).fillMaxHeight())
            } else {
                VerticalDivider(color = PanelBorder)
                CollapsedSideStrip("◂", onExpand = { focused.inspectorVisible = true })
            }
        }

        NotesArea(focused)
        HorizontalDivider(color = PanelBorder)
        StatusBar(focused)
    }
}

/**
 * Renders the canvas area. Single pane: just the canvas (its tab lives in the
 * top tab bar). Split: each pane gets its own tab strip directly above it.
 */
@Composable
private fun SplitArea(workspace: Workspace) {
    val pane0 = workspace.paneTab(0) ?: return
    when (workspace.split) {
        SplitMode.None -> CanvasPane(workspace, pane0, 0, Modifier.fillMaxSize())
        SplitMode.SideBySide -> {
            val pane1 = workspace.paneTab(1)
            Row(Modifier.fillMaxSize()) {
                PaneColumn(workspace, pane0, 0, Modifier.weight(1f).fillMaxHeight())
                VerticalDivider(color = PanelBorder)
                if (pane1 != null) PaneColumn(workspace, pane1, 1, Modifier.weight(1f).fillMaxHeight())
            }
        }
        SplitMode.Stacked -> {
            val pane1 = workspace.paneTab(1)
            Column(Modifier.fillMaxSize()) {
                PaneColumn(workspace, pane0, 0, Modifier.weight(1f).fillMaxWidth())
                HorizontalDivider(color = PanelBorder)
                if (pane1 != null) PaneColumn(workspace, pane1, 1, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/** A pane's own tab strip stacked above its canvas (used only when split). */
@Composable
private fun PaneColumn(workspace: Workspace, tab: AppState, paneIndex: Int, modifier: Modifier) {
    Column(modifier) {
        PaneTabStrip(workspace, paneIndex)
        HorizontalDivider(color = PanelBorder)
        CanvasPane(workspace, tab, paneIndex, Modifier.weight(1f).fillMaxWidth())
    }
}

/**
 * One canvas pane showing [tab]'s graph. Pressing anywhere in the pane focuses
 * it (Initial pass, no consume, so the canvas still gets the event). When split,
 * the focused pane gets an accent border. LocalAppState is set to this tab so
 * inline editors report to the right document.
 */
@Composable
private fun CanvasPane(workspace: Workspace, tab: AppState, paneIndex: Int, modifier: Modifier) {
    val split = workspace.split != SplitMode.None
    val isFocused = workspace.isFocused(tab)
    // Only add a wrapper modifier when split. In single-pane mode the pane must
    // be completely transparent to pointer input, or it competes with the
    // canvas's own gesture/event handling (pan, zoom, cursor tracking).
    val paneModifier = if (split) {
        modifier
            .border(
                width = if (isFocused) 1.5.dp else 0.dp,
                color = if (isFocused) Accent.copy(alpha = 0.7f) else Color.Transparent,
            )
            // Focus this pane on click without consuming the event (Main pass,
            // non-consuming) so the canvas still receives the gesture.
            .pointerInput(paneIndex) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    workspace.focusPane(paneIndex)
                }
            }
    } else {
        modifier
    }
    Box(paneModifier) {
        CompositionLocalProvider(LocalAppState provides tab) {
            GraphCanvas(tab, Modifier.fillMaxSize())
            ConnectionBanner(tab, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
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

/**
 * Bottom panel: a larger notes editor for the selected node (bound to its
 * description). Resizable by dragging its top border; collapses to a thin
 * reopen strip.
 */
@Composable
private fun NotesArea(state: AppState) {
    val node = state.selectedNode ?: return
    if (!state.notesPanelVisible) {
        HorizontalDivider(color = PanelBorder)
        Row(
            Modifier
                .fillMaxWidth()
                .height(20.dp)
                .background(PanelBackground)
                .clickable { state.notesPanelVisible = true }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("▴  Notes", fontSize = 10.sp, color = TextSecondary)
        }
        return
    }

    HorizontalResizeHandle(onDragDp = state::resizeNotesPanel)
    Column(
        Modifier
            .fillMaxWidth()
            .height(state.notesPanelHeight.dp)
            .background(PanelBackground)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PanelCaption("Notes — ${node.title.ifBlank { "(untitled)" }}", modifier = Modifier.weight(1f))
            PanelToggleButton("▾", onClick = { state.notesPanelVisible = false })
        }
        Spacer(Modifier.height(6.dp))
        CompactTextField(
            value = node.description,
            onValueChange = { v -> state.updateNode(node.id) { it.copy(description = v) } },
            modifier = Modifier.fillMaxSize(),
            placeholder = "Longer notes about this node…",
            singleLine = false,
        )
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
                if (state.dirty) append("unsaved · ")
                append("${state.project.nodes.size} nodes · ${state.project.edges.size} edges · ")
                append("${(state.zoom * 100).roundToInt()}%")
            },
            fontSize = 11.sp,
            color = TextFaint,
        )
    }
}
