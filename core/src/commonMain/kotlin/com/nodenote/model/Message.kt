package com.nodenote.model

import kotlinx.serialization.Serializable

/** What a chat message is, so decisions/actions/fixes can be pulled out of a thread later. */
@Serializable
enum class MessageKind(val label: String, val colorArgb: Long) {
    Note("Note", 0xFF8B94A0),
    Issue("Issue", 0xFFE2606B),
    Idea("Idea", 0xFFF0C04A),
    Decision("Decision", 0xFF9B7BFF),
    Action("Action", 0xFF5B9CFF),
    Fix("Fix", 0xFF53C27C),
}

/**
 * One chat message. Every message lives on a board and, usually, under one
 * node ([nodeId]); an empty [nodeId] puts it in the board's inbox — a raw
 * braindump waiting to be filed under a node (or turned into one).
 *
 * Messages can reference other nodes: [linkedNodeIds] (kept in sync with the
 * `[[Node title]]` links in [text]) makes a message show up in those nodes'
 * chats too, which is what lets threads cross between nodes.
 *
 * The server owns [createdAt], [editedAt] and [seq]; clients send 0.
 * [seq] increases on every create/update across the whole server, so a client
 * catches up by asking for "everything after the highest seq I have".
 */
@Serializable
data class Message(
    val id: String,
    val boardId: String,
    val nodeId: String = "",
    val author: String,
    val text: String,
    val kind: MessageKind = MessageKind.Note,
    /** 0 = not rated, otherwise 1 (minor) … 10 (critical key detail). */
    val importance: Int = 0,
    val linkedNodeIds: List<String> = emptyList(),
    /** Only meaningful for [MessageKind.Action]: the action item is done. */
    val done: Boolean = false,
    val createdAt: Long = 0,
    val editedAt: Long = 0,
    /** Tombstone — deleted messages stay in the database so other clients learn about the delete. */
    val deleted: Boolean = false,
    val seq: Long = 0,
)

/** Matches `[[Title]]` and `[[Title|alias]]` links in message text. */
val NodeLinkRegex = Regex("""\[\[([^\[\]|]+)(?:\|[^\[\]]*)?]]""")

/**
 * Resolves the `[[Title]]` links in [text] to node ids on [project]
 * (case-insensitive title match; unknown titles are ignored).
 */
fun resolveNodeLinks(text: String, project: Project): List<String> {
    val byTitle = project.nodes.associateBy { it.title.trim().lowercase() }
    return NodeLinkRegex.findAll(text)
        .mapNotNull { byTitle[it.groupValues[1].trim().lowercase()]?.id }
        .distinct()
        .toList()
}
