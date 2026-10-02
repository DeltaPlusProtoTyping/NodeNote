package com.nodenote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodenote.model.Message
import com.nodenote.model.MessageKind
import com.nodenote.model.Node
import com.nodenote.model.resolveNodeLinks
import com.nodenote.sort.NodeMatcher
import com.nodenote.state.AppState
import com.nodenote.state.LocalAppState
import com.nodenote.theme.Accent
import com.nodenote.theme.DangerRed
import com.nodenote.theme.PanelBackground
import com.nodenote.theme.PanelBackgroundRaised
import com.nodenote.theme.PanelBorder
import com.nodenote.theme.TextFaint
import com.nodenote.theme.TextPrimary
import com.nodenote.theme.TextSecondary
import com.nodenote.theme.color

/**
 * The Chat tab. With one node selected it shows that node's thread (its own
 * messages plus mentions from other nodes); with nothing selected it is the
 * board feed — every message on the board, inbox braindumps included, each
 * tagged with the node it belongs to.
 */
@Composable
fun ChatView(state: AppState, modifier: Modifier = Modifier) {
    val node = state.selectedNode
    val all = state.messages
    val thread = remember(all, node?.id, state.feedInboxOnly) {
        when {
            node != null -> state.threadOf(node.id)
            state.feedInboxOnly -> all.filter { it.nodeId.isEmpty() }
            else -> all
        }
    }
    val filtering = state.chatMinImportance > 0 || state.chatKindFilter != null || state.chatSearch.isNotBlank()
    val visible = remember(thread, state.chatMinImportance, state.chatKindFilter, state.chatSearch, state.project.nodes) {
        val messages = thread.filter { m ->
            m.importance >= state.chatMinImportance &&
                (state.chatKindFilter == null || m.kind == state.chatKindFilter) &&
                (state.chatSearch.isBlank() || m.text.contains(state.chatSearch, ignoreCase = true) ||
                    m.author.contains(state.chatSearch, ignoreCase = true))
        }
        // "X created node Y" notices, woven in by time (hidden while filtering).
        val created = when {
            filtering || state.feedInboxOnly -> emptyList()
            node != null -> listOfNotNull(node.takeIf { it.createdAt > 0 })
            else -> state.project.nodes.filter { it.createdAt > 0 }
        }
        if (created.isEmpty()) messages
        else (messages + created.map(::nodeCreatedEvent)).sortedWith(compareBy<Message>({ it.createdAt }, { it.seq }))
    }

    // Opening a node's thread counts as reading it (the feed doesn't — see "Mark all read").
    // The read mark from *before* opening is kept for this visit, so what's new stays marked while you read.
    val readMarkAtOpen = remember(node?.id) { node?.let { state.lastRead[it.id] } }
    LaunchedEffect(node?.id, thread.size, thread.lastOrNull()?.seq) {
        if (node != null) state.markThreadRead(node.id)
    }

    Column(modifier) {
        ChatHeader(state, node)
        HorizontalDivider(color = PanelBorder)
        MessageList(
            state,
            visible,
            showNodeChips = node == null,
            contextNodeId = node?.id,
            readMark = { m -> readMarkAtOpen ?: state.lastRead[m.nodeId] ?: 0L },
            modifier = Modifier.weight(1f),
        )
        HorizontalDivider(color = PanelBorder)
        Composer(state, node)
    }
}

@Composable
private fun ChatHeader(state: AppState, node: Node?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (node != null) {
                val type = state.nodeTypeOf(node.type)
                TypeDot(type.color, 8.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    node.title.ifBlank { "(untitled)" },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (node.status != com.nodenote.model.NodeStatus.None) StatusPill(node.status)
                Spacer(Modifier.width(4.dp))
                ImportanceBadge(node.importance)
            } else {
                Text(
                    if (state.feedInboxOnly) "Unsorted thoughts" else "Board feed — all nodes",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (state.totalUnread > 0) {
                    Text(
                        "Mark all read",
                        fontSize = 11.sp,
                        color = Accent,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { state.markAllRead() }.padding(4.dp),
                    )
                }
            }
        }
        // Filters: importance floor, kind, text search.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(0 to "All", 5 to "5+", 7 to "7+", 9 to "9+").forEach { (min, label) ->
                FilterChip(label, selected = state.chatMinImportance == min, color = importanceColor(min.coerceAtLeast(1))) {
                    state.chatMinImportance = min
                }
            }
            KindFilter(state)
            if (node == null) {
                FilterChip("Unsorted", selected = state.feedInboxOnly, color = Accent) { state.feedInboxOnly = !state.feedInboxOnly }
            }
            CompactTextField(
                value = state.chatSearch,
                onValueChange = { state.chatSearch = it },
                modifier = Modifier.weight(1f),
                placeholder = "Search…",
                minHeight = 24.dp,
                textStyle = TextStyle(color = TextPrimary, fontSize = 11.sp),
            )
        }
    }
}

