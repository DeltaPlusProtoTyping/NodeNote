package com.nodenote.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodenote.state.AppState
import com.nodenote.theme.PanelBackground
import com.nodenote.theme.PanelBackgroundRaised
import com.nodenote.theme.PanelBorder
import com.nodenote.theme.TextPrimary
import com.nodenote.theme.TextSecondary

private val shortcutGroups = listOf(
    "Mapping" to listOf(
        "Tab" to "New child of the selected node (type its title)",
        "Enter" to "New sibling of the selected node",
        "Typing a title: Enter" to "Done; after a new node, starts the next sibling",
        "Typing a title: Tab" to "Done, then a child of it",
        "Typing a title: Shift + Tab" to "Done, then back out a level",
        "Click away from a new node" to "Keep it, called \"New Issue\" (etc.) until renamed",
        "Escape" to "Drop the new node you're naming",
        "Empty Enter in a typed-out branch" to "Stop (the empty next node goes away)",
        "F2" to "Rename the selected node",
        "Drag the card's corner" to "Make the node bigger or smaller",
        "Ctrl + G" to "Area around the selected cards (none selected: drag a box)",
        "Drag an area's name or border" to "Move the area with everything in it",
        "Drag an area's corner or side middle" to "Resize it (the cards stay put)",
        "Click a link" to "Say what it means: Causes, Solves, Blocks…",
    ),
    "Chat" to listOf(
        "Enter" to "Send message",
        "Shift + Enter" to "New line",
        "[[" to "Link a node (pick from the list, Enter completes)",
        "@" to "Name a person (they get a filter in the node list)",
        "Escape" to "Cancel editing a message",
    ),
    "Edit" to listOf(
        "Ctrl + Z" to "Undo",
        "Ctrl + Y  /  Ctrl + Shift + Z" to "Redo",
        "Ctrl + C / X / V" to "Copy / Cut / Paste",
        "Ctrl + D" to "Duplicate selection",
        "Ctrl + A" to "Select all",
        "Delete" to "Delete selection",
        "Arrow keys" to "Nudge selection",
        "Shift + Arrow keys" to "Nudge by one grid step",
        "Escape" to "Cancel connection / clear selection / close dialog",
    ),
    "View" to listOf(
        "Ctrl + =" to "Zoom in",
        "Ctrl + −" to "Zoom out",
        "Ctrl + 0" to "Reset view",
        "Mouse wheel" to "Zoom at cursor",
        "Middle- or right-drag" to "Pan canvas",
    ),
    "Canvas" to listOf(
        "Double-click empty space" to "Add a node (last-used type) and type its title",
        "Double-click a node" to "Rename inline",
        "Double-click a text block" to "Edit text",
        "Drag a node's right-edge dot" to "Connect to another node",
        "Alt + drag a node" to "Connect it to the node you release on",
        "Alt + click a node" to "Start a connection (then click the target)",
        "Drag on empty space" to "Box-select",
        "Ctrl + click" to "Toggle item in selection",
        "Click empty space" to "Deselect — the chat shows the whole board",
        "Click an area's name or border" to "Select the area (rename, color, private, tidy inside)",
    ),
    "Saving" to listOf(
        "(automatic)" to "Every edit and message syncs to the server as you go",
    ),
)

/** Keyboard & mouse cheat sheet. Opened from the toolbar "?" button; closed by Escape or clicking away. */
@Composable
fun ShortcutsDialog(state: AppState) {
    if (!state.shortcutsOpen) return

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(Unit) { detectTapGestures { state.shortcutsOpen = false } },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = PanelBackground,
            border = BorderStroke(1.dp, PanelBorder),
            modifier = Modifier
                .width(560.dp)
                .height(560.dp)
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Shortcuts", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, modifier = Modifier.weight(1f))
                    CloseButton(onClick = { state.shortcutsOpen = false })
                }
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    shortcutGroups.forEach { (group, rows) ->
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            PanelCaption(group)
                            rows.forEach { (keys, desc) -> ShortcutRow(keys, desc) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShortcutRow(keys: String, description: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(230.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(PanelBackgroundRaised)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(keys, fontSize = 11.sp, color = TextPrimary, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.width(12.dp))
        Text(description, fontSize = 12.sp, color = TextSecondary)
    }
}
