package com.nodenote.model

/**
 * Human-readable difference summary between the open project and another file
 * (Compare…). Matching is by id for nodes; edges are described by endpoint
 * titles. Not serialized — computed on demand for the compare dialog.
 */
class ProjectDiff(
    val otherName: String,
    val addedNodes: List<String>,    // in the open project, not in the file
    val removedNodes: List<String>,  // in the file, not in the open project
    val changedNodes: List<String>,  // same id, different content
    val addedEdges: List<String>,
    val removedEdges: List<String>,
) {
    val isEmpty: Boolean
        get() = addedNodes.isEmpty() && removedNodes.isEmpty() && changedNodes.isEmpty() &&
            addedEdges.isEmpty() && removedEdges.isEmpty()
}
