package com.nodenote.model

/**
 * Where a node lands on a Lean PICK chart (importance × effort), once both are rated.
 * Importance 6+ counts as important, effort 4 or less as a small job.
 */
enum class Pick(val label: String, val hint: String) {
    QuickWin("Quick wins", "important, small job — do these first"),
    BigBet("Big bets", "important, big job — plan them"),
    FillIn("Fill-ins", "small job, less important — when there's time"),
    Rethink("Rethink", "big job, less important — drop or shrink"),
    ;

    companion object {
        const val IMPORTANT = 6
        const val SMALL_JOB = 4
    }
}

/** The node's PICK bucket, or null while importance or effort is unrated. */
val Node.pick: Pick?
    get() = when {
        importance == 0 || effort == 0 -> null
        importance >= Pick.IMPORTANT && effort <= Pick.SMALL_JOB -> Pick.QuickWin
        importance >= Pick.IMPORTANT -> Pick.BigBet
        effort <= Pick.SMALL_JOB -> Pick.FillIn
        else -> Pick.Rethink
    }

/** Work still to decide or do: not solved or parked, and not the goal everything hangs off. */
val Node.isOpenWork: Boolean
    get() = status != NodeStatus.Solved && status != NodeStatus.Parked && type != "Goal"
