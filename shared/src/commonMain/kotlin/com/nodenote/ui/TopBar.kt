package com.nodenote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodenote.model.Attachment
import com.nodenote.model.NodeCategory
import com.nodenote.model.NodeTypeDef
import com.nodenote.state.AppState
import com.nodenote.state.Workspace
import com.nodenote.storage.ProjectActions
import com.nodenote.storage.ProjectStorage
import com.nodenote.storage.decodeImageOrNull
import com.nodenote.theme.AppThemeOption
import com.nodenote.theme.ThemeState
import com.nodenote.theme.Accent
import com.nodenote.theme.DangerRed
import com.nodenote.theme.PanelBackground
import com.nodenote.theme.PanelBorder
import com.nodenote.theme.TextFaint
import com.nodenote.theme.TextPrimary
import com.nodenote.theme.TextSecondary
import com.nodenote.theme.color
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.roundToInt

/** Main toolbar: project actions, insert/edit actions, zoom controls. Operates on the workspace's focused tab. */
@Composable
fun TopBar(workspace: Workspace) {
    val state = workspace.focused
    Row(
        Modifier.fillMaxWidth().height(46.dp).background(PanelBackground).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // App mark
        Box(Modifier.size(10.dp).background(Accent, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(8.dp))
        Text("NodeNote", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)

        ToolbarDivider()

        ToolbarButton("New", onClick = { workspace.newTab() })
        ToolbarButton("Open File", onClick = { workspace.openViaDialog() })
        OpenRecentButton(workspace)
        ToolbarButton("Save", onClick = { ProjectActions.save(state) })
        ToolbarButton("Save As", onClick = { ProjectActions.saveAs(state) })
        ToolbarButton("Compare", onClick = { ProjectActions.compare(state) })
        ToolbarButton("Export", onClick = { state.openExportDialog() })

        ToolbarDivider()

        AddNodeButton(state)
        Spacer(Modifier.width(4.dp))
        InsertButton(state)
        ToolbarButton(
            "Align",
            onClick = { state.alignSelected() },
            enabled = state.selectedNodeIds.isNotEmpty(),
        )
        ToolbarButton(
            "Delete",
            onClick = { state.deleteSelected() },
            enabled = state.selectedNodeIds.isNotEmpty(),
            color = DangerRed,
        )

        ToolbarDivider()

        ToolbarButton("Undo", onClick = { state.undo() }, enabled = state.canUndo, color = TextSecondary)
        ToolbarButton("Redo", onClick = { state.redo() }, enabled = state.canRedo, color = TextSecondary)

        Spacer(Modifier.weight(1f))

        ThemeMenuButton()
        ToolbarDivider()

        // Zoom controls
        SmallIconButton("−", onClick = { state.zoomOut() })
        Text(
            "${(state.zoom * 100).roundToInt()}%",
            fontSize = 12.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 46.dp),
        )
        SmallIconButton("+", onClick = { state.zoomIn() })
        Spacer(Modifier.width(4.dp))
        ToolbarButton("Fit", onClick = { state.zoomToFit() }, color = TextSecondary)
        ToolbarButton("Reset", onClick = { state.resetView() }, color = TextSecondary)
        Spacer(Modifier.width(4.dp))
        SmallIconButton("?", onClick = { state.shortcutsOpen = true })
    }
}

@Composable
private fun ToolbarDivider() {
    Spacer(Modifier.width(10.dp))
    VerticalDivider(Modifier.height(20.dp), color = PanelBorder)
    Spacer(Modifier.width(10.dp))
}

/** Dropdown of recently opened/saved project files. Opens the chosen file in a new tab. */
@Composable
private fun OpenRecentButton(workspace: Workspace) {
    val state = workspace.focused
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        ToolbarButton("Open Recent", onClick = {
            state.loadRecentsIfNeeded()
            menuOpen = true
        })
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (state.recentFiles.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No recent files", fontSize = 12.sp, color = TextFaint) },
                    onClick = { menuOpen = false },
                )
            }
            state.recentFiles.forEach { path ->
                val fileName = path.substringAfterLast('\\').substringAfterLast('/')
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(fileName, fontSize = 12.sp, color = TextPrimary)
                            Text(
                                path,
                                fontSize = 9.sp,
                                color = TextFaint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 340.dp),
                            )
                        }
                    },
                    onClick = {
                        menuOpen = false
                        workspace.openFileInNewTab(path)
                    },
                )
            }
        }
    }
}

