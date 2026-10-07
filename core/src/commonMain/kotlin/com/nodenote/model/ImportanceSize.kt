package com.nodenote.model

import kotlin.math.abs
import kotlin.math.ln

/*
 * A card's size is its importance: one size per level, so what matters stands out on the map.
 * Levels keep the sizes the old size presets had (Small 0.8 = 1, Normal = 3, Large 1.5 = 7,
 * Huge 2.2 = 10), and urgent (11–12) goes bigger than anything else. Unrated cards are Normal.
 */
private val LevelScales = floatArrayOf(0.8f, 0.9f, 1f, 1.1f, 1.2f, 1.35f, 1.5f, 1.65f, 1.85f, 2.2f, 2.6f, 3f)

/** The card size (scale) for an importance; 0 (not rated) is the normal size. */
fun scaleForImportance(importance: Int): Float =
    if (importance <= 0) 1f else LevelScales[importance.coerceAtMost(MAX_IMPORTANCE) - 1]

/** The importance whose size is closest to [scale] (1..12): what picking a size or dragging a card's corner sets. */
fun importanceForScale(scale: Float): Int {
    val target = ln(scale.coerceAtLeast(0.01f))
    return (1..MAX_IMPORTANCE).minBy { abs(ln(LevelScales[it - 1]) - target) }
}

/** This card at [scale], grown or shrunk around its centre, so it stays in its area. */
fun Node.scaledAroundCenter(scale: Float): Node {
    if (scale == this.scale) return this
    val w = width / this.scale * scale
    val h = height / this.scale * scale
    return copy(scale = scale, width = w, height = h, x = centerX - w / 2f, y = centerY - h / 2f)
}

/**
 * This card with its size and importance in step: a rated card takes its level's size, and
 * a card sized by hand before sizes meant importance gets the level of its size.
 */
fun Node.sizedByImportance(): Node = when {
    importance > 0 -> scaledAroundCenter(scaleForImportance(importance))
    scale != 1f -> importanceForScale(scale).let { level -> copy(importance = level).scaledAroundCenter(scaleForImportance(level)) }
    else -> this
}

/** Every card sized by its importance (see [sizedByImportance]); the same board when nothing needs to change. */
fun Project.withSizesByImportance(): Project {
    val sized = nodes.map { it.sizedByImportance() }
    return if (sized.indices.all { sized[it] === nodes[it] }) this else copy(nodes = sized)
}
