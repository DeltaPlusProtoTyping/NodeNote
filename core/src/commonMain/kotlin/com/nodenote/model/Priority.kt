package com.nodenote.model

/** Work still to decide or do: not solved or parked, and not the goal everything hangs off. */
val Node.isOpenWork: Boolean
    get() = status != NodeStatus.Solved && status != NodeStatus.Parked && type != "Goal"

/** Solved, but only for now: it will need doing again. */
val Node.isSolvedForNow: Boolean
    get() = status == NodeStatus.Solved && forNow

/** A solved-for-now card whose check-again date has come: it's back on the list. */
fun Node.isDueAgain(now: Long): Boolean = isSolvedForNow && recheckAt in 1..now

/** How long a solved-for-now card waits before it's due again: its last solve to its check-again date. 0 = no date. */
val Node.recheckInterval: Long
    get() = if (recheckAt > 0 && solvedAt in 1 until recheckAt) recheckAt - solvedAt else 0L

/** The status as a word for lists and notes: Solved for now and Due again, else the plain status. */
fun Node.statusLabel(now: Long): String = when {
    isDueAgain(now) -> "Due again"
    isSolvedForNow -> "Solved for now"
    else -> status.label
}

/** The check-again choices for Solved for now: label and days (0 = no date). */
val RecheckChoices: List<Pair<String, Int>> = listOf(
    "No date" to 0,
    "In a week" to 7,
    "In a month" to 30,
    "In 3 months" to 91,
    "In 6 months" to 182,
    "In a year" to 365,
)

const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

/** Card and legend colors: solved for now (teal) and due again (orange). */
const val FOR_NOW_ARGB = 0xFF45C4D6
const val DUE_AGAIN_ARGB = 0xFFF0904B
