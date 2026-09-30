package com.nodenote.model

import kotlinx.serialization.Serializable

/**
 * An entity-level change set between two versions of a board ([Project]).
 *
 * Two people edit the same board at once, so clients never upload whole
 * boards: they upload the difference between the last server version they saw
 * and their local copy. Applying is "upsert by id / delete by id", which is
 * idempotent and merges cleanly when the other person changed different nodes
 * in the meantime (same node edited by both = last writer wins).
 *
 * Custom types are only ever added or edited in the UI, never deleted, so they
 * have no delete lists.
 */
@Serializable
data class BoardPatch(
    val name: String? = null,
    val upsertNodes: List<Node> = emptyList(),
    val deleteNodes: List<String> = emptyList(),
    val upsertEdges: List<Edge> = emptyList(),
    val deleteEdges: List<String> = emptyList(),
    val upsertElements: List<CanvasElement> = emptyList(),
    val deleteElements: List<String> = emptyList(),
    val upsertNodeTypes: List<NodeTypeDef> = emptyList(),
    val upsertEdgeTypes: List<EdgeTypeDef> = emptyList(),
) {
    val isEmpty: Boolean
        get() = name == null &&
            upsertNodes.isEmpty() && deleteNodes.isEmpty() &&
            upsertEdges.isEmpty() && deleteEdges.isEmpty() &&
            upsertElements.isEmpty() && deleteElements.isEmpty() &&
            upsertNodeTypes.isEmpty() && upsertEdgeTypes.isEmpty()
}

object BoardDiff {

    /** The patch that turns [from] into [to]. Board id is never part of a patch. */
    fun diff(from: Project, to: Project): BoardPatch = BoardPatch(
        name = to.name.takeIf { it != from.name },
        upsertNodes = changed(from.nodes, to.nodes) { it.id },
        deleteNodes = removed(from.nodes, to.nodes) { it.id },
        upsertEdges = changed(from.edges, to.edges) { it.id },
        deleteEdges = removed(from.edges, to.edges) { it.id },
        upsertElements = changed(from.elements, to.elements) { it.id },
        deleteElements = removed(from.elements, to.elements) { it.id },
        upsertNodeTypes = changed(from.customNodeTypes, to.customNodeTypes) { it.id },
        upsertEdgeTypes = changed(from.customEdgeTypes, to.customEdgeTypes) { it.id },
    )

    /** Items in [to] that are new or differ from their [from] counterpart, in [to]'s order. */
    private fun <T> changed(from: List<T>, to: List<T>, id: (T) -> String): List<T> {
        val before = from.associateBy(id)
        return to.filter { before[id(it)] != it }
    }

    private fun <T> removed(from: List<T>, to: List<T>, id: (T) -> String): List<String> {
        val after = to.mapTo(HashSet(), id)
        return from.map(id).filter { it !in after }
    }
}

/**
 * Applies [patch]: existing items keep their list position (= canvas z-order),
 * new ones are appended in patch order, deletes win over upserts. Edges left
 * pointing at a missing node are dropped, so the result is always consistent
 * no matter how two people's edits interleave. Deterministic — the server and
 * every client compute the same board from the same inputs.
 */
fun Project.applyPatch(patch: BoardPatch): Project {
    if (patch.isEmpty) return this
    val nodes = merge(nodes, patch.upsertNodes, patch.deleteNodes) { it.id }
    val nodeIds = nodes.mapTo(HashSet()) { it.id }
    return copy(
        name = patch.name ?: name,
        nodes = nodes,
        edges = merge(edges, patch.upsertEdges, patch.deleteEdges) { it.id }
            .filter { it.fromNodeId in nodeIds && it.toNodeId in nodeIds },
        elements = merge(elements, patch.upsertElements, patch.deleteElements) { it.id },
        customNodeTypes = merge(customNodeTypes, patch.upsertNodeTypes, emptyList()) { it.id },
        customEdgeTypes = merge(customEdgeTypes, patch.upsertEdgeTypes, emptyList()) { it.id },
    )
}

private fun <T> merge(current: List<T>, upserts: List<T>, deletes: List<String>, id: (T) -> String): List<T> {
    if (upserts.isEmpty() && deletes.isEmpty()) return current
    val deleted = deletes.toHashSet()
    val replacements = upserts.associateBy(id)
    val existing = HashSet<String>()
    val result = ArrayList<T>(current.size + upserts.size)
    for (item in current) {
        val key = id(item)
        existing.add(key)
        if (key !in deleted) result.add(replacements[key] ?: item)
    }
    for (item in upserts) {
        val key = id(item)
        if (key !in existing && key !in deleted) {
            existing.add(key) // a patch listing the same id twice still adds it once
            result.add(item)
        }
    }
    return result
}

/**
 * Re-bases local work onto a newer server board: whatever [local] changed
 * relative to [oldBase] is replayed on top of [newBase]. Used when the other
 * person's edits arrive while this client has unsent (or in-flight) edits.
 */
fun rebase(local: Project, oldBase: Project, newBase: Project): Project =
    newBase.applyPatch(BoardDiff.diff(oldBase, local))
