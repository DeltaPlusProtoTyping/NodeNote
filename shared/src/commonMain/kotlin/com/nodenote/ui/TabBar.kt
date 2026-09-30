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
import com.nodenote.state.Workspace
import com.nodenote.theme.Accent
import com.nodenote.theme.PanelBackground
import com.nodenote.theme.PanelBackgroundRaised
import com.nodenote.theme.PanelBorder
import com.nodenote.theme.TextFaint
import com.nodenote.theme.TextPrimary
import com.nodenote.theme.TextSecondary

/** The open boards as tabs; "+" opens a Home tab (board picker). */
@Composable
fun TabBar(workspace: Workspace) {
    Row(
        Modifier.fillMaxWidth().height(32.dp).background(PanelBackground).padding(horizontal = 6.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        workspace.tabs.forEach { tab ->
            TabChip(
                tab = tab,
                focused = workspace.isFocused(tab),
                shown = workspace.isFocused(tab),
                onSelect = { workspace.selectTab(tab) },
                onClose = { workspace.closeTab(tab) },
            )
            Spacer(Modifier.width(3.dp))
        }
        SmallIconButton("+", onClick = { workspace.newTab() })
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
        Box(
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClick = onClose)
                .padding(4.dp),
        ) {
            GlyphIcon(Glyph.Close, TextFaint, 8.dp)
        }
    }
}

