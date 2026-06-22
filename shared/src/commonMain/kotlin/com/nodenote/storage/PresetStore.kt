package com.nodenote.storage

import com.nodenote.model.ExportPreset
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Persists export presets as one JSON file in the app's preferences directory. */
object PresetStore {

    private const val FILE_NAME = "export_presets.json"

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val serializer = ListSerializer(ExportPreset.serializer())

    fun load(): List<ExportPreset> = try {
        ProjectStorage.readPrefs(FILE_NAME)?.let { json.decodeFromString(serializer, it) } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    fun save(presets: List<ExportPreset>) {
        ProjectStorage.writePrefs(FILE_NAME, json.encodeToString(serializer, presets))
    }
}
