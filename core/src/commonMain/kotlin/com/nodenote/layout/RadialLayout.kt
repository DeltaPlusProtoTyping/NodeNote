package com.nodenote.layout

import com.nodenote.model.Edge
import com.nodenote.model.Node
import com.nodenote.model.Project
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The "Tidy" button: lays a board out as a radial mind map around its main goal, with no two
 * cards closer than [Options.nodeGap].
 *
 * The tree is the one [com.nodenote.sort.BoardHierarchy] sees (connections undirected, BFS from the
 * most-connected Goal); cross-links don't affect the layout. Depth d sits on ring d around the root,
 * and every ring keeps the order the user already gave the branches, so each branch stays on its side.
 * Cards are packed along their ring as close to their parent as the ring allows (a few passes out and
 * back in balance parents over their children), then each ring takes the smallest radius at which none
 * of its cards comes near anything already placed. Other islands go in a column to the right.
 *
 * Rings are packed one by one rather than giving each branch a fixed wedge: a wedge has to be as wide as
 * the branch's busiest ring, which leaves gaps on every other ring and made big boards 2–3× larger.
 */
object RadialLayout {

    data class Options(
        /** Minimum clear space between any two cards. */
        val nodeGap: Float = 28f,
        /** Extra radial space between consecutive rings. */
        val levelGap: Float = 60f,
        /** Space between separate islands (disconnected groups). */
        val islandGap: Float = 160f,
    )

    /**
     * New top-left positions for every node of [project] (node id → x to y). The main tree's root keeps its current
     * position; nodes appear in the result even if unchanged. Pure and deterministic.
     */
    fun layout(project: Project, options: Options = Options()): Map<String, Pair<Float, Float>> {
        val nodes = project.nodes.distinctBy { it.id }.map(::usable)
        if (nodes.isEmpty()) return emptyMap()
        val forest = Forest(nodes, project.edges)
        val gap = options.nodeGap.orZero()
        val levelGap = options.levelGap.orZero()
        val islandGap = max(options.islandGap.orZero(), gap)
        val left = DoubleArray(nodes.size)
        val top = DoubleArray(nodes.size)

        fun place(tree: TreeLayout, centerX: Double, centerY: Double) {
            tree.members.forEachIndexed { k, i ->
                left[i] = centerX + tree.dx[k] - nodes[i].width / 2.0
                top[i] = centerY + tree.dy[k] - nodes[i].height / 2.0
            }
        }

        fun bounds(indices: Iterable<Int>): Box {
            val box = Box()
            for (i in indices) box.add(left[i], top[i], left[i] + nodes[i].width, top[i] + nodes[i].height)
            return box
        }

        // The main tree grows around its root, which stays where it is.
        val main = forest.roots.first()
        val mainTree = TreeLayout(nodes, forest, main, gap, levelGap)
        place(mainTree, nodes[main].x + nodes[main].width / 2.0, nodes[main].y + nodes[main].height / 2.0)

        // Other islands in a column to its right, top-aligned and stacked; lone cards in a grid below them.
        val map = bounds(mainTree.members.asIterable())
        val columnX = map.right + islandGap
        var nextY = map.top
        val others = forest.roots.drop(1)
        for (root in others.filter { forest.children[it].isNotEmpty() }.sortedBy { nodes[it].y + nodes[it].height / 2 }) {
            val island = TreeLayout(nodes, forest, root, gap, levelGap)
            place(island, 0.0, 0.0)
            val box = bounds(island.members.asIterable())
            for (i in island.members) {
                left[i] += columnX - box.left
                top[i] += nextY - box.top
            }
            nextY += box.bottom - box.top + islandGap
        }
        val loners = others.filter { forest.children[it].isEmpty() }
            .sortedWith(compareBy({ nodes[it].y + nodes[it].height / 2 }, { nodes[it].x + nodes[it].width / 2 }))
        grid(loners, nodes, columnX, nextY, gap, left, top)

        return nodes.indices.associate { i ->
            nodes[i].id to (if (i == main) nodes[i].x to nodes[i].y else left[i].toFloat() to top[i].toFloat())
        }
    }

