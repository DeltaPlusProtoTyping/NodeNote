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
    "Files" to listOf(
        "Ctrl + S" to "Save",
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
        "Middle-drag" to "Pan canvas",
    ),
    "Canvas" to listOf(
        "Double-click empty space" to "Add a node (last-used type)",
        "Double-click a node" to "Rename inline",
        "Double-click a text block" to "Edit text",
        "Drag a node's right-edge dot" to "Connect to another node",
        "Drag on empty space" to "Box-select",
        "Ctrl + click" to "Toggle item in selection",
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
                    Text(
                        "✕",
                        fontSize = 13.sp,
                        color = TextSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { state.shortcutsOpen = false }
                            .padding(6.dp),
                    )
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
