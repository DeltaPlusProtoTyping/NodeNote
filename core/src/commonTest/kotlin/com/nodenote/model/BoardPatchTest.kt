package com.nodenote.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoardPatchTest {

    private fun node(id: String, title: String = id, x: Float = 0f) = Node(id, "Topic", title, x = x, y = 0f)

    private val base = Project(
        id = "b",
        name = "Board",
        nodes = listOf(node("a"), node("b"), node("c")),
        edges = listOf(Edge("e1", "a", "b"), Edge("e2", "b", "c")),
    )

    @Test
    fun diffThenApplyReproducesTarget() {
        val target = base.copy(
            name = "Renamed",
            nodes = listOf(node("a", "A!"), node("c"), node("d")),
            edges = listOf(Edge("e3", "a", "d")),
        )
        val patch = BoardDiff.diff(base, target)
        assertEquals(target, base.applyPatch(patch))
        assertTrue(BoardDiff.diff(target, target).isEmpty)
    }

    @Test
    fun applyIsIdempotent() {
        val target = base.copy(nodes = base.nodes.map { if (it.id == "b") it.copy(x = 50f) else it } + node("z"))
        val patch = BoardDiff.diff(base, target)
        assertEquals(base.applyPatch(patch), base.applyPatch(patch).applyPatch(patch))
    }

    @Test
    fun concurrentEditsOnDifferentNodesBothSurvive() {
        val mine = base.copy(nodes = base.nodes.map { if (it.id == "a") it.copy(title = "mine") else it })
        val theirs = base.copy(nodes = base.nodes.map { if (it.id == "c") it.copy(title = "theirs") else it })
        val merged = rebase(local = mine, oldBase = base, newBase = theirs)
        assertEquals("mine", merged.nodes.first { it.id == "a" }.title)
        assertEquals("theirs", merged.nodes.first { it.id == "c" }.title)
    }

    @Test
    fun edgeToNodeDeletedRemotelyIsDropped() {
        val mine = base.copy(nodes = base.nodes + node("d"), edges = base.edges + Edge("e9", "c", "d"))
        val theirs = base.copy(nodes = base.nodes.filterNot { it.id == "c" }, edges = listOf(Edge("e1", "a", "b")))
        val merged = rebase(local = mine, oldBase = base, newBase = theirs)
        assertTrue(merged.nodes.none { it.id == "c" })
        assertTrue(merged.edges.none { it.fromNodeId == "c" || it.toNodeId == "c" })
        assertTrue(merged.nodes.any { it.id == "d" })
    }

    @Test
    fun resolvesNodeLinksCaseInsensitively() {
        val p = base.copy(nodes = listOf(node("x", "Spindle warm-up"), node("y", "Quoting")))
        assertEquals(listOf("x", "y"), resolveNodeLinks("see [[spindle WARM-up]] and [[Quoting|the quote flow]] and [[nope]]", p))
    }
}
