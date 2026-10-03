package com.nodenote.layout

import com.nodenote.model.Edge
import com.nodenote.model.Node
import com.nodenote.model.Project
import com.nodenote.sort.BoardHierarchy
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Shared fixtures and requirement checks for [RadialLayout] tests. */
internal object LayoutChecks {

    fun node(id: String, x: Float, y: Float, width: Float = Node.DEFAULT_WIDTH, height: Float = Node.DEFAULT_HEIGHT, type: String = "Topic") =
        Node(id, type, "Node $id", x = x, y = y, width = width, height = height)

    fun edge(from: String, to: String) = Edge("$from-$to", from, to)

    /**
     * A random tree of [count] cards with random sizes and positions: each new card hangs off a random earlier one
     * (bushy) or one of the last few (deep chains), edge directions random, plus a few cross-links when [crossLinks].
     */
    fun randomTree(seed: Int, count: Int, crossLinks: Boolean = true): Project {
        val random = Random(seed)
        val nodes = List(count) { i ->
            node(
                "n$i", random.nextDouble(-3000.0, 3000.0).toFloat(), random.nextDouble(-3000.0, 3000.0).toFloat(),
                width = random.nextDouble(120.0, 500.0).toFloat(), height = random.nextDouble(60.0, 260.0).toFloat(),
                type = if (i == 0) "Goal" else "Topic",
            )
        }
        val edges = ArrayList<Edge>()
        for (i in 1 until count) {
            val parent = if (random.nextBoolean()) random.nextInt(i) else maxOf(0, i - 1 - random.nextInt(3))
            edges += if (random.nextBoolean()) edge("n$parent", "n$i") else edge("n$i", "n$parent")
        }
        if (crossLinks) repeat(count / 10) { edges += edge("n${random.nextInt(count)}", "n${random.nextInt(count)}") }
        return Project("random$seed", "Random $seed", nodes, edges)
    }

    /** The main root by BoardHierarchy's rule: most-connected Goal, else most-connected node, first one on ties. */
    fun mainRoot(project: Project): Node {
        fun degree(id: String) = project.edges.count { it.fromNodeId == id } + project.edges.count { it.toNodeId == id }
        return project.nodes.sortedWith(compareByDescending<Node> { it.type == "Goal" }.thenByDescending { degree(it.id) }).first()
    }

    /** Lays [project] out, checks every requirement, and returns the result. */
    fun layoutAndCheck(project: Project, options: RadialLayout.Options = RadialLayout.Options()): Map<String, Pair<Float, Float>> {
        val result = RadialLayout.layout(project, options)
        assertEquals(project.nodes.map { it.id }.toSet(), result.keys, "every node gets a position")
        assertEquals(result, RadialLayout.layout(project, options), "deterministic")
        if (project.nodes.isEmpty()) return result
        assertNoOverlap(project, result, options.nodeGap)
        val root = mainRoot(project)
        assertEquals(root.x to root.y, result.getValue(root.id), "main root stays put")
        assertRootChildrenOrderKept(project, result, root)
        assertChildrenFartherOut(project, result, root)
        return result
    }

    /** Any two cards are at least [gap] apart along x or along y (a rounding allowance of half a unit). */
    fun assertNoOverlap(project: Project, result: Map<String, Pair<Float, Float>>, gap: Float) {
        val nodes = project.nodes
        for (i in nodes.indices) for (j in i + 1 until nodes.size) {
            val a = nodes[i]
            val b = nodes[j]
            val (ax, ay) = result.getValue(a.id)
            val (bx, by) = result.getValue(b.id)
            val clearX = abs((ax + a.width / 2) - (bx + b.width / 2)) - (a.width + b.width) / 2
            val clearY = abs((ay + a.height / 2) - (by + b.height / 2)) - (a.height + b.height) / 2
            if (maxOf(clearX, clearY) < gap - 0.5f) {
                fail("${a.id} and ${b.id} are only ${maxOf(clearX, clearY)} apart in ${project.name}")
            }
        }
    }

    /** The main branches go round the root in the same cyclic order as before. */
    fun assertRootChildrenOrderKept(project: Project, result: Map<String, Pair<Float, Float>>, root: Node) {
        val parents = BoardHierarchy(project).parents
        val kids = project.nodes.filter { parents[it.id] == root.id }
        if (kids.size < 3) return
        val cx = root.centerX
        val cy = root.centerY
        val before = kids.sortedBy { atan2(it.centerY - cy, it.centerX - cx) }.map { it.id }
        val after = kids.sortedBy {
            val (x, y) = result.getValue(it.id)
            atan2(y + it.height / 2 - cy, x + it.width / 2 - cx)
        }.map { it.id }
        val shift = after.indexOf(before.first())
        assertEquals(before, after.drop(shift) + after.take(shift), "cyclic order of the main branches")
    }

    /** In the main tree, every child's center is farther from the root's center than its parent's center. */
    fun assertChildrenFartherOut(project: Project, result: Map<String, Pair<Float, Float>>, root: Node) {
        val parents = BoardHierarchy(project).parents
        val byId = project.nodes.associateBy { it.id }
        fun distance(id: String): Double {
            val node = byId.getValue(id)
            val (x, y) = result.getValue(id)
            return hypot((x + node.width / 2 - root.centerX).toDouble(), (y + node.height / 2 - root.centerY).toDouble())
        }
        fun inMainTree(id: String): Boolean {
            var at = id
            while (true) at = parents[at] ?: return at == root.id
        }
        for ((child, parent) in parents) {
            if (!inMainTree(child)) continue
            assertTrue(distance(child) > distance(parent), "$child should sit farther out than its parent $parent")
        }
    }

    /** A star: one Goal with [count] cards scattered around it. */
    fun star(count: Int): Project {
        val random = Random(5)
        val nodes = listOf(node("goal", 0f, 0f, type = "Goal")) + List(count) { i ->
            val a = random.nextDouble(-PI, PI)
            node("s$i", (400 * cos(a)).toFloat(), (400 * sin(a)).toFloat())
        }
        return Project("star", "Star", nodes, List(count) { edge("goal", "s$it") })
    }
}
