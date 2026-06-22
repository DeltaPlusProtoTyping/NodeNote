package com.nodenote.storage

import com.nodenote.model.Project
import com.nodenote.model.ProjectDiff

/**
 * Computes a human-readable [ProjectDiff] between the open project ([current])
 * and another loaded project ([other], typically a file on disk).
 *
 * Nodes are matched by id (so a renamed node reads as "changed", not
 * add+remove). Edges are matched by their endpoint pair and described using
 * node titles, which is what a reviewer actually wants to read.
 */
object ProjectDiffer {

    fun diff(current: Project, other: Project, otherName: String): ProjectDiff {
        val currentNodes = current.nodes.associateBy { it.id }
        val otherNodes = other.nodes.associateBy { it.id }

        val added = current.nodes.filter { it.id !in otherNodes }.map { it.title.ifBlank { "(untitled)" } }
        val removed = other.nodes.filter { it.id !in currentNodes }.map { it.title.ifBlank { "(untitled)" } }

        // Same id on both sides, but a different field somewhere.
        val changed = current.nodes.mapNotNull { node ->
            val before = otherNodes[node.id] ?: return@mapNotNull null
            if (before != node) describeNodeChange(node.title.ifBlank { "(untitled)" }, before, node) else null
        }

        // Build a "TitleA → TitleB" label set for edges on each side.
        fun edgeLabels(p: Project): Map<Pair<String, String>, String> {
            val titles = p.nodes.associate { it.id to it.title.ifBlank { "(untitled)" } }
            return p.edges.associate { e ->
                (e.fromNodeId to e.toNodeId) to "${titles[e.fromNodeId] ?: "?"} → ${titles[e.toNodeId] ?: "?"}"
            }
        }

        val currentEdges = edgeLabels(current)
        val otherEdges = edgeLabels(other)
        val addedEdges = currentEdges.filterKeys { it !in otherEdges }.values.toList()
        val removedEdges = otherEdges.filterKeys { it !in currentEdges }.values.toList()

        return ProjectDiff(
            otherName = otherName,
            addedNodes = added,
            removedNodes = removed,
            changedNodes = changed,
            addedEdges = addedEdges,
            removedEdges = removedEdges,
        )
    }

    /** Names the fields that differ on a matched node, e.g. "Main PCB: title, 2 properties". */
    private fun describeNodeChange(name: String, before: com.nodenote.model.Node, after: com.nodenote.model.Node): String {
        val parts = mutableListOf<String>()
        if (before.title != after.title) parts.add("title")
        if (before.type != after.type) parts.add("type")
        if (before.description != after.description) parts.add("description")
        if (before.x != after.x || before.y != after.y) parts.add("position")
        if (before.width != after.width || before.height != after.height) parts.add("size")
        if (before.properties != after.properties) parts.add("properties")
        if (before.attachments != after.attachments) parts.add("attachments")
        return if (parts.isEmpty()) name else "$name — ${parts.joinToString(", ")}"
    }
}
