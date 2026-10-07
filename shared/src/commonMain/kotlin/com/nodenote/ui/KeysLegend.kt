package com.nodenote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodenote.state.AppState
import com.nodenote.storage.ClientPrefs
import com.nodenote.theme.Accent
import com.nodenote.theme.PanelBackground
import com.nodenote.theme.PanelBackgroundRaised
import com.nodenote.theme.PanelBorder
import com.nodenote.theme.TextFaint
import com.nodenote.theme.TextPrimary
import com.nodenote.theme.TextSecondary

/**
 * The keys that work right now, in the map's bottom-left corner: with nothing selected, a
 * card, several cards, an area or a link, and while typing a title. Minimized it is a small
 * "Keys" tab; the choice is kept on this device. F1 still has every key.
 */
@Composable
fun KeysLegend(state: AppState, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(ClientPrefs.keysLegendOpen) }
    fun show(value: Boolean) {
        open = value
        ClientPrefs.keysLegendOpen = value
    }
    val shape = RoundedCornerShape(8.dp)
    val panel = modifier
        .pointerInput(Unit) { detectTapGestures { } } // clicks here never reach the map underneath
        .clip(shape)
        .background(PanelBackground.copy(alpha = 0.94f))
        .border(1.dp, PanelBorder, shape)

    if (!open) {
        Row(
            panel.clickable { show(true) }.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Keys", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
            Spacer(Modifier.width(6.dp))
            GlyphIcon(Glyph.ChevronDown, TextFaint, 9.dp, Modifier.rotate(180f))
        }
        return
    }

    val (context, keys) = keysFor(state)
    Column(
        panel.width(250.dp).padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 7.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Keys", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Text("  $context", fontSize = 11.sp, color = TextFaint, maxLines = 1, modifier = Modifier.weight(1f))
            Box(
                Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).clickable { show(false) },
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(Glyph.ChevronDown, TextSecondary, 10.dp)
            }
        }
        keys.forEach { (key, what) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(84.dp)) { KeyCap(key) }
                Text(what, fontSize = 11.sp, color = TextSecondary, lineHeight = 14.sp)
            }
        }
        Text(
            "All keys: F1",
            fontSize = 11.sp,
            color = Accent,
            modifier = Modifier.padding(top = 2.dp).clip(RoundedCornerShape(4.dp)).clickable { state.shortcutsOpen = true },
        )
    }
}

@Composable
private fun KeyCap(text: String) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .clip(shape)
            .background(PanelBackgroundRaised)
            .border(1.dp, PanelBorder, shape)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(text, fontSize = 10.sp, color = TextPrimary, fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}

/** What the legend says for what is going on (the same keys as [handleShortcut] and the title editor). */
private fun keysFor(state: AppState): Pair<String, List<Pair<String, String>>> {
    val editing = state.editingNodeId
    val selected = state.selectedNodeIds.size
    return when {
        state.drawingArea -> "drawing an area" to listOf(
            "Drag" to "a box for the new area",
            "Esc" to "cancel",
        )
        editing != null && editing == state.freshNodeId -> {
            val branch = state.freshParentId != null
            "naming a new card" to listOfNotNull(
                "Enter" to if (branch) "done, then the next one" else "done",
                "Tab" to "done, then a child of it",
                ("Shift+Tab" to "done, back out a level").takeIf { branch },
                "Esc" to "drop it",
                ("Click away" to "keep it").takeUnless { state.freshIsContinuation },
            )
        }
        editing != null -> "renaming" to listOf(
            "Enter" to "done",
            "Tab" to "done, then a child of it",
            "Esc" to "keep the old title",
        )
        state.editingAreaId != null -> "naming the area" to listOf(
            "Enter" to "done",
            "Esc" to "cancel",
        )
        state.connectFromId != null -> "linking" to listOf(
            "Click a card" to "link to it",
            "Esc" to "cancel",
        )
        selected == 1 && state.selectedNode != null -> "card selected" to listOf(
            "Tab" to "new child",
            "Enter" to "new sibling",
            "F2" to "rename",
            "Alt+drag" to "link it to another card",
            "Drag corner" to "size = importance",
            "Ctrl+G" to "area around its branch",
            "Ctrl+D" to "duplicate",
            "Delete" to "delete",
        )
        selected > 0 -> (if (selected == 1) "1 selected" else "$selected selected") to listOf(
            "Ctrl+G" to "area around them",
            "Arrows" to "nudge (Shift: a grid step)",
            "Ctrl+D" to "duplicate",
            "Ctrl+C / X" to "copy / cut",
            "Delete" to "delete",
            "Esc" to "deselect",
        )
        state.selectedAreaId != null -> "area selected" to listOf(
            "F2" to "rename",
            "Drag border" to "move it with its cards",
            "Drag a grip" to "resize (cards stay put)",
            "Delete" to "remove area (cards stay)",
            "Esc" to "deselect",
        )
        state.selectedEdgeId != null -> "link selected" to listOf(
            "Delete" to "delete the link",
            "Esc" to "deselect",
        )
        else -> "map" to listOf(
            "Double-click" to "new card",
            "Drag" to "box-select",
            "Right-drag" to "pan",
            "Wheel" to "zoom",
            "Ctrl+G" to "draw an area",
            "Ctrl+Z / Y" to "undo / redo",
        )
    }
}