@Composable
fun FilterChip(label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .height(22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) color.copy(alpha = 0.22f) else Color.Transparent)
            .border(1.dp, if (selected) color else PanelBorder, RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 10.sp, color = if (selected) TextPrimary else TextSecondary, maxLines = 1)
    }
}

@Composable
private fun KindFilter(state: AppState) {
    var open by remember { mutableStateOf(false) }
    Box {
        val kind = state.chatKindFilter
        FilterChip(kind?.label ?: "Any kind", selected = kind != null, color = kind?.color ?: Accent) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Any kind", fontSize = 12.sp) }, onClick = { state.chatKindFilter = null; open = false })
            MessageKind.entries.forEach { k ->
                DropdownMenuItem(text = { KindPill(k) }, onClick = { state.chatKindFilter = k; open = false })
            }
        }
    }
}

// ---- Message list ----

@Composable
private fun MessageList(
    state: AppState,
    messages: List<Message>,
    showNodeChips: Boolean,
    contextNodeId: String?,
    readMark: (Message) -> Long,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    // Jump to the newest message when switching threads; follow new messages
    // while the reader is already at the bottom (or when they're mine).
    LaunchedEffect(contextNodeId) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.size - 1)
    }
    LaunchedEffect(messages.size) {
        if (messages.isEmpty()) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        val mine = messages.last().author == state.session.userName
        if (mine || lastVisible >= messages.size - 3) listState.animateScrollToItem(messages.size - 1)
    }

    if (messages.isEmpty()) {
        Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                when {
                    !state.isLoaded -> "Loading…"
                    state.chatMinImportance > 0 || state.chatKindFilter != null || state.chatSearch.isNotBlank() ->
                        "No messages match the filters."
                    contextNodeId != null -> "No messages yet. Dump what you know about this below —\nrate the key details 1–10."
                    else -> "Nothing posted yet. Messages sent with no node selected land in the inbox."
                },
                fontSize = 12.sp,
                color = TextFaint,
                lineHeight = 17.sp,
            )
        }
        return
    }

    LazyColumn(modifier.fillMaxWidth(), state = listState) {
        items(messages.size, key = { messages[it].id }) { i ->
            val m = messages[i]
            val prev = messages.getOrNull(i - 1)
            if (prev == null || dayKey(prev.createdAt) != dayKey(m.createdAt)) {
                DaySeparator(m.createdAt)
            } else if (showNodeChips && prev.nodeId != m.nodeId) {
                // Board feed: a thin line wherever the conversation moves to another node.
                HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = PanelBorder)
            }
            if (m.isNodeCreatedEvent) {
                NodeCreatedNotice(state, m)
                return@items
            }
            val grouped = prev != null && !prev.isNodeCreatedEvent && prev.author == m.author && prev.nodeId == m.nodeId &&
                dayKey(prev.createdAt) == dayKey(m.createdAt) && m.createdAt - prev.createdAt < 5 * 60_000
            MessageItem(
                state,
                m,
                showNodeChip = showNodeChips || (contextNodeId != null && m.nodeId != contextNodeId),
                compact = grouped,
                unread = m.author != state.session.userName && m.seq > readMark(m),
            )
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

// ---- "node created" notices ----
// Derived from each node's createdAt/createdBy — not stored as messages, so they
// never reach the server or the vault chat.

private const val NODE_CREATED_PREFIX = "event:created:"

private fun nodeCreatedEvent(node: Node) = Message(
    id = NODE_CREATED_PREFIX + node.id,
    boardId = "",
    nodeId = node.id,
    author = node.createdBy,
    text = "",
    createdAt = node.createdAt,
)

private val Message.isNodeCreatedEvent: Boolean get() = id.startsWith(NODE_CREATED_PREFIX)

@Composable
private fun NodeCreatedNotice(state: AppState, event: Message) {
    val node = state.project.nodes.find { it.id == event.nodeId } ?: return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            (event.author.ifBlank { "Someone" }) + " created ",
            fontSize = 11.sp,
            color = TextFaint,
        )
        NodeChip(
            node.title.ifBlank { "(untitled)" },
            state.nodeTypeOf(node.type).color,
            onClick = { state.focusNode(node.id) },
            modifier = Modifier.widthIn(max = 180.dp),
        )
        Text("  " + formatClock(event.createdAt), fontSize = 10.sp, color = TextFaint)
    }
}

