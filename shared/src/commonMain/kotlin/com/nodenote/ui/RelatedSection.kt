package com.nodenote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.nodenote.api.RelatedDoc
import com.nodenote.api.RelatedItem
import com.nodenote.api.RelatedStatus
import com.nodenote.model.Node
import com.nodenote.state.AppState
import com.nodenote.theme.Accent
import com.nodenote.theme.PanelBackground
import com.nodenote.theme.PanelBorder
import com.nodenote.theme.TextFaint
import com.nodenote.theme.TextPrimary
import com.nodenote.theme.TextSecondary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Notes from outside the board that relate to this card (see the server's RelatedNotes). Only
 * shows for the one account allowed, closed until asked, and searches only when opened, so the
 * card and this panel stay as they were for everyone else.
 */
@Composable
fun RelatedSection(state: AppState, node: Node) {
    var status by remember(state) { mutableStateOf(RelatedStatus()) }
    LaunchedEffect(state) {
        status = try {
            state.session.api.relatedStatus()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RelatedStatus() // an older server, or offline: no section
        }
    }
    if (!status.available && !status.needsOwnSignIn) return

    HorizontalDivider(color = PanelBorder)
    if (status.needsOwnSignIn) {
        PanelCaption("Related notes")
        Text("Sign out, then sign in with your own password to see these.", fontSize = 11.sp, color = TextFaint)
        return
    }

    val boardId = state.boardId ?: return
    var open by remember(node.id) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).clickable { open = !open }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PanelCaption("Related notes")
        Spacer(Modifier.weight(1f))
        Text(if (open) "Hide" else "Show", fontSize = 11.sp, color = Accent)
    }
    if (!open) return

    var items by remember(node.id) { mutableStateOf<List<RelatedItem>?>(null) }
    var failed by remember(node.id) { mutableStateOf(false) }
    var reading by remember(node.id) { mutableStateOf<RelatedDoc?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        try {
            items = state.session.api.related(boardId, node.id).items
            failed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        }
    }
    LaunchedEffect(node.id, node.title) { load() }

    fun mark(item: RelatedItem, markState: String) {
        scope.launch {
            try {
                state.session.api.markRelated(boardId, node.id, item.id, markState)
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
            }
        }
    }

    val found = items
    when {
        failed -> Text("Couldn't load these. Try again in a moment.", fontSize = 11.sp, color = TextFaint)
        found == null -> Text("Looking...", fontSize = 11.sp, color = TextFaint)
        found.isEmpty() -> Text("Nothing found for this card.", fontSize = 11.sp, color = TextFaint)
        else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            found.forEach { item ->
                RelatedRow(
                    item,
                    onOpen = {
                        scope.launch {
                            try {
                                reading = state.session.api.relatedDoc(item.id)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                failed = true
                            }
                        }
                    },
                    onPin = { mark(item, if (item.pinned) "none" else "pin") },
                    onDismiss = { mark(item, "dismiss") },
                )
            }
        }
    }

    reading?.let { doc -> RelatedReader(doc) { reading = null } }
}

@Composable
private fun RelatedRow(item: RelatedItem, onOpen: () -> Unit, onPin: () -> Unit, onDismiss: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            item.title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clip(RoundedCornerShape(3.dp)).clickable(onClick = onOpen),
        )
        Text(metaLine(item.source, item.who, item.date), fontSize = 10.sp, color = TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (item.snippet.isNotBlank()) {
            Text(item.snippet, fontSize = 11.sp, color = TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RowAction(if (item.pinned) "Unpin" else "Pin", Accent, onPin)
            if (!item.pinned) RowAction("Not related", TextFaint, onDismiss)
        }
    }
}

@Composable
private fun RowAction(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 10.sp,
        color = color,
        modifier = Modifier.clip(RoundedCornerShape(3.dp)).clickable(onClick = onClick).padding(vertical = 2.dp),
    )
}

/** The whole note, to read over the app. */
@Composable
private fun RelatedReader(doc: RelatedDoc, close: () -> Unit) {
    Dialog(onDismissRequest = close) {
        Column(
            Modifier
                .widthIn(max = 640.dp)
                .heightIn(max = 620.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(PanelBackground)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(doc.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Text(metaLine(doc.source, doc.who, doc.date), fontSize = 11.sp, color = TextFaint)
                }
                CloseButton(onClick = close)
            }
            HorizontalDivider(color = PanelBorder)
            SelectionContainer(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Text(
                    if (doc.trimmed) doc.text + "\n\n(The rest of this note is cut off here.)" else doc.text,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = TextSecondary,
                )
            }
        }
    }
}

private fun metaLine(source: String, who: String, date: String): String =
    listOf(source, who, date).filter { it.isNotBlank() }.joinToString("  ·  ")
