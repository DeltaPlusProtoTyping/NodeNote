package com.nodenote.storage

import com.nodenote.model.Project
import kotlinx.serialization.json.Json

/**
 * JSON encoding/decoding for project files.
 *
 * Pretty-printed so saved files are human-readable and diff-friendly;
 * unknown keys are ignored so files from newer app versions still open.
 */
object ProjectSerializer {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(project: Project): String = json.encodeToString(Project.serializer(), project)

    /** Throws [kotlinx.serialization.SerializationException] / [IllegalArgumentException] on malformed input. */
    fun decode(text: String): Project = json.decodeFromString(Project.serializer(), text)
}