    /**
     * The node as the layout math sees it: a non-finite position counts as 0 and a broken size as the default, so one
     * bad number can never spread NaN across the board. Normal nodes come back unchanged.
     */
    private fun usable(node: Node): Node {
        fun Float.isSize() = isFinite() && this > 0f
        if (node.x.isFinite() && node.y.isFinite() && node.width.isSize() && node.height.isSize()) return node
        return node.copy(
            x = if (node.x.isFinite()) node.x else 0f,
            y = if (node.y.isFinite()) node.y else 0f,
            width = if (node.width.isSize()) node.width else Node.DEFAULT_WIDTH,
            height = if (node.height.isSize()) node.height else Node.DEFAULT_HEIGHT,
        )
    }

    /** Lone cards in rows and columns (each column as wide as its widest card), starting at [x], [y]. */
    private fun grid(cards: List<Int>, nodes: List<Node>, x: Double, y: Double, gap: Double, left: DoubleArray, top: DoubleArray) {
        if (cards.isEmpty()) return
        val columns = ceil(sqrt(cards.size.toDouble())).toInt()
        val columnWidth = DoubleArray(columns)
        val rowHeight = DoubleArray((cards.size + columns - 1) / columns)
        cards.forEachIndexed { k, i ->
            columnWidth[k % columns] = max(columnWidth[k % columns], nodes[i].width.toDouble())
            rowHeight[k / columns] = max(rowHeight[k / columns], nodes[i].height.toDouble())
        }
        var rowY = y
        for (row in rowHeight.indices) {
            var cellX = x
            for (column in 0 until columns) {
                val k = row * columns + column
                if (k >= cards.size) break
                left[cards[k]] = cellX
                top[cards[k]] = rowY
                cellX += columnWidth[column] + gap
            }
            rowY += rowHeight[row] + gap
        }
    }
}

private const val TAU = 2 * PI

/** Out-and-back-in passes that balance parents over their children before the final pass. */
private const val SWEEPS = 3

/** How far (radians) packing may push a card from its ideal angle before its ring grows instead. */
private const val MAX_SHIFT = 0.35

/** Children stay within this angle either side of their parent, so links never point back inward. */
private const val MAX_FAN = PI / 2

/** Ring growth step while searching for a radius where the ring's cards fit. */
private const val GROWTH = 1.04

/** Negative or NaN spacing options count as 0. */
private fun Float.orZero(): Double = if (this > 0f) toDouble() else 0.0

/** Angle in (−π, π]. */
private fun normalize(angle: Double): Double {
    val a = angle.mod(TAU)
    return if (a > PI) a - TAU else a
}

/** Growing bounding box. */
private class Box {
    var left = Double.POSITIVE_INFINITY
    var top = Double.POSITIVE_INFINITY
    var right = Double.NEGATIVE_INFINITY
    var bottom = Double.NEGATIVE_INFINITY

    fun add(l: Double, t: Double, r: Double, b: Double) {
        left = min(left, l)
        top = min(top, t)
        right = max(right, r)
        bottom = max(bottom, b)
    }
}

/**
 * The board as trees, by node index, shaped exactly like [com.nodenote.sort.BoardHierarchy]: connections
 * undirected, BFS from the most-connected Goal (or most-connected node), edge order breaking ties, one root
 * per island. Connections to missing nodes are ignored.
 */
private class Forest(nodes: List<Node>, edges: List<Edge>) {
    val parent = IntArray(nodes.size) { -1 }
    val children = List(nodes.size) { ArrayList<Int>() }

    /** The main root first, then the other islands' roots. */
    val roots = ArrayList<Int>()

