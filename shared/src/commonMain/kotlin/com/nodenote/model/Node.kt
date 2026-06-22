package com.nodenote.model

import kotlinx.serialization.Serializable

/**
 * A single card on the canvas.
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
) {
    val centerX: Float get() = x + width / 2f
    val centerY: Float get() = y + height / 2f

    companion object {
        const val DEFAULT_WIDTH = 190f
        const val DEFAULT_HEIGHT = 92f
    }
}
