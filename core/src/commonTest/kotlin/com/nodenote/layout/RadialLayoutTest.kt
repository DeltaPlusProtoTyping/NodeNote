package com.nodenote.layout

import com.nodenote.layout.LayoutChecks.assertNoOverlap
import com.nodenote.layout.LayoutChecks.edge
import com.nodenote.layout.LayoutChecks.layoutAndCheck
import com.nodenote.layout.LayoutChecks.node
import com.nodenote.layout.LayoutChecks.randomTree
import com.nodenote.layout.LayoutChecks.star
import com.nodenote.model.Edge
import com.nodenote.model.Project
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RadialLayoutTest {

    @Test
    fun emptyBoard() {
        assertEquals(emptyMap(), RadialLayout.layout(Project("p", "Empty")))
    }

    @Test
    fun singleCardStaysPut() {
        val board = Project("p", "One", listOf(node("a", 12.5f, -40f)))
        assertEquals(mapOf("a" to (12.5f to -40f)), layoutAndCheck(board))
    }

    @Test
    fun starOfThirty() {
        layoutAndCheck(star(30))
    }

    @Test
    fun deepChain() {
        val nodes = List(20) { node("c$it", it * 50f, it * 30f, type = if (it == 0) "Goal" else "Topic") }
        val edges = (1 until 20).map { if (it % 2 == 0) edge("c${it - 1}", "c$it") else edge("c$it", "c${it - 1}") }
        layoutAndCheck(Project("chain", "Chain", nodes, edges))
    }

    @Test
    fun randomTreesNeverOverlap() {
        for (seed in 1..30) {
            val count = 5 + (seed * 37) % 196
            layoutAndCheck(randomTree(seed, count, crossLinks = seed % 2 == 0))
        }
    }

    @Test
    fun widerGapsAreHonoured() {
        val options = RadialLayout.Options(nodeGap = 90f, levelGap = 10f, islandGap = 40f)
        layoutAndCheck(randomTree(77, 60), options)
        layoutAndCheck(star(12), options)
    }

    @Test
    fun branchesStayOnTheirSide() {
        // Four equal branches pointing right, down, left and up (y grows downward).
        val nodes = arrayListOf(node("goal", 0f, 0f, type = "Goal"))
        val edges = arrayListOf<Edge>()
        for (b in 0 until 4) {
            val angle = b * PI / 2
            val id = "b$b"
            nodes += node(id, (500 * cos(angle)).toFloat(), (500 * sin(angle)).toFloat())
            edges += edge("goal", id)
            for (k in 0 until 3) {
                val leafAngle = angle + (k - 1) * 0.3
                nodes += node("$id-$k", (900 * cos(leafAngle)).toFloat(), (900 * sin(leafAngle)).toFloat())
                edges += edge(id, "$id-$k")
            }
        }
        val board = Project("p", "Sides", nodes, edges)
        val result = layoutAndCheck(board)
        for (b in 0 until 4) {
            // Default-size cards: top-left offsets from the goal equal center offsets.
            val (x, y) = result.getValue("b$b")
            val off = abs((atan2(y.toDouble(), x.toDouble()) - b * PI / 2 + 3 * PI).mod(2 * PI) - PI)
            assertTrue(off < 0.2, "branch b$b turned ${off * 180 / PI} degrees")
        }
    }

    @Test
    fun familiesKeepTheirOrder() {
        // p sits right of the goal; its children fan out above (c0), straight out (c1) and below (c2), y growing
        // downward. The links are listed in a different order, so only the positions can explain the result.
        val nodes = listOf(
            node("goal", 0f, 0f, type = "Goal"), node("p", 400f, 0f), node("q", -400f, 0f),
            node("c2", 800f, 300f), node("c0", 800f, -300f), node("c1", 850f, 0f),
        )
        val edges = listOf(edge("goal", "p"), edge("q", "goal"), edge("p", "c2"), edge("c0", "p"), edge("p", "c1"))
        val result = layoutAndCheck(Project("p", "Family", nodes, edges))
        // All cards are the default size, so top-left offsets from the goal equal center offsets.
        fun angle(id: String) = result.getValue(id).let { (x, y) -> atan2(y.toDouble(), x.toDouble()) }
        assertTrue(angle("c0") < angle("c1") && angle("c1") < angle("c2"), "children of p out of order")
    }

    @Test
    fun cardsStackedOnOneSpotStillSpreadOut() {
        val nodes = List(25) { node("n$it", 100f, 100f, type = if (it == 0) "Goal" else "Topic") }
        val edges = (1 until 25).map { edge("n${(it - 1) / 3}", "n$it") }
        val board = Project("p", "Stacked", nodes, edges)
        val result = RadialLayout.layout(board)
        assertNoOverlap(board, result, RadialLayout.Options().nodeGap)
        assertEquals(100f to 100f, result.getValue("n0"))
        LayoutChecks.assertChildrenFartherOut(board, result, nodes[0])
    }

    @Test
    fun islandsAndLoneCardsGoInAColumnToTheRight() {
        val main = randomTree(3, 25)
        val island = listOf(node("i0", -900f, 50f), node("i1", -700f, 300f), node("i2", -500f, 0f, width = 400f, height = 200f))
        val pair = listOf(node("j0", 0f, 900f), node("j1", 10f, 950f))
        val loners = List(7) { node("z$it", it * 40f, -it * 60f, width = 120f + 40 * it, height = 60f + 25 * it) }
        val board = Project(
            "p", "Islands",
            nodes = main.nodes + island + pair + loners,
            edges = main.edges + listOf(
                edge("i0", "i1"), edge("i2", "i0"), edge("j0", "j1"),
                edge("z0", "nowhere"), // dangling connections are ignored
                edge("z1", "z1"), // and so are self-loops
            ),
        )
        val result = layoutAndCheck(board)
        fun right(id: String) = result.getValue(id).first + board.nodes.first { it.id == id }.width
        fun bottom(id: String) = result.getValue(id).second + board.nodes.first { it.id == id }.height
        val mapRight = main.nodes.maxOf { right(it.id) }
        val mapTop = main.nodes.minOf { result.getValue(it.id).second }
        val column = (island + pair + loners).map { it.id }
        for (id in column) assertTrue(result.getValue(id).first >= mapRight + 160f - 0.01f, "$id should be right of the map")
        // Islands stacked from the top (higher one first), lone cards in a grid below them.
        assertEquals(mapTop, island.minOf { result.getValue(it.id).second }, 0.01f)
        assertTrue(pair.minOf { result.getValue(it.id).second } >= island.maxOf { bottom(it.id) } + 160f - 0.01f)
        assertTrue(loners.minOf { result.getValue(it.id).second } >= pair.maxOf { bottom(it.id) } + 160f - 0.01f)
    }

    @Test
    fun islandsKeepTheNodeGapEvenWhenTheIslandGapIsSmaller() {
        val board = randomTree(21, 30, crossLinks = false)
        val islands = listOf(node("i0", 0f, 0f, width = 300f), node("i1", 50f, 50f), node("lone1", 0f, 0f), node("lone2", 9f, 9f))
        val withIslands = board.copy(nodes = board.nodes + islands, edges = board.edges + edge("i0", "i1"))
        layoutAndCheck(withIslands, RadialLayout.Options(nodeGap = 70f, islandGap = 5f))
    }

    @Test
    fun brokenNumbersNeverSpreadNaN() {
        val board = Project(
            "p", "Broken",
            listOf(
                node("goal", 0f, 0f, type = "Goal"),
                node("a", Float.NaN, 10f),
                node("b", 300f, Float.POSITIVE_INFINITY),
                node("c", 600f, 0f, width = Float.NaN, height = -5f),
                node("d", 900f, 0f),
            ),
            listOf(edge("goal", "a"), edge("goal", "b"), edge("a", "c"), edge("d", "goal")),
        )
        val result = RadialLayout.layout(board)
        assertEquals(5, result.size)
        for ((id, at) in result) assertTrue(at.first.isFinite() && at.second.isFinite(), "$id got $at")
    }

    @Test
    fun tidyingTwiceChangesNothing() {
        for (board in listOf(star(16), randomTree(11, 80), randomTree(12, 150), randomTree(13, 40, crossLinks = false))) {
            val once = RadialLayout.layout(board)
            val moved = board.copy(nodes = board.nodes.map { val (x, y) = once.getValue(it.id); it.copy(x = x, y = y) })
            val twice = RadialLayout.layout(moved)
            for ((id, first) in once) {
                val (x, y) = twice.getValue(id)
                assertTrue(hypot(x - first.first, y - first.second) < 0.5f, "$id moved on the second tidy in ${board.name}")
            }
        }
    }

    @Test
    fun overlapCheckCatchesOverlaps() {
        // Guard the guard: the checker itself must flag cards that are too close.
        val board = Project("p", "Bad", listOf(node("a", 0f, 0f), node("b", 100f, 50f)))
        val failed = runCatching { assertNoOverlap(board, mapOf("a" to (0f to 0f), "b" to (100f to 50f)), 28f) }.isFailure
        assertTrue(failed)
    }
}
