package com.nodenote.model

import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * A board: the node graph two people build and chat around. (Still called
 * Project in code — it is the same document NodeNote saved as .json, so old
 * files import unchanged.) Chat messages are stored separately, per board.
 */
@Serializable
data class Project(
    val id: String,
    val name: String,
    val nodes: List<Node> = emptyList(),
    val edges: List<Edge> = emptyList(),
    val elements: List<CanvasElement> = emptyList(),
    // User-defined types travel with the project so they survive sharing/reopening.
    val customNodeTypes: List<NodeTypeDef> = emptyList(),
    val customEdgeTypes: List<EdgeTypeDef> = emptyList(),
    /** Named territories behind groups of cards (October 2026; older boards have none). */
    val areas: List<Area> = emptyList(),
) {
    companion object {
        fun empty(): Project = Project(id = newId(), name = "Untitled Board")
    }
}

/** Random hex id. No java.util.UUID in common code, and this is plenty unique for documents. */
fun newId(): String {
    val chars = "0123456789abcdef"
    return buildString(16) { repeat(16) { append(chars[Random.nextInt(16)]) } }
}

/** Wall-clock time as epoch milliseconds, on every platform. */
fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