@Composable
private fun DaySeparator(ms: Long) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = PanelBorder)
        Text(formatDay(ms), fontSize = 10.sp, color = TextFaint, modifier = Modifier.padding(horizontal = 8.dp))
        HorizontalDivider(Modifier.weight(1f), color = PanelBorder)
    }
}

@Composable
private fun MessageItem(state: AppState, m: Message, showNodeChip: Boolean, compact: Boolean, unread: Boolean) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val mine = m.author == state.session.userName
    val pending = m.id in state.pendingMessageIds
    val failed = m.id in state.failedMessageIds

    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .hoverable(interaction)
            .background(
                when {
                    state.editingMessageId == m.id -> Accent.copy(alpha = 0.10f)
                    hovered -> PanelBackgroundRaised.copy(alpha = 0.6f)
                    else -> Color.Transparent
                },
            ),
    ) {
        // Importance stripe: the key details stand out while scrolling.
        Box(Modifier.width(3.dp).fillMaxHeight().background(if (m.importance > 0) importanceColor(m.importance) else Color.Transparent))
        Column(Modifier.weight(1f).padding(start = 9.dp, end = 10.dp, top = if (compact) 1.dp else 6.dp, bottom = 3.dp)) {
            if (!compact || m.importance > 0 || m.kind != MessageKind.Note || showNodeChip) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.author, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = authorColor(m.author), maxLines = 1)
                    Spacer(Modifier.width(6.dp))
                    Text(formatClock(m.createdAt), fontSize = 10.sp, color = TextFaint)
                    if (m.editedAt > 0) Text(" · edited", fontSize = 10.sp, color = TextFaint)
                    if (unread) {
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.size(6.dp).background(Accent, RoundedCornerShape(3.dp)))
                    }
                    if (m.kind != MessageKind.Note) {
                        Spacer(Modifier.width(6.dp))
                        KindPill(m.kind)
                    }
                    if (showNodeChip) {
                        Spacer(Modifier.width(6.dp))
                        MessageNodeChip(state, m)
                    }
                    Spacer(Modifier.weight(1f))
                    MessageActions(state, m, mine, visible = hovered)
                }
            }
            val headerless = compact && m.importance == 0 && m.kind == MessageKind.Note && !showNodeChip
            Row(verticalAlignment = Alignment.Top) {
                SelectionContainer(Modifier.weight(1f)) {
                    Text(
                        linkifyMessage(m.text, state.project, Accent) { id -> state.focusNode(id) },
                        fontSize = 13.sp,
                        color = if (m.kind == MessageKind.Action && m.done) TextFaint else TextPrimary,
                        lineHeight = 18.sp,
                        fontStyle = if (pending) FontStyle.Italic else FontStyle.Normal,
                    )
                }
                // Follow-up messages have no header row, so their actions sit beside the text.
                if (headerless) MessageActions(state, m, mine, visible = hovered)
            }
            if (m.kind == MessageKind.Action) {
                Row(
                    Modifier.padding(top = 2.dp).clip(RoundedCornerShape(4.dp)).clickable { state.setMessageDone(m.id, !m.done) }.padding(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(12.dp).clip(RoundedCornerShape(3.dp))
                            .background(if (m.done) MessageKind.Fix.color else Color.Transparent)
                            .border(1.dp, if (m.done) MessageKind.Fix.color else TextFaint, RoundedCornerShape(3.dp)),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(if (m.done) "Done" else "Open action", fontSize = 10.sp, color = TextSecondary)
                }
            }
            when {
                failed -> Text(
                    "Not sent — click to retry",
                    fontSize = 10.sp,
                    color = DangerRed,
                    modifier = Modifier.clickable { state.retryMessage(m.id) }.padding(top = 2.dp),
                )
                pending -> Text("sending…", fontSize = 10.sp, color = TextFaint)
            }
        }
    }
}

