package com.nodenote.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Where a node stands as a piece of work. [None] is a plain topic/braindump
 * node; the rest track issues and improvements through to done.
 */
@Serializable
enum class NodeStatus(val label: String, val colorArgb: Long) {
    None("No status", 0xFF8B94A0),
    Open("Open", 0xFF5B9CFF),
    InProgress("In progress", 0xFFE5B25B),
    Blocked("Blocked", 0xFFE2606B),
    Solved("Solved", 0xFF53C27C),
    Parked("Parked", 0xFF9AA5B1),
    ;

    /** Counts toward a board's "open" total (still needs someone's attention). */
    val isActive: Boolean get() = this == Open || this == InProgress || this == Blocked
}

/**
 * A single card on the canvas — a topic, issue, machine, process… Each node
 * also owns a chat thread (see [Message.nodeId]).
 *
 * Positions and sizes are in world units (density-independent pixels), so a saved
 * project lays out identically on a 1x Windows monitor and a 3x iPhone screen.
 * (x, y) is the top-left corner of the card.
 */
@Serializable
data class Node(
    val id: String,
    /** Node type id — resolved to a [NodeTypeDef] (built-in or project custom) via the catalog. */
    val type: String,
    val title: String,
    val description: String = "",
    val x: Float,
    val y: Float,
    val width: Float = DEFAULT_WIDTH,
    val height: Float = DEFAULT_HEIGHT,
    val properties: Map<String, String> = emptyMap(),
    val attachments: List<Attachment> = emptyList(),
    val status: NodeStatus = NodeStatus.None,
    /** 0 = not rated, otherwise 1 (minor) … 10 (critical). */
    val importance: Int = 0,
    /** Who is driving this node (free text, usually a user name). */
    val owner: String = "",
    /** Current state of the topic in a few lines — written by hand now, by AI later. */
    val summary: String = "",
    /** Epoch millis; 0 for nodes created before timestamps existed. */
    val createdAt: Long = 0,
    val createdBy: String = "",
    /** Extra words that should route thoughts here when sorting (free text, e.g. "VF-2, spindle, warm-up"). */
    val keywords: String = "",
    /** How big a job this is: 0 = not rated, otherwise 1 (an afternoon) … 10 (a major project). With [importance] it finds the quick wins. */
    val effort: Int = 0,
    /** Kept off the screen while presenting and out of exports — true, but not for everyone's eyes. */
    @SerialName("private") val isPrivate: Boolean = false,
    /** Epoch millis when the status last became Solved; 0 while it isn't. Dates the Wins list. */
    val solvedAt: Long = 0,
    /** Card size multiplier (1 = normal, 2 = twice as big, text included). [width]/[height] already include it. */
    val scale: Float = 1f,
) {
    val centerX: Float get() = x + width / 2f
    val centerY: Float get() = y + height / 2f

    companion object {
        const val DEFAULT_WIDTH = 190f
        const val DEFAULT_HEIGHT = 92f
        const val MIN_SCALE = 0.6f
        const val MAX_SCALE = 4f
    }
}

/** Importance and effort are always 0 (unrated) or 1..10. */
fun clampImportance(value: Int): Int = value.coerceIn(0, 10)
