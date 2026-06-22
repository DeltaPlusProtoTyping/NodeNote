package com.nodenote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodenote.state.AppState
import com.nodenote.state.SplitMode
import com.nodenote.state.Workspace
import com.nodenote.theme.Accent
import com.nodenote.theme.PanelBackground
import com.nodenote.theme.PanelBackgroundRaised
import com.nodenote.theme.PanelBorder
import com.nodenote.theme.TextFaint
import com.nodenote.theme.TextPrimary
import com.nodenote.theme.TextSecondary

/**
 * Top control bar under the toolbar. When the view is a single pane it holds the
 * tab strip (all open projects) plus the split controls. When split, the tabs
 * move to per-pane strips ([PaneTabStrip]) above each pane, so this bar shows
 * only the split controls.
 */
@Composable
fun TabBar(workspace: Workspace) {
    Row(
        Modifier.fillMaxWidth().height(32.dp).background(PanelBackground).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (workspace.split == SplitMode.None) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                workspace.tabs.forEach { tab ->
                    TabChip(
                        tab = tab,
                        focused = workspace.isFocused(tab),
                        shown = workspace.paneTab(0) === tab,
                        onSelect = { workspace.selectTab(tab) },
                        onClose = { workspace.closeTab(tab) },
                    )
                    Spacer(Modifier.width(3.dp))
                }
                SmallIconButton("+", onClick = { workspace.newTab() })
            }
        } else {
            Spacer(Modifier.weight(1f))
        }

        Spacer(Modifier.width(8.dp))
        // Split controls.
        SplitButton("▭", "Single pane", workspace.split == SplitMode.None) { workspace.setSplitMode(SplitMode.None) }
        SplitButton("▤", "Split: stacked", workspace.split == SplitMode.Stacked) { workspace.setSplitMode(SplitMode.Stacked) }
        SplitButton("▥", "Split: side by side", workspace.split == SplitMode.SideBySide) { workspace.setSplitMode(SplitMode.SideBySide) }
    }
}

/**
 * Per-pane tab strip shown above a pane when the view is split. Lists all open
 * projects; the one currently shown in this pane is highlighted. Clicking a tab
 * loads it into this pane; "+" opens a new project here.
 */
@Composable
fun PaneTabStrip(workspace: Workspace, paneIndex: Int) {
    val shownTab = workspace.paneTab(paneIndex)
    val paneFocused = workspace.focusedPane == paneIndex
    Row(
        Modifier.fillMaxWidth().height(28.dp).background(PanelBackground).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            workspace.tabs.forEach { tab ->
                TabChip(
                    tab = tab,
                    focused = shownTab === tab && paneFocused,
                    shown = shownTab === tab,
                    onSelect = { workspace.showTabInPane(paneIndex, tab) },
                    onClose = { workspace.closeTab(tab) },
                )
                Spacer(Modifier.width(3.dp))
            }
            SmallIconButton("+", onClick = { workspace.newTabInPane(paneIndex) })
        }
    }
}

@Composable
private fun TabChip(
    tab: AppState,
    focused: Boolean,
    shown: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val bg = when {
        focused -> Accent.copy(alpha = 0.18f)
        shown -> PanelBackgroundRaised
        else -> Color.Transparent
    }
    Row(
        Modifier
            .height(24.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(onClick = onSelect)
            .padding(start = 9.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            tab.tabTitle,
            fontSize = 12.sp,
            color = if (focused) TextPrimary else TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 160.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "✕",
            fontSize = 11.sp,
            color = TextFaint,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClick = onClose)
                .padding(horizontal = 3.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun SplitButton(glyph: String, tooltip: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(horizontal = 2.dp)
            .size(26.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Accent.copy(alpha = 0.18f) else Color.Transparent)
            .border(1.dp, PanelBorder, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontSize = 12.sp, color = if (selected) Accent else TextSecondary)
    }
}
