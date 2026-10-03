package com.nodenote.layout

import com.nodenote.api.ApiJson
import com.nodenote.model.Node
import com.nodenote.model.Project
import com.nodenote.sort.BoardHierarchy
import java.io.File
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertTrue

/** [RadialLayout] on an anonymized copy of the real shop board (37 cards, 42 links, one Goal). */
class RadialLayoutFixtureTest {

    private val board: Project = ApiJson.decodeFromString(
        Project.serializer(),
        checkNotNull(javaClass.getResource("/layout/board_structure.json")).readText(),
    )

    @Test
    fun realBoardIsTidyAndNoBiggerThanTheHandMadeOne() {
        val result = LayoutChecks.layoutAndCheck(board)
        val before = bounds(board, board.nodes.associate { it.id to (it.x to it.y) })
        val after = bounds(board, result)
        val root = LayoutChecks.mainRoot(board)
        val radii = ringRadii(board, result, root)
        println("Fixture layout: ${after.w.toInt()} x ${after.h.toInt()} (hand-made ${before.w.toInt()} x ${before.h.toInt()})")
        println("Ring radii: " + radii.entries.joinToString { (d, r) -> "$d: ${r.toInt()}" })
        writeSvg(board, result, root, radii.values, File("build/layout-samples/fixture.svg"))
        assertTrue(after.w <= before.w && after.h <= before.h, "tidy layout should not be bigger than the hand-made one")
    }

    @Test
    fun tidyingTheRealBoardTwiceChangesNothing() {
        val once = RadialLayout.layout(board)
        val moved = board.copy(nodes = board.nodes.map { val (x, y) = once.getValue(it.id); it.copy(x = x, y = y) })
        val twice = RadialLayout.layout(moved)
        for ((id, first) in once) {
            val (x, y) = twice.getValue(id)
            assertTrue(hypot(x - first.first, y - first.second) < 0.5f, "$id moved on the second tidy")
        }
    }

    @Test
    fun fiveHundredCardsInUnder300Ms() {
        val big = LayoutChecks.randomTree(500, 500)
        repeat(2) { RadialLayout.layout(big) } // warm up the JIT
        val best = (1..3).minOf {
            val start = System.nanoTime()
            RadialLayout.layout(big)
            (System.nanoTime() - start) / 1_000_000
        }
        println("500 cards: $best ms")
        assertTrue(best < 300, "500 cards took $best ms")
        LayoutChecks.assertNoOverlap(big, RadialLayout.layout(big), RadialLayout.Options().nodeGap)
    }

    private class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val w get() = right - left
        val h get() = bottom - top
    }

    private fun bounds(project: Project, at: Map<String, Pair<Float, Float>>): Bounds {
        val boxes = project.nodes.map { val (x, y) = at.getValue(it.id); floatArrayOf(x, y, x + it.width, y + it.height) }
        return Bounds(boxes.minOf { it[0] }, boxes.minOf { it[1] }, boxes.maxOf { it[2] }, boxes.maxOf { it[3] })
    }

    /** Distance of each depth's card centers from the root's center (every card of a depth shares its ring). */
    private fun ringRadii(project: Project, result: Map<String, Pair<Float, Float>>, root: Node): Map<Int, Double> {
        val parents = BoardHierarchy(project).parents
        fun depth(id: String): Int = parents[id]?.let { depth(it) + 1 } ?: 0
        return project.nodes.groupBy { depth(it.id) }.toSortedMap().mapValues { (_, nodes) ->
            nodes.maxOf {
                val (x, y) = result.getValue(it.id)
                hypot((x + it.width / 2 - root.centerX).toDouble(), (y + it.height / 2 - root.centerY).toDouble())
            }
        }
    }

    /** Cards, tree links (solid), cross-links (dashed) and the rings, for a human to look at. */
    private fun writeSvg(project: Project, result: Map<String, Pair<Float, Float>>, root: Node, radii: Collection<Double>, file: File) {
        val parents = BoardHierarchy(project).parents
        val byId = project.nodes.associateBy { it.id }
        fun cx(id: String) = result.getValue(id).first + byId.getValue(id).width / 2
        fun cy(id: String) = result.getValue(id).second + byId.getValue(id).height / 2
        val b = bounds(project, result)
        val margin = 60
        val svg = StringBuilder()
        svg.appendLine(
            """<svg xmlns="http://www.w3.org/2000/svg" viewBox="${b.left - margin} ${b.top - margin} ${b.w + 2 * margin} ${b.h + 2 * margin}" """ +
                """width="${((b.w + 2 * margin) / 2).toInt()}" height="${((b.h + 2 * margin) / 2).toInt()}" font-family="sans-serif">""",
        )
        svg.appendLine("""<rect x="${b.left - margin}" y="${b.top - margin}" width="${b.w + 2 * margin}" height="${b.h + 2 * margin}" fill="#f7f8fa"/>""")
        for (r in radii.filter { it > 0 }) {
            svg.appendLine("""<circle cx="${root.centerX}" cy="${root.centerY}" r="$r" fill="none" stroke="#dde2ea" stroke-width="2"/>""")
        }
        for (e in project.edges) {
            if (e.fromNodeId !in byId || e.toNodeId !in byId) continue
            val tree = parents[e.fromNodeId] == e.toNodeId || parents[e.toNodeId] == e.fromNodeId
            val style = if (tree) """stroke="#7b8794" stroke-width="3"""" else """stroke="#e2a3a3" stroke-width="2" stroke-dasharray="10 8""""
            svg.appendLine("""<line x1="${cx(e.fromNodeId)}" y1="${cy(e.fromNodeId)}" x2="${cx(e.toNodeId)}" y2="${cy(e.toNodeId)}" $style/>""")
        }
        for (node in project.nodes) {
            val (x, y) = result.getValue(node.id)
            val fill = if (node.id == root.id) "#ffe7a3" else "#ffffff"
            svg.appendLine("""<rect x="$x" y="$y" width="${node.width}" height="${node.height}" rx="10" fill="$fill" stroke="#4a5568" stroke-width="2"/>""")
            svg.appendLine("""<text x="${x + 12}" y="${y + 30}" font-size="20" fill="#2d3748">${escape(node.title)}</text>""")
        }
        svg.appendLine("</svg>")
        file.parentFile.mkdirs()
        file.writeText(svg.toString())
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