@Composable
private fun MessageNodeChip(state: AppState, m: Message) {
    if (m.nodeId.isEmpty()) {
        Pill("Unsorted", Accent, onClick = { state.rightTab = AppState.RightTab.Sort })
        return
    }
    val node = state.project.nodes.find { it.id == m.nodeId }
    if (node == null) {
        Text("(removed node)", fontSize = 10.sp, color = TextFaint)
        return
    }
    NodeChip(
        node.title.ifBlank { "(untitled)" },
        state.nodeTypeOf(node.type).color,
        onClick = { state.focusNode(node.id) },
        modifier = Modifier.widthIn(max = 150.dp),
    )
}

/** Importance badge (click to rate) and the "…" menu; faded in on hover so the thread stays calm. */
@Composable
private fun MessageActions(state: AppState, m: Message, mine: Boolean, visible: Boolean) {
    var rateOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var moveOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            if (m.importance > 0) {
                ImportanceBadge(m.importance, Modifier.clickable { rateOpen = true })
            } else if (visible) {
                Text(
                    "Rate",
                    fontSize = 10.sp,
                    color = TextSecondary,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { rateOpen = true }.padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
            DropdownMenu(expanded = rateOpen, onDismissRequest = { rateOpen = false }) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text("How important is this detail?", fontSize = 11.sp, color = TextSecondary)
                    Spacer(Modifier.height(6.dp))
                    ImportancePicker(m.importance, onChange = {
                        state.setMessageImportance(m.id, it)
                        rateOpen = false
                    })
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        Box {
            if (visible || menuOpen) {
                Text(
                    "•••",
                    fontSize = 10.sp,
                    color = TextSecondary,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { menuOpen = true }.padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (mine) {
                    MenuRow("Edit") { state.editingMessageId = m.id; menuOpen = false }
                }
                MenuRow("Move to node…") { menuOpen = false; moveOpen = true }
                MenuRow("Make a node from this") { state.makeNodeFromMessage(m.id); menuOpen = false }
                HorizontalDivider(color = PanelBorder)
                Text("Kind", fontSize = 10.sp, color = TextFaint, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
                Row(Modifier.width(260.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    MessageKind.entries.forEach { k ->
                        KindPill(k, filled = k == m.kind, onClick = { state.setMessageKind(m.id, k); menuOpen = false })
                    }
                }
                if (mine) {
                    HorizontalDivider(color = PanelBorder)
                    MenuRow("Delete", color = DangerRed) { state.deleteMessage(m.id); menuOpen = false }
                }
            }
            NodePickerMenu(
                state = state,
                expanded = moveOpen,
                includeInbox = m.nodeId.isNotEmpty(),
                onDismiss = { moveOpen = false },
                onPick = { id -> state.moveMessage(m.id, id); moveOpen = false },
            )
        }
    }
}

@Composable
private fun MenuRow(label: String, color: Color = TextPrimary, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label, fontSize = 12.sp, color = color) }, onClick = onClick)
}

/** Searchable node list in a dropdown; [onPick] gets a node id ("" = inbox). */
@Composable
fun NodePickerMenu(state: AppState, expanded: Boolean, includeInbox: Boolean, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by remember(expanded) { mutableStateOf("") }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        CompactTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.width(240.dp).padding(horizontal = 8.dp),
            placeholder = "Find node…",
            minHeight = 26.dp,
        )
        Spacer(Modifier.height(4.dp))
        if (includeInbox) MenuRow("Back to Unsorted", color = Accent) { onPick("") }
        state.project.nodes
            .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
            .sortedBy { it.title.lowercase() }
            .take(40)
            .forEach { n ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TypeDot(state.nodeTypeOf(n.type).color, 7.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(n.title.ifBlank { "(untitled)" }, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    onClick = { onPick(n.id) },
                )
            }
    }
}

// ---- Composer ----

private val OpenLinkRegex = Regex("""\[\[([^\[\]]*)$""")

/**
 * The message box. On a node it posts to that node. With no node (board feed,
 * Sort tab) it is the dump bar: Enter drops the thought into Unsorted, and as
 * you type it offers the nodes it probably belongs under — tap one to file it
 * straight there.
 */
