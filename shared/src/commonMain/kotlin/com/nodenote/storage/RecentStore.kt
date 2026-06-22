package com.nodenote.storage

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Persists the recently opened/saved project paths (newest first). */
object RecentStore {

    private const val FILE_NAME = "recent_files.json"

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val serializer = ListSerializer(String.serializer())

    fun load(): List<String> = try {
        ProjectStorage.readPrefs(FILE_NAME)?.let { json.decodeFromString(serializer, it) } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    fun save(paths: List<String>) {
        ProjectStorage.writePrefs(FILE_NAME, json.encodeToString(serializer, paths))
    }
}
