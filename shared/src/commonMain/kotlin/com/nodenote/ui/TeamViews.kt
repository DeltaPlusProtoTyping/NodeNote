package com.nodenote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodenote.api.Activity
import com.nodenote.api.HistoryEntry
import com.nodenote.model.Node
import com.nodenote.model.nowMillis
import com.nodenote.state.AppState
import com.nodenote.state.Workspace
import com.nodenote.storage.notificationPermission
import com.nodenote.storage.requestNotificationPermission
import com.nodenote.theme.Accent
import com.nodenote.theme.DangerRed
import com.nodenote.theme.PanelBackground
import com.nodenote.theme.PanelBorder
import com.nodenote.theme.TextFaint
import com.nodenote.theme.TextPrimary
import com.nodenote.theme.TextSecondary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/*
 * Working together on a server: who's been here and what they changed (Activity), putting a
 * board back the way it was (Earlier versions), replies, the first-run tips, shared text
 * waiting for a board, and "Might connect to" link suggestions.
 */

/** "just now", "5 min ago", "2 h ago", "3 days ago", else the date. */
fun formatAgo(ms: Long): String {
    val minutes = (nowMillis() - ms) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 48 * 60 -> "${minutes / 60} h ago"
        minutes < 14 * 24 * 60 -> "${minutes / (24 * 60)} days ago"
        else -> formatDate(ms)
    }
}

/**
 * The board menu's items (wide toolbar "Board", phone "Menu"). Activity and Earlier versions
 * are for boards on a server; boards kept on this device have no one else and no saved copies.
 */
@Composable
fun BoardMenuItems(workspace: Workspace, close: () -> Unit) {
    val session = workspace.session
    val state = workspace.focused
    if (!session.onThisDevice) {
        DropdownMenuItem(
            text = { Text("Activity", fontSize = 12.sp) },
            onClick = { close(); workspace.activityOpen = true },
        )
        DropdownMenuItem(
            text = { Text("Earlier versions…", fontSize = 12.sp, color = if (state.isLoaded) TextPrimary else TextFaint) },
            enabled = state.isLoaded,
            onClick = { close(); workspace.historyOpen = true },
        )
        // Browsers that can notify: offer it until it's allowed.
        val permission = remember { mutableStateOf(notificationPermission()) }
        if (permission.value == "default" || permission.value == "denied") {
            DropdownMenuItem(
                text = { Text("Notify me about replies", fontSize = 12.sp) },
                onClick = {
                    close()
                    if (permission.value == "denied") {
                        state.status = "Notifications are blocked for this site; allow them in the browser's site settings"
                    } else {
                        requestNotificationPermission { answer ->
                            permission.value = answer
                            state.status = if (answer == "granted") "You'll be told when someone replies on your cards" else "Notifications not allowed"
                        }
                    }
                },
            )
        }
    }
    DropdownMenuItem(
        text = { Text("Show tips", fontSize = 12.sp) },
        onClick = { close(); workspace.tipsVisible = true },
    )
}

/** Who is on this server, when they were last here, and what changed lately. */
@Composable
fun ActivityDialog(workspace: Workspace) {
    if (!workspace.activityOpen) return
    val session = workspace.session
    var activity by remember { mutableStateOf<Activity?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            activity = session.api.activity()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            session.markOffline(e)
            error = "Couldn't load the activity — is the server reachable?"
        }
    }
    ModalCard("Activity", onDismiss = { workspace.activityOpen = false }, width = 460.dp) {
        val a = activity
        when {
            error != null -> Text(error!!, fontSize = 12.sp, color = DangerRed)
            a == null -> Text("Loading…", fontSize = 12.sp, color = TextFaint)
            else -> Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PanelCaption("People (${a.people.size})")
                a.people.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            p.name,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = authorColor(p.name),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 150.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            buildString {
                                append(if (p.lastSeen > 0) "last seen ${formatAgo(p.lastSeen)}" else "not seen yet")
                                append(", ")
                                append(if (p.lastChange > 0) "last change ${formatAgo(p.lastChange)}" else "no changes yet")
                            },
                            fontSize = 11.sp,
                            color = TextSecondary,
                        )
                    }
                }
                Spacer(Modifier.width(1.dp))
                PanelCaption("Recent")
                if (a.recent.isEmpty()) Text("Nothing changed lately.", fontSize = 12.sp, color = TextFaint)
                a.recent.forEach { item ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable {
                            workspace.activityOpen = false
                            workspace.openBoard(item.boardId)
                        }.padding(horizontal = 4.dp, vertical = 3.dp),
                    ) {
                        Row {
                            Text(item.author, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = authorColor(item.author))
                            Text(": ${item.what}", fontSize = 12.sp, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Text("on ${item.boardName}, ${formatAgo(item.at)}", fontSize = 11.sp, color = TextFaint)
                    }
                }
            }
        }
    }
}

/**
 * Earlier versions of the focused board: the server's saved copies, newest first. Picking one
 * asks first, then puts the board back through the board's sync (see BoardSync.restore). The
 * server saves the board as it was just before, so a restore shows up here to be undone too.
 */