    init {
        val index = HashMap<String, Int>()
        nodes.forEachIndexed { i, node -> index[node.id] = i }
        val adjacency = List(nodes.size) { ArrayList<Int>() }
        for (edge in edges) {
            val a = index[edge.fromNodeId] ?: continue
            val b = index[edge.toNodeId] ?: continue
            adjacency[a].add(b)
            adjacency[b].add(a)
        }
        val rootOrder = nodes.indices.sortedWith(
            compareByDescending<Int> { nodes[it].type == "Goal" }.thenByDescending { adjacency[it].size },
        )
        val seen = BooleanArray(nodes.size)
        for (root in rootOrder) {
            if (seen[root]) continue
            seen[root] = true
            roots.add(root)
            val queue = ArrayDeque(listOf(root))
            while (queue.isNotEmpty()) {
                val i = queue.removeFirst()
                for (j in adjacency[i]) {
                    if (seen[j]) continue
                    seen[j] = true
                    parent[j] = i
                    children[i].add(j)
                    queue.addLast(j)
                }
            }
        }
    }
}

/**
 * Radial layout of the tree under [root]: card centers ([dx], [dy]) relative to the root's center, for each of
 * [members] (board indices; local index 0 is the root). Angles are measured in board coordinates (y down).
 */
private class TreeLayout(nodes: List<Node>, forest: Forest, root: Int, private val gap: Double, private val levelGap: Double) {
    val members: IntArray
    val dx: DoubleArray
    val dy: DoubleArray

    private val n: Int
    private val w: DoubleArray
    private val h: DoubleArray
    private val parent: IntArray
    private val kids: Array<IntArray>
    private val size: IntArray

    /** Current angle of each node around the root (where the user put it). */
    private val userAngle: DoubleArray

    /** Local indices by depth, each ring in cyclic (depth-first) order. */
    private val rings = ArrayList<IntArray>()
    private val theta: DoubleArray
    private val radius: DoubleArray

    init {
        // Members in BFS order, so parents come before children.
        val order = ArrayList<Int>().apply { add(root) }
        var head = 0
        while (head < order.size) order.addAll(forest.children[order[head++]])
        members = order.toIntArray()
        n = members.size
        val local = HashMap<Int, Int>()
        members.forEachIndexed { k, i -> local[i] = k }
        w = DoubleArray(n) { nodes[members[it]].width.toDouble() }
        h = DoubleArray(n) { nodes[members[it]].height.toDouble() }
        parent = IntArray(n) { k -> if (k == 0) -1 else local.getValue(forest.parent[members[k]]) }

        val rootX = nodes[root].x + nodes[root].width / 2.0
        val rootY = nodes[root].y + nodes[root].height / 2.0
        val x = DoubleArray(n) { nodes[members[it]].x + w[it] / 2 - rootX }
        val y = DoubleArray(n) { nodes[members[it]].y + h[it] / 2 - rootY }
        userAngle = DoubleArray(n) { atan2(y[it], x[it]) }

        // Children in the order the user arranged them: around the root for the main branches, deeper down by
        // their angle seen from the parent, relative to the direction the parent points away from its own parent.
        kids = Array(n) { v ->
            val children = forest.children[members[v]].map { local.getValue(it) }
            if (v == 0) {
                children.sortedBy { userAngle[it] }.toIntArray()
            } else {
                val p = parent[v]
                val outward = if (x[v] == x[p] && y[v] == y[p]) userAngle[v] else atan2(y[v] - y[p], x[v] - x[p])
                children.sortedBy { normalize(atan2(y[it] - y[v], x[it] - x[v]) - outward) }.toIntArray()
            }
        }
        size = IntArray(n) { 1 }
        for (v in n - 1 downTo 1) size[parent[v]] += size[v]

        // Rings in depth-first order: every subtree is one contiguous run of each ring.
        val stack = ArrayDeque(listOf(0 to 0))
        val ringLists = ArrayList<ArrayList<Int>>()
        while (stack.isNotEmpty()) {
            val (v, depth) = stack.removeLast()
            if (ringLists.size <= depth) ringLists.add(ArrayList())
            ringLists[depth].add(v)
            for (k in kids[v].indices.reversed()) stack.addLast(kids[v][k] to depth + 1)
        }
        ringLists.mapTo(rings) { it.toIntArray() }
        theta = DoubleArray(n)
        radius = DoubleArray(rings.size)

        if (n > 1) {
            spreadMainBranches()
            val start = theta.copyOf()
            repeat(SWEEPS) {
                for (d in 1 until rings.size) placeRing(d)
                for (d in rings.size - 2 downTo 1) centerOverChildren(d)
            }
            // The sweeps may have turned everything a little; undo that before and after the last pass. Anchoring to the
            // start (rather than to the user's angles again) makes a second Tidy reproduce the first exactly.
            turnToward(start)
            for (d in 1 until rings.size) placeRing(d)
            turnToward(start)
        }
        val (px, py) = settle()
        dx = px
        dy = py
    }

