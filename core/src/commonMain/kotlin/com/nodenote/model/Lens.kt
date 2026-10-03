package com.nodenote.model

/**
 * What the card colors mean right now. [Type] is the everyday look; the others recolor the
 * whole map so it answers one question at a glance: what matters most, what's done, how big
 * the jobs are, who's on what, which issues still have no fix. Shared by the app and the
 * exports, so a PDF shows the same colors as the screen.
 */
enum class ColorBy(val label: String, val hint: String) {
    Type("Type", "the usual look: each card's type"),
    Status("Status", "open, in progress, blocked, solved"),
    Importance("Importance", "how much it matters: the hotter, the more"),
    Effort("Effort", "how big a job: green small, violet big"),
    Person("Person", "the owner, else the first @name on the card"),
    Gaps("No fix yet", "issues with no Solves link to a fix"),
}

/** One row of a lens legend; [colorArgb] 0 = shown untinted. */
data class LensKey(val label: String, val colorArgb: Long)

/** Importance scale (same as the app's badges): 1–2 slate, 3–4 blue, 5–6 amber, 7–8 orange, 9–10 red. */
fun importanceArgb(importance: Int): Long = when (importance) {
    1, 2 -> 0xFF8FA0B3
    3, 4 -> 0xFF5B9CFF
    5, 6 -> 0xFFE5B25B
    7, 8 -> 0xFFE58E5B
    else -> 0xFFE2606B
}

/** Effort scale: 1–3 green (quick), 4–6 amber, 7–10 violet (a big one). */
fun effortArgb(effort: Int): Long = when (effort) {
    1, 2, 3 -> 0xFF53C27C
    4, 5, 6 -> 0xFFE5B25B
    else -> 0xFF9B7BFF
}

private val personPalette = listOf(
    0xFF5B9CFF, 0xFF53C27C, 0xFFE58E5B, 0xFFD97BC8, 0xFF45C4D6, 0xFFF0C04A, 0xFF9B7BFF, 0xFFE2606B,
)

/** Stable color per name (the same one a person's chat messages carry). */
fun personColor(name: String): Long {
    var h = 7
    for (c in name.lowercase()) h = h * 31 + c.code
    return personPalette[(h and 0x7fffffff) % personPalette.size]
}

/** Who a card is for or on: its owner, else the first @name in its title, notes or summary; "" if nobody. */
fun Node.person(): String =
    owner.trim().removePrefix("@").ifEmpty {
        mentionsIn(title).firstOrNull() ?: mentionsIn(description).firstOrNull() ?: mentionsIn(summary).firstOrNull() ?: ""
    }

private const val GAP_OPEN = 0xFFE2606B
private const val GAP_FIX = 0xFFE5B25B
private const val GAP_SOLVED = 0xFF53C27C

/** Issues with a fix on the map: a Solves link from something else (drawn either way round). */
fun Project.fixedIssueIds(): Set<String> {
    val byId = nodes.associateBy { it.id }
    val fixed = HashSet<String>()
    for (e in edges) {
        if (e.type != "Solves") continue
        val from = byId[e.fromNodeId] ?: continue
        val to = byId[e.toNodeId] ?: continue
        if (to.type == "Issue") fixed += to.id else if (from.type == "Issue") fixed += from.id
    }
    return fixed
}

/**
 * Colors cards for one [ColorBy] over one board. Build it once per board version (the
 * Gaps lens looks at the links) and ask it per card.
 */
class Lens(val project: Project, val by: ColorBy) {
    private val fixed: Set<String> = if (by == ColorBy.Gaps) project.fixedIssueIds() else emptySet()

    /** The card's tint under this lens, or null to leave it as it is. */
    fun colorOf(node: Node): Long? = when (by) {
        ColorBy.Type -> null
        ColorBy.Status -> node.status.takeIf { it != NodeStatus.None }?.colorArgb
        ColorBy.Importance -> node.importance.takeIf { it > 0 }?.let(::importanceArgb)
        ColorBy.Effort -> node.effort.takeIf { it > 0 }?.let(::effortArgb)
        ColorBy.Person -> node.person().takeIf { it.isNotEmpty() }?.let(::personColor)
        ColorBy.Gaps -> when {
            node.type != "Issue" -> null
            node.status == NodeStatus.Solved -> GAP_SOLVED
            node.id in fixed -> GAP_FIX
            node.status == NodeStatus.Parked -> null
            else -> GAP_OPEN
        }
    }

    /** What the colors on [nodes] mean, in a sensible order; untinted cards get a row only if there are any. */
    fun legend(nodes: List<Node>): List<LensKey> {
        val untinted = nodes.any { colorOf(it) == null }
        fun none(label: String) = if (untinted) listOf(LensKey(label, 0)) else emptyList()
        return when (by) {
            ColorBy.Type -> emptyList()
            ColorBy.Status -> NodeStatus.entries.filter { s -> s != NodeStatus.None && nodes.any { it.status == s } }
                .map { LensKey(it.label, it.colorArgb) } + none("No status")
            ColorBy.Importance -> listOf(9 to "9–10 critical", 7 to "7–8", 5 to "5–6", 3 to "3–4", 1 to "1–2 minor")
                .filter { (v, _) -> nodes.any { it.importance == v || it.importance == v + 1 } }
                .map { (v, label) -> LensKey(label, importanceArgb(v)) } + none("Not rated")
            ColorBy.Effort -> listOf(1 to "1–3 small job", 4 to "4–6", 7 to "7–10 big job")
                .filter { (v, _) -> nodes.any { n -> n.effort > 0 && effortArgb(n.effort) == effortArgb(v) } }
                .map { (v, label) -> LensKey(label, effortArgb(v)) } + none("Not rated")
            ColorBy.Person -> nodes.map { it.person() }.filter { it.isNotEmpty() }
                .groupBy { it.lowercase() }.values.sortedByDescending { it.size }.take(MAX_PEOPLE)
                .map { names -> LensKey(names.first(), personColor(names.first())) } + none("Nobody named")
            ColorBy.Gaps -> buildList {
                val issues = nodes.filter { it.type == "Issue" }
                if (issues.any { colorOf(it) == GAP_OPEN }) add(LensKey("No fix yet", GAP_OPEN))
                if (issues.any { colorOf(it) == GAP_FIX }) add(LensKey("Fix on the map", GAP_FIX))
                if (issues.any { colorOf(it) == GAP_SOLVED }) add(LensKey("Solved", GAP_SOLVED))
                addAll(none("Not an open issue"))
            }
        }
    }

    companion object {
        const val MAX_PEOPLE = 8
    }
}
