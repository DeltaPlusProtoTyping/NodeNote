package com.nodenote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodenote.state.Workspace
import com.nodenote.storage.FileNode
import com.nodenote.storage.ProjectStorage
import com.nodenote.theme.Accent
import com.nodenote.theme.PanelBackground
import com.nodenote.theme.TextFaint
import com.nodenote.theme.TextPrimary
import com.nodenote.theme.TextSecondary

private class VisibleEntry(val node: FileNode, val depth: Int)

/** Walks the tree from [root], descending only into folders listed in [expanded]. */
private fun buildVisible(root: String, expanded: Set<String>): List<VisibleEntry> {
    val out = ArrayList<VisibleEntry>()
    fun walk(path: String, depth: Int) {
        for (node in ProjectStorage.listDirectory(path)) {
            out.add(VisibleEntry(node, depth))
            if (node.isDirectory && node.path in expanded) walk(node.path, depth + 1)
        }
    }
    walk(root, 0)
    return out
}

/**
 * VS Code-style file explorer: browse folders from a chosen root and open
 * project (.json) files into new tabs. Folders expand inline.
 */
@Composable
fun FileExplorer(workspace: Workspace, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(setOf<String>()) }
    // Recompute the visible rows when the root or the set of expanded folders changes.
    val visible = remember(workspace.rootDir, expanded) { buildVisible(workspace.rootDir, expanded) }
    val rootName = workspace.rootDir.substringAfterLast('\\').substringAfterLast('/').ifBlank { workspace.rootDir }

    Column(modifier.background(PanelBackground).padding(horizontal = 8.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PanelCaption("Explorer", modifier = Modifier.weight(1f))
            PanelToggleButton("◂", onClick = { workspace.fileExplorerVisible = false })
        }
        Spacer(Modifier.height(6.dp))

        // Current folder + open-folder / up controls.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                rootName,
                fontSize = 12.sp,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            SmallIconButton("↑", onClick = {
                ProjectStorage.parentDirectory(workspace.rootDir)?.let {
                    workspace.rootDir = it
                    expanded = emptySet()
                }
            })
            Spacer(Modifier.width(4.dp))
            SmallIconButton("⊞", onClick = {
                ProjectStorage.pickDirectory()?.let {
                    workspace.rootDir = it
                    expanded = emptySet()
                }
            })
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Open sample project",
            fontSize = 11.sp,
            color = Accent,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable { workspace.openSampleInNewTab() }
                .padding(horizontal = 4.dp, vertical = 3.dp),
        )
        Spacer(Modifier.height(6.dp))

        if (visible.isEmpty()) {
            Text("No folders or .json projects here", fontSize = 11.sp, color = TextFaint, modifier = Modifier.padding(4.dp))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                items(visible, key = { it.node.path }) { entry ->
                    FileRow(
                        entry = entry,
                        isExpanded = entry.node.path in expanded,
                        isOpen = workspace.tabs.any { it.currentFilePath == entry.node.path },
                        onClick = {
                            if (entry.node.isDirectory) {
                                expanded = if (entry.node.path in expanded) expanded - entry.node.path else expanded + entry.node.path
                            } else {
                                workspace.openFileInNewTab(entry.node.path)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FileRow(entry: VisibleEntry, isExpanded: Boolean, isOpen: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(start = (6 + entry.depth * 12).dp, top = 4.dp, bottom = 4.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val glyph = if (entry.node.isDirectory) (if (isExpanded) "▾" else "▸") else "•"
        Box(Modifier.width(14.dp)) {
            Text(glyph, fontSize = if (entry.node.isDirectory) 10.sp else 12.sp, color = TextFaint)
        }
        Text(
            entry.node.name,
            fontSize = 12.sp,
            color = when {
                isOpen -> Accent
                entry.node.isDirectory -> TextPrimary
                else -> TextSecondary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
