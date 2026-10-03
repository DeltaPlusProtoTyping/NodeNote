package com.nodenote.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

/**
 * A named territory on the board: a tinted region behind a group of cards ("Lead time",
 * "Sales pipeline"). Cards belong to an area by where they sit (the card's centre inside
 * the rectangle), so dragging a card in or out is all it takes, and moving the area carries
 * its cards along. A private area keeps everything in it off the screen while presenting
 * and out of exports.
 *
 * (x, y) is the top-left corner, in world units like [Node].
 */
@Serializable
data class Area(
    val id: String,
    val title: String = "",
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    /** Tint as 0xAARRGGBB; 0 = [DEFAULT_COLOR]. */
    val color: Long = 0,
    @SerialName("private") val isPrivate: Boolean = false,
    val createdAt: Long = 0,
    val createdBy: String = "",
) {
    val right: Float get() = x + width
    val bottom: Float get() = y + height
    val tint: Long get() = if (color == 0L) DEFAULT_COLOR else color

    fun contains(px: Float, py: Float): Boolean = px >= x && px <= right && py >= y && py <= bottom

    /** Holds all of [other]: an area drawn inside this one moves (and hides) with it. */
    fun encloses(other: Area): Boolean = other.x >= x && other.right <= right && other.y >= y && other.bottom <= bottom

    companion object {
        const val DEFAULT_COLOR = 0xFF1D9E75

        /** Space around the cards an area is drawn around; the top gets [TITLE_ROOM] more for the name. */
        const val PADDING = 40f
        const val TITLE_ROOM = 34f
        const val MIN_WIDTH = 160f
        const val MIN_HEIGHT = 110f

        /** Tints offered for areas, the default first: teal, blue, violet, pink, orange, slate. */
        val Palette: List<Long> = listOf(DEFAULT_COLOR, 0xFF5B9CFF, 0xFF9B7BFF, 0xFFD97BC8, 0xFFE58E5B, 0xFF8B94A0)
    }
}

/** The area a card belongs to: the smallest one holding its centre (areas can nest), or null. */
fun Project.areaOf(node: Node): Area? =
    areas.filter { it.contains(node.centerX, node.centerY) }.minByOrNull { it.width * it.height }

/** Cards whose centre is inside [area], including those in areas nested in it. */
fun Project.cardsIn(area: Area): List<Node> = nodes.filter { area.contains(it.centerX, it.centerY) }

/** Other areas lying wholly inside [area]. */
fun Project.areasInside(area: Area): List<Area> = areas.filter { it.id != area.id && area.encloses(it) }

/**
 * Ids of the cards kept out of sight while presenting and out of exports: cards marked
 * private, and every card in a private area.
 */
fun Project.privateNodeIds(): Set<String> {
    val privateAreas = areas.filter { it.isPrivate }
    return nodes.filter { n -> n.isPrivate || privateAreas.any { it.contains(n.centerX, n.centerY) } }.mapTo(HashSet()) { it.id }
}

/** Ids of the areas hidden along with private cards: private areas and any area drawn inside one. */
fun Project.privateAreaIds(): Set<String> {
    val privateAreas = areas.filter { it.isPrivate }
    if (privateAreas.isEmpty()) return emptySet()
    return areas.filter { a -> a.isPrivate || privateAreas.any { it.id != a.id && it.encloses(a) } }.mapTo(HashSet()) { it.id }
}

/** This area resized to hug [cards], with padding and room for its name on top; unchanged when there are none. */
fun Area.fittedTo(cards: List<Node>): Area {
    if (cards.isEmpty()) return this
    val left = cards.minOf { it.x } - Area.PADDING
    val top = cards.minOf { it.y } - Area.PADDING - Area.TITLE_ROOM
    val right = cards.maxOf { it.x + it.width } + Area.PADDING
    val bottom = cards.maxOf { it.y + it.height } + Area.PADDING
    return copy(x = left, y = top, width = max(right - left, Area.MIN_WIDTH), height = max(bottom - top, Area.MIN_HEIGHT))
}

/** The eight grips of a selected area: its corners and the middles of its sides. */
enum class AreaHandle(val left: Boolean, val top: Boolean, val right: Boolean, val bottom: Boolean) {
    TopLeft(true, true, false, false),
    Top(false, true, false, false),
    TopRight(false, true, true, false),
    Right(false, false, true, false),
    BottomRight(false, false, true, true),
    Bottom(false, false, false, true),
    BottomLeft(true, false, false, true),
    Left(true, false, false, false),
}

/**
 * This area with the sides [handle] holds moved by ([dx], [dy]) in world units; the other
 * sides stay put, and it never gets smaller than [Area.MIN_WIDTH] × [Area.MIN_HEIGHT].
 */
fun Area.resized(handle: AreaHandle, dx: Float, dy: Float): Area {
    var l = x
    var t = y
    var r = right
    var b = bottom
    if (handle.left) l = min(x + dx, r - Area.MIN_WIDTH)
    if (handle.right) r = max(right + dx, l + Area.MIN_WIDTH)
    if (handle.top) t = min(y + dy, b - Area.MIN_HEIGHT)
    if (handle.bottom) b = max(bottom + dy, t + Area.MIN_HEIGHT)
    return copy(x = l, y = t, width = r - l, height = b - t)
}