    /** Starting point: the main branches split the circle by leaf count, turned to where the user had them. */
    private fun spreadMainBranches() {
        val leaves = IntArray(n)
        for (v in n - 1 downTo 0) {
            if (kids[v].isEmpty()) leaves[v] = 1
            if (v > 0) leaves[parent[v]] += leaves[v]
        }
        var start = 0.0
        for (k in kids[0]) {
            val wedge = TAU * leaves[k] / leaves[0]
            theta[k] = start + wedge / 2
            start += wedge
        }
        turnToward(userAngle)
    }

    /** Turns everything so the main branches best match the [reference] angles (circular mean, weighted by branch size). */
    private fun turnToward(reference: DoubleArray) {
        var s = 0.0
        var c = 0.0
        for (k in kids[0]) {
            s += size[k] * sin(reference[k] - theta[k])
            c += size[k] * cos(reference[k] - theta[k])
        }
        if (s == 0.0 && c == 0.0) return
        val turn = atan2(s, c)
        for (v in 1 until n) theta[v] += turn
    }

    /** Places ring [d] around the ring inside it: families centered on their parents, the ring grown until they fit. */
    private fun placeRing(d: Int) {
        val ring = rings[d]
        val guess = DoubleArray(ring.size) { theta[if (d == 1) ring[it] else parent[ring[it]]] }
        if (d == 1) for (i in 1 until guess.size) guess[i] = guess[i - 1] + (guess[i] - guess[i - 1]).mod(TAU)
        var r = max(floorRadius(d) { guess[it] }, (0 until ring.size).sumOf { chordAt(ring, guess, it) } / TAU)
        var best: DoubleArray? = null
        for (attempt in 0 until 400) {
            val targets = if (d == 1) guess else familyTargets(d, r)
            val angles = pack(ring, targets, r)
            if (angles != null) {
                best = angles
                val settled = angles.indices.all { abs(angles[it] - targets[it]) <= MAX_SHIFT } &&
                    (d == 1 || angles.indices.all { abs(angles[it] - theta[parent[ring[it]]]) <= MAX_FAN })
                if (settled) break
            }
            r *= GROWTH
        }
        radius[d] = r
        val angles = best ?: DoubleArray(ring.size) { guess[0] + TAU * it / ring.size }
        for (i in ring.indices) theta[ring[i]] = angles[i]
    }

    /** Pulls each node of ring [d] toward the middle of its children (leaves stay), on the same radius. */
    private fun centerOverChildren(d: Int) {
        val ring = rings[d]
        val targets = DoubleArray(ring.size) { i ->
            val v = ring[i]
            if (kids[v].isEmpty()) theta[v] else theta[v] + kids[v].sumOf { normalize(theta[it] - theta[v]) } / kids[v].size
        }
        val angles = pack(ring, targets, radius[d]) ?: return
        for (i in ring.indices) theta[ring[i]] = angles[i]
    }

    /** Each family of ring [d] fanned out around its parent's angle, siblings as close as radius [r] allows. */
    private fun familyTargets(d: Int, r: Double): DoubleArray {
        val ring = rings[d]
        val targets = DoubleArray(ring.size)
        var first = 0
        while (first < ring.size) {
            val p = parent[ring[first]]
            var last = first
            while (last + 1 < ring.size && parent[ring[last + 1]] == p) {
                targets[last + 1] = targets[last] + separation(ring[last], ring[last + 1], theta[p], r)
                last++
            }
            val shift = theta[p] - targets[last] / 2
            for (i in first..last) targets[i] += shift
            first = last + 1
        }
        return targets
    }

