package com.nodenote.model

/** Work still to decide or do: not solved or parked, and not the goal everything hangs off. */
val Node.isOpenWork: Boolean
    get() = status != NodeStatus.Solved && status != NodeStatus.Parked && type != "Goal"
