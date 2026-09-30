package com.nodenote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.nodenote.theme.TextFaint
import com.nodenote.theme.TextPrimary
import com.nodenote.theme.TextSecondary

/** What a tab shows before a board is opened in it: the boards on the server, and ways to start one. */
@Composable
fun HomeView(workspace: Workspace, modifier: Modifier = Modifier) {
    val session = workspace.session
    Box(modifier.background(CanvasBackground), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.width(620.dp).verticalScroll(rememberScrollState()).padding(vertical = 48.dp, horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(16.dp).background(Accent, RoundedCornerShape(5.dp)))
                Spacer(Modifier.width(10.dp))
                Text("NodeNote", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            }
            Text(
                "Braindump boards for process improvement. Map the shop as nodes, chat under each node, " +
                    "rate the key details 1–10, and track issues from open to solved. Everything is mirrored " +
                    "into the Obsidian vault on the server.",
                fontSize = 13.sp,
                color = TextSecondary,
                lineHeight = 19.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { workspace.newBoardOpen = true }, shape = RoundedCornerShape(6.dp), modifier = Modifier.height(32.dp)) {
                    Text("+ New board", fontSize = 12.sp)
                }
                CompactOutlinedButton("Import NodeNote .json", onClick = { workspace.importBoardFromFile() }, modifier = Modifier.height(32.dp))
            }

            if (session.connection == ConnectionState.Offline) {
                Text(
                    "Can't reach the server at ${session.serverUrl}. Start it with `gradlew :server:run` " +
                        "(or check Tailscale), or change the address in Settings.",
                    fontSize = 12.sp,
                    color = DangerRed,
                    lineHeight = 17.sp,
                )
            }

            if (workspace.boards.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                PanelCaption("Boards")
                workspace.boards.forEach { b ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(PanelBackground)
                            .border(1.dp, PanelBorder, RoundedCornerShape(8.dp))
                            .clickable { workspace.openBoard(b.id) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(b.name.ifBlank { "Untitled" }, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${b.openCount} open · ${b.nodeCount} nodes · ${b.messageCount} messages · updated ${formatStamp(b.updatedAt)}",
                                fontSize = 11.sp,
                                color = TextFaint,
                            )
                        }
                        Text("Open", fontSize = 12.sp, color = Accent)
                    }
                }
            }
        }
    }
}