    /**
     * Angles for [ring] as close as possible to [targets] with neighbours far enough apart on radius [r], or null when
     * the ring is too small for its cards. Spacing depends on where a pair ends up (wide cards need more room near the
     * top and bottom), so the gaps are refit a few times.
     */
    private fun pack(ring: IntArray, targets: DoubleArray, r: Double): DoubleArray? {
        val count = ring.size
        if (count == 1) return targets.copyOf()
        val gaps = DoubleArray(count)
        var angles = targets
        for (pass in 0 until 4) {
            var widened = false
            for (i in 0 until count) {
                val next = if (i == count - 1) angles[0] + TAU else angles[i + 1]
                val needed = separation(ring[i], ring[(i + 1) % count], (angles[i] + next) / 2, r)
                if (needed > gaps[i]) {
                    gaps[i] = needed
                    widened = true
                }
            }
            if (!widened) break
            if (gaps.sum() > TAU) return null
            angles = placeOnCircle(targets, gaps)
        }
        return angles
    }

    /**
     * Smallest angle between neighbours [a] and [b] on a ring of radius [r], their chord pointing along the tangent at
     * [mid]; more than π when they can't fit at all.
     */
    private fun separation(a: Int, b: Int, mid: Double, r: Double): Double {
        val chord = chord(a, b, mid)
        return if (chord >= 2 * r) PI + 1 else 2 * asin(chord / (2 * r))
    }

    /** Center distance neighbours [a] and [b] need along the tangent at [mid]: clear side by side, or stacked. */
    private fun chord(a: Int, b: Int, mid: Double): Double {
        val sideBySide = (w[a] + w[b]) / 2 + gap
        val stacked = (h[a] + h[b]) / 2 + gap
        return min(sideBySide / abs(sin(mid)), stacked / abs(cos(mid)))
    }

    private fun chordAt(ring: IntArray, angles: DoubleArray, i: Int): Double {
        val j = (i + 1) % ring.size
        if (j == i) return 0.0
        val next = if (j == 0) angles[0] + TAU else angles[j]
        return chord(ring[i], ring[j], (angles[i] + next) / 2)
    }

    /** Half the card's extent along the ray at [angle]. */
    private fun radialExtent(v: Int, angle: Double) = w[v] / 2 * abs(cos(angle)) + h[v] / 2 * abs(sin(angle))

    /** Smallest radius for ring [d] that keeps each card [levelGap] further out than its parent (radially). */
    private fun floorRadius(d: Int, angleAt: (Int) -> Double): Double {
        var floor = 0.0
        rings[d].forEachIndexed { i, v ->
            val p = parent[v]
            val a = angleAt(i)
            val parentExtent = radialExtent(p, if (p == 0) a else theta[p])
            floor = max(floor, radius[d - 1] + parentExtent + radialExtent(v, a) + levelGap)
        }
        return floor
    }