/** Appearance theme picker (Dark / Night / Light). Writes to the global ThemeState, which re-skins the whole UI. */
@Composable
private fun ThemeMenuButton() {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        ToolbarButton("Theme: ${ThemeState.option.label}", onClick = { menuOpen = true }, color = TextSecondary)
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            AppThemeOption.entries.forEach { opt ->
                DropdownMenuItem(
                    text = {
                        Text(
                            opt.label,
                            fontSize = 12.sp,
                            color = if (opt == ThemeState.option) Accent else TextPrimary,
                        )
                    },
                    onClick = {
                        ThemeState.select(opt)
                        menuOpen = false
                    },
                )
            }
        }
    }
}

/**
 * "+ Add Node" button. The menu shows a Recent row on top, then the node types
 * laid out in columns by category (General, Hardware, Software, …), then
 * "New type…" to create a custom one and add a node of it.
 */
@Composable
private fun AddNodeButton(state: AppState) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton(
            onClick = { menuOpen = true },
            modifier = Modifier.height(28.dp),
            shape = RoundedCornerShape(6.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            Text("+ Add Node", fontSize = 12.sp)
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            val all = state.allNodeTypes
            val byCategory = all.groupBy { it.category }

            val recent = state.recentNodeTypeIds.mapNotNull { id -> all.find { it.id == id } }
            if (recent.isNotEmpty()) {
                AddNodeColumnHeader("Recent", Modifier.padding(start = 10.dp))
                Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    recent.forEach { def -> RecentTypeChip(def) { state.addNode(def.id); menuOpen = false } }
                }
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = PanelBorder)
            }

            // One column per non-empty category, in enum order.
            Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                NodeCategory.entries.forEach { category ->
                    val items = byCategory[category].orEmpty()
                    if (items.isNotEmpty()) {
                        Column(Modifier.width(152.dp)) {
                            AddNodeColumnHeader(category.label)
                            Spacer(Modifier.height(2.dp))
                            items.forEach { def ->
                                AddNodeColumnRow(def.label, def.color) { state.addNode(def.id); menuOpen = false }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = PanelBorder)
            DropdownMenuItem(
                text = { Text("+ New type…", fontSize = 12.sp, color = Accent) },
                onClick = {
                    menuOpen = false
                    state.openNewType(AppState.NewTypeKind.Node) { id -> state.addNode(id) }
                },
            )
        }
    }
}

@Composable
private fun AddNodeColumnHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        fontSize = 9.sp,
        color = TextFaint,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        modifier = modifier.padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun AddNodeColumnRow(label: String, dot: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TypeDot(dot, 7.dp)
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 12.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RecentTypeChip(def: NodeTypeDef, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, PanelBorder, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TypeDot(def.color, 6.dp)
        Spacer(Modifier.width(5.dp))
        Text(def.label, fontSize = 11.sp, color = TextPrimary, maxLines = 1)
    }
}

/** "Insert" menu for non-node canvas elements: text blocks and images. */
@Composable
private fun InsertButton(state: AppState) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        ToolbarButton("Insert ▾", onClick = { menuOpen = true })
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Text block", fontSize = 12.sp) },
                onClick = {
                    menuOpen = false
                    state.addTextElement()
                },
            )
            DropdownMenuItem(
                text = { Text("Image…", fontSize = 12.sp) },
                onClick = {
                    menuOpen = false
                    insertImage(state)
                },
            )
        }
    }
}

@OptIn(ExperimentalEncodingApi::class)
private fun insertImage(state: AppState) {
    val picked = ProjectStorage.pickAttachmentFile()
    if (picked == null) {
        state.status = "Insert cancelled"
        return
    }
    if (picked.bytes.size > Attachment.MAX_EMBED_BYTES) {
        state.status = "Image too large to embed (max 25 MB)"
        return
    }
    val bitmap = decodeImageOrNull(picked.bytes)
    if (bitmap == null) {
        state.status = "${picked.name} is not a decodable image"
        return
    }
    state.addImageElement(picked.name, Base64.encode(picked.bytes), bitmap.width, bitmap.height)
}