@Composable
internal fun Composer(state: AppState, node: Node?) {
    val editing = state.editingMessageId?.let { state.messageById(it) }
    var value by remember { mutableStateOf(TextFieldValue("")) }
    val focusRequester = remember { FocusRequester() }

    // A half-typed message holds off automatic update reloads.
    val hasDraft = value.text.isNotBlank()
    LaunchedEffect(hasDraft) { state.draftActive = hasDraft }
    DisposableEffect(state) { onDispose { state.draftActive = false } }

    // Entering edit mode loads the message text; leaving it clears the box.
    LaunchedEffect(editing?.id) {
        value = if (editing != null) TextFieldValue(editing.text, TextRange(editing.text.length)) else TextFieldValue("")
        if (editing != null) focusRequester.requestFocus()
    }

    // `[[partial` right before the cursor → suggest node titles to complete the link.
    val openLink = remember(value) {
        OpenLinkRegex.find(value.text.substring(0, value.selection.start.coerceIn(0, value.text.length)))
    }
    val suggestions = remember(openLink?.value, state.project.nodes) {
        val q = openLink?.groupValues?.get(1)?.trim() ?: return@remember emptyList()
        state.project.nodes.filter { q.isEmpty() || it.title.contains(q, ignoreCase = true) }.take(6)
    }

    fun completeLink(n: Node) {
        val match = openLink ?: return
        val before = value.text.substring(0, match.range.first)
        val after = value.text.substring(value.selection.start)
        val inserted = "[[${n.title}]]"
        value = TextFieldValue(before + inserted + after, TextRange(before.length + inserted.length))
    }

    // Dump bar only: where this thought probably goes (needs a couple of real words to guess).
    val dumping = node == null && editing == null
    val fileTo = remember(value.text, state.matcher) {
        if (!dumping || NodeMatcher.tokenize(value.text).size < 2) emptyList()
        else state.suggestionsFor(value.text, resolveNodeLinks(value.text, state.project))
    }

    fun submit(toNode: Node? = null) {
        val text = value.text
        if (text.isBlank()) return
        if (editing != null) {
            state.editMessageText(editing.id, text)
            state.editingMessageId = null
        } else {
            state.sendMessage(text, toNode?.id ?: node?.id ?: "")
            state.status = when {
                toNode != null -> "Filed under ${toNode.title}"
                node == null -> "Dropped in Unsorted"
                else -> state.status
            }
        }
        value = TextFieldValue("")
    }

    Column(Modifier.fillMaxWidth().background(PanelBackground).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (editing != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Editing your message", fontSize = 11.sp, color = Accent, modifier = Modifier.weight(1f))
                Text(
                    "Cancel",
                    fontSize = 11.sp,
                    color = TextSecondary,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { state.editingMessageId = null }.padding(4.dp),
                )
            }
        } else {
            // Kind + importance are set before sending, so key details are rated as they're written.
            // One compact line: both open small pickers.
            Row(verticalAlignment = Alignment.CenterVertically) {
                ComposerKindPicker(state)
                Spacer(Modifier.width(6.dp))
                ComposerImportancePicker(state)
                Spacer(Modifier.weight(1f))
                Text("Enter sends · Shift+Enter new line", fontSize = 10.sp, color = TextFaint, maxLines = 1)
            }
        }

        if (suggestions.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(PanelBackgroundRaised)
                    .border(1.dp, PanelBorder, RoundedCornerShape(6.dp)).padding(4.dp),
            ) {
                suggestions.forEachIndexed { i, n ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                            .background(if (i == 0) Accent.copy(alpha = 0.12f) else Color.Transparent)
                            .clickable {
                                completeLink(n)
                                focusRequester.requestFocus() // keep typing after picking with the mouse
                            }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TypeDot(state.nodeTypeOf(n.type).color, 7.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(n.title.ifBlank { "(untitled)" }, fontSize = 12.sp, color = TextPrimary, maxLines = 1)
                    }
                }
            }
        }

        if (fileTo.isNotEmpty() && suggestions.isEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text("File under", fontSize = 10.sp, color = TextFaint)
                fileTo.forEach { n -> FileChip(state, n) { submit(toNode = n) } }
            }
        }

        Row(verticalAlignment = Alignment.Bottom) {
            ChatTextField(
                value = value,
                onValueChange = { value = it },
                placeholder = when {
                    editing != null -> "Edit message…"
                    node != null -> "Message ${node.title.ifBlank { "this node" }}…  ([[ links a node)"
                    else -> "Dump a thought… Enter drops it in Unsorted"
                },
                onEnter = {
                    if (suggestions.isNotEmpty()) completeLink(suggestions.first()) else submit()
                },
                onEscape = { if (editing != null) state.editingMessageId = null },
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
            )
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .height(34.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (value.text.isBlank()) PanelBackgroundRaised else Accent)
                    .clickable(enabled = value.text.isNotBlank()) { submit() }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when {
                        editing != null -> "Save"
                        node == null -> "Dump"
                        else -> "Send"
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (value.text.isBlank()) TextFaint else Color.White,
                )
            }
        }
    }
}