    /**
     * Final radii, inside out: each ring as small as possible (at least its floor) with every one of its cards at
     * least [gap] clear of every card already placed and of each other. Returns the card centers.
     */
    private fun settle(): Pair<DoubleArray, DoubleArray> {
        val px = DoubleArray(n)
        val py = DoubleArray(n)
        val placed = ArrayList<Int>(n).apply { add(0) }
        for (d in 1 until rings.size) {
            val ring = rings[d]
            val floor = floorRadius(d) { theta[ring[it]] }
            // Radii at which some pair would overlap, as open intervals.
            val blocked = ArrayList<Span>()
            for (v in ring) {
                val c = cos(theta[v])
                val s = sin(theta[v])
                for (u in placed) {
                    val xs = overlapAlong(c, px[u], (w[u] + w[v]) / 2 + gap) ?: continue
                    val ys = overlapAlong(s, py[u], (h[u] + h[v]) / 2 + gap) ?: continue
                    val span = Span(max(xs.lo, ys.lo), min(xs.hi, ys.hi))
                    if (span.lo < span.hi && span.hi > floor) blocked.add(span)
                }
            }
            for (i in ring.indices) {
                for (j in i + 1 until ring.size) {
                    val a = ring[i]
                    val b = ring[j]
                    // Same radius: the pair overlaps for every r below this.
                    val clearX = ((w[a] + w[b]) / 2 + gap) / abs(cos(theta[a]) - cos(theta[b]))
                    val clearY = ((h[a] + h[b]) / 2 + gap) / abs(sin(theta[a]) - sin(theta[b]))
                    val below = min(clearX, clearY)
                    if (below > floor && below.isFinite()) blocked.add(Span(Double.NEGATIVE_INFINITY, below))
                }
            }
            blocked.sortBy { it.lo }
            var r = floor
            for (span in blocked) {
                if (span.lo >= r) break
                if (span.hi > r) r = span.hi
            }
            r += 1e-6 * max(1.0, r)
            radius[d] = r
            for (v in ring) {
                px[v] = r * cos(theta[v])
                py[v] = r * sin(theta[v])
                placed.add(v)
            }
        }
        return px to py
    }
}

/** Open interval of radii. */
private class Span(val lo: Double, val hi: Double)

/** Radii r with |r·[u] − [p]| < [need]: an interval, everything, or nothing (null). */
private fun overlapAlong(u: Double, p: Double, need: Double): Span? {
    if (abs(u) < 1e-12) return if (abs(p) < need) Span(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY) else null
    val a = (p - need) / u
    val b = (p + need) / u
    return if (a < b) Span(a, b) else Span(b, a)
}

/**
 * Angles as close as possible (least squares) to [targets], in the same cyclic order, with consecutive angles at least
 * [gaps] apart (gaps[i] is between i and i+1; the last one wraps around to 0). Neighbours that would collide merge
 * into blocks placed at their mean (pool adjacent violators, around the circle). The gaps must sum to at most 2π.
 */
private fun placeOnCircle(targets: DoubleArray, gaps: DoubleArray): DoubleArray {
    val n = targets.size

    /**
     * Cards start..start+size−1 (indices wrap around), placed [span] apart from first to last; [sum] totals each
     * card's target minus its offset from the first, so sum/size is where the first card goes.
     */
    class Block(val start: Int, var size: Int, var sum: Double, var span: Double) {
        val first get() = sum / size
    }

    val blocks = ArrayList<Block>(n)
    for (i in 0 until n) blocks.add(Block(i, 1, targets[i], 0.0))
    var merged = true
    while (merged && blocks.size > 1) {
        merged = false
        var j = 0
        while (j < blocks.size && blocks.size > 1) {
            val a = blocks[j]
            val k = (j + 1) % blocks.size
            val b = blocks[k]
            val end = a.start + a.size // b's first card, counted past the wrap when it is one
            val turn = if (end >= n) TAU else 0.0
            val offset = a.span + gaps[(end - 1) % n]
            if (b.first + turn >= a.first + offset - 1e-12) {
                j++
                continue
            }
            a.sum += b.sum + b.size * (turn - offset)
            a.size += b.size
            a.span = offset + b.span
            blocks.removeAt(k)
            merged = true
            j = max(0, if (k == 0) j - 2 else j - 1)
        }
    }
    val angles = DoubleArray(n)
    for (block in blocks) {
        var angle = block.first
        for (m in 0 until block.size) {
            val i = (block.start + m) % n
            angles[i] = angle
            angle += gaps[i]
        }
    }
    // Unwrap into one increasing run starting next to the first target.
    angles[0] += TAU * round((targets[0] - angles[0]) / TAU)
    for (i in 1 until n) angles[i] = angles[i - 1] + (angles[i] - angles[i - 1]).mod(TAU)
    return angles
}
