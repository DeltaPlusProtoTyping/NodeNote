package com.nodenote.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Persists the recently used node/connection type ids (newest first), app-global. */
object RecentTypeStore {

    private const val FILE_NAME = "recent_types.json"

    @Serializable
    private data class Recents(val nodes: List<String> = emptyList(), val edges: List<String> = emptyList())

    private val json = Json { ignoreUnknownKeys = true }

    /** Returns (nodeTypeIds, edgeTypeIds). */
    fun load(): Pair<List<String>, List<String>> = try {
        ProjectStorage.readPrefs(FILE_NAME)
            ?.let { json.decodeFromString(Recents.serializer(), it) }
            ?.let { it.nodes to it.edges }
            ?: (emptyList<String>() to emptyList())
    } catch (e: Exception) {
        emptyList<String>() to emptyList()
    }

    fun save(nodeTypeIds: List<String>, edgeTypeIds: List<String>) {
        ProjectStorage.writePrefs(FILE_NAME, json.encodeToString(Recents.serializer(), Recents(nodeTypeIds, edgeTypeIds)))
    }
}