/** One-tap destination: the node's title, with its parent underneath for context. */
@Composable
internal fun FileChip(state: AppState, node: Node, primary: Boolean = false, onClick: () -> Unit) {
    val parent = state.parentOf(node.id)
    Row(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (primary) Accent.copy(alpha = 0.16f) else PanelBackgroundRaised)
            .border(1.dp, if (primary) Accent.copy(alpha = 0.7f) else PanelBorder, RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TypeDot(state.nodeTypeOf(node.type).color, 7.dp)
        Spacer(Modifier.width(6.dp))
        Column {
            Text(
                node.title.ifBlank { "(untitled)" },
                fontSize = 12.sp,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp),
            )
            if (parent != null) {
                Text(
                    "in ${parent.title.ifBlank { "(untitled)" }}",
                    fontSize = 9.sp,
                    color = TextFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 200.dp),
                )
            }
        }
    }
}

@Composable
private fun ComposerKindPicker(state: AppState) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KindPill(state.composerKind, filled = state.composerKind != MessageKind.Note, onClick = { open = true })
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MessageKind.entries.forEach { k ->
                DropdownMenuItem(text = { KindPill(k, filled = k == state.composerKind) }, onClick = { state.composerKind = k; open = false })
            }
        }
    }
}

@Composable
private fun ComposerImportancePicker(state: AppState) {
    var open by remember { mutableStateOf(false) }
    Box {
        val value = state.composerImportance
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, if (value > 0) importanceColor(value) else PanelBorder, RoundedCornerShape(10.dp))
                .clickable { open = true }.padding(horizontal = 7.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (value > 0) "Key detail" else "Rate", style = tightText(10.sp, color = if (value > 0) TextPrimary else TextSecondary))
            if (value > 0) {
                Spacer(Modifier.width(5.dp))
                ImportanceBadge(value, size = 14.dp)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                Text("How important is this detail?", fontSize = 11.sp, color = TextSecondary)
                Spacer(Modifier.height(6.dp))
                ImportancePicker(value, onChange = { state.composerImportance = it; open = false })
            }
        }
    }
}

/**
 * Multi-line message box: Enter sends (or completes a [[link]]), Shift+Enter
 * inserts a newline. Reports focus to AppState like CompactTextField, so
 * canvas shortcuts (Delete, arrows…) pause while typing.
 */
@Composable
private fun ChatTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    onEnter: () -> Unit,
    onEscape: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val appState = LocalAppState.current
    DisposableEffect(appState) {
        onDispose { if (focused) appState?.let { it.activeTextFields-- } }
    }
    val shape = RoundedCornerShape(6.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = TextStyle(color = TextPrimary, fontSize = 13.sp, lineHeight = 18.sp),
        cursorBrush = SolidColor(Accent),
        modifier = modifier
            .heightIn(min = 34.dp, max = 320.dp)
            .onFocusChanged {
                if (it.isFocused != focused) appState?.let { s -> s.activeTextFields += if (it.isFocused) 1 else -1 }
                focused = it.isFocused
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    (event.key == Key.Enter || event.key == Key.NumPadEnter) && !event.isShiftPressed -> {
                        onEnter()
                        true
                    }
                    // Shift+Enter: new line. Inserted here because the text field
                    // ignores Enter with a modifier held (in the browser especially).
                    event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                        val start = minOf(value.selection.start, value.selection.end)
                        val end = maxOf(value.selection.start, value.selection.end)
                        val text = value.text.substring(0, start) + "\n" + value.text.substring(end)
                        onValueChange(TextFieldValue(text, TextRange(start + 1)))
                        true
                    }
                    event.key == Key.Escape -> {
                        onEscape()
                        false
                    }
                    else -> false
                }
            }
            .background(PanelBackgroundRaised, shape)
            .border(1.dp, if (focused) Accent else PanelBorder, shape)
            .padding(horizontal = 9.dp, vertical = 8.dp),
        decorationBox = { inner ->
            Box {
                if (value.text.isEmpty()) Text(placeholder, fontSize = 12.sp, color = TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                inner()
            }
        },
    )
}