@Composable
fun HistoryDialog(workspace: Workspace) {
    if (!workspace.historyOpen) return
    val session = workspace.session
    val state = workspace.focused
    val boardId = state.boardId
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<HistoryEntry>?>(null) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // text, isError
    var confirming by remember { mutableStateOf<HistoryEntry?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reloads by remember { mutableStateOf(0) }
    LaunchedEffect(boardId, reloads) {
        if (boardId == null) return@LaunchedEffect
        try {
            entries = session.api.history(boardId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            session.markOffline(e)
            message = "Couldn't load the earlier versions — is the server reachable?" to true
        }
    }

    fun restore(entry: HistoryEntry) {
        val id = boardId ?: return
        val sync = state.sync ?: return
        busy = true
        scope.launch {
            val error = try {
                val old = session.api.historyEntry(id, entry.id)
                sync.restore(entry.id, old.project)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                session.markOffline(e)
                "Couldn't fetch that version — is the server reachable?"
            }
            busy = false
            confirming = null
            message = (error ?: "Put back to ${entryLabel(entry)}. To undo, pick the newest version below.") to (error != null)
            if (error == null) state.status = "Board put back to ${formatDay(entry.savedAt)} ${formatClock(entry.savedAt)}"
            reloads++
        }
    }

    ModalCard("Earlier versions of ${state.project.name}", onDismiss = { workspace.historyOpen = false }, width = 460.dp) {
        Text(
            "A copy is kept from before the first change in every half hour, and before every restore. Chats aren't changed.",
            fontSize = 11.sp,
            color = TextFaint,
        )
        message?.let { (text, isError) -> Text(text, fontSize = 12.sp, color = if (isError) DangerRed else TextSecondary) }
        val pending = confirming
        if (pending != null) {
            Column(
                Modifier.fillMaxWidth().background(Accent.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                    .border(1.dp, Accent.copy(alpha = 0.4f), RoundedCornerShape(8.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(entryLabel(pending), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Text("Put the board back to how it was then? You can undo this from this list.", fontSize = 12.sp, color = TextPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = { if (!busy) restore(pending) }, enabled = !busy, shape = RoundedCornerShape(6.dp)) {
                        Text(if (busy) "Putting it back…" else "Put it back", fontSize = 12.sp)
                    }
                    CompactOutlinedButton("Cancel", onClick = { if (!busy) confirming = null })
                }
            }
        }
        val list = entries
        when {
            list == null -> if (message == null) Text("Loading…", fontSize = 12.sp, color = TextFaint)
            list.isEmpty() -> Text("No earlier versions yet: one is kept once the board changes.", fontSize = 12.sp, color = TextFaint)
            else -> Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                list.forEach { entry ->
                    Text(
                        entryLabel(entry),
                        fontSize = 13.sp,
                        color = if (confirming?.id == entry.id) Accent else TextPrimary,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                            .clickable(enabled = !busy) { confirming = entry; message = null }
                            .padding(horizontal = 6.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
}

/** "Today 15:40, 12 cards, 9 links". */
private fun entryLabel(entry: HistoryEntry): String =
    "${formatDay(entry.savedAt)} ${formatClock(entry.savedAt)}, ${count(entry.cards, "card")}, ${count(entry.links, "link")}"

private fun count(n: Int, what: String) = if (n == 1) "1 $what" else "$n ${what}s"

/** First-run tips: how this client's gestures work. "Got it" hides them for good on this device. */
@Composable
fun TipsCard(workspace: Workspace, compact: Boolean, modifier: Modifier = Modifier) {
    if (!workspace.tipsVisible || !workspace.focused.isLoaded) return
    val tips = if (compact) {
        listOf(
            "Tap a card (in Boards & nodes, or on the Map) to open its chat.",
            "On the Map, drag a card to move it.",
            "Double-tap empty space on the Map for a new card, or turn a chat message into one.",
            "Drag the dot on a card's right edge onto another card to link them.",
            "Activity and Earlier versions are in Menu, top right.",
        )
    } else {
        listOf(
            "Double-click empty space for a new card (or + Add).",
            "Click a card to open its chat on the right; tick its box when it's done.",
            "Drag the dot on a card's right edge onto another card to link them.",
            "Right-click for more. Boards, Activity and Earlier versions are under the board's name.",
        )
    }
    Column(
        modifier.widthIn(max = 340.dp).background(PanelBackground, RoundedCornerShape(10.dp))
            .border(1.dp, Accent.copy(alpha = 0.5f), RoundedCornerShape(10.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text("Getting started", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        tips.forEach { Text("- $it", fontSize = 12.sp, color = TextSecondary) }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            FilledTonalButton(onClick = { workspace.dismissTips() }, shape = RoundedCornerShape(6.dp)) {
                Text("Got it", fontSize = 12.sp)
            }
        }
    }
}

/** Text shared into the app while no board is open: it becomes a card on the next board opened. */
@Composable
fun SharedTextBanner(workspace: Workspace) {
    val text = workspace.session.sharedText ?: return
    if (workspace.focused.isLoaded) return
    Row(
        Modifier.fillMaxWidth().background(Accent.copy(alpha = 0.18f)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Open a board to add \"${text.lineSequence().first().take(40)}\" as a card.",
            fontSize = 12.sp,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            "Discard",
            fontSize = 12.sp,
            color = Accent,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { workspace.session.sharedText = null }
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** "Might connect to:" under a card with few links; tapping one links them (undoable). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectSuggestions(state: AppState, node: Node) {
    val suggestions = remember(state.project, state.messages, node.id) { state.connectionSuggestions(node) }
    if (suggestions.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Might connect to:", fontSize = 11.sp, color = TextFaint)
        suggestions.forEach { other ->
            Text(
                other.title.take(40),
                fontSize = 11.sp,
                color = Accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, PanelBorder, RoundedCornerShape(10.dp))
                    .clickable { state.connectSuggested(node.id, other.id) }.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}
