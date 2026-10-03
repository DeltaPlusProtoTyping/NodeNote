package com.nodenote.storage

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Per-device client settings: who is signed in, which server I talk to, which boards
 * were open, and how far I've read each node's chat. Stored with the platform
 * prefs (desktop ~/.nodenote/, browser localStorage), never on the server.
 */
object ClientPrefs {

    private val json = Json { ignoreUnknownKeys = true }
    private val idList = ListSerializer(String.serializer())
    private val readMap = MapSerializer(String.serializer(), Long.serializer())

    var userName: String
        get() = ProjectStorage.readPrefs("user_name.txt")?.trim().orEmpty()
        set(value) {
            ProjectStorage.writePrefs("user_name.txt", value.trim())
        }

    /** Bearer token of the signed-in session on this device; blank when signed out. */
    var sessionToken: String
        get() = ProjectStorage.readPrefs("session.txt")?.trim().orEmpty()
        set(value) {
            ProjectStorage.writePrefs("session.txt", value.trim())
        }

    /** Blank means "use the platform default" ([defaultServerUrl]). */
    var serverUrl: String
        get() = ProjectStorage.readPrefs("server_url.txt")?.trim().orEmpty()
        set(value) {
            ProjectStorage.writePrefs("server_url.txt", value.trim())
        }

    var openBoardIds: List<String>
        get() = decode(ProjectStorage.readPrefs("open_boards.json"), idList) ?: emptyList()
        set(value) {
            ProjectStorage.writePrefs("open_boards.json", json.encodeToString(idList, value))
        }

    /** Presentation mode (private nodes hidden). Kept per device so a reload mid-meeting doesn't reveal them. */
    var presenting: Boolean
        get() = ProjectStorage.readPrefs("presenting.txt")?.trim() == "1"
        set(value) {
            ProjectStorage.writePrefs("presenting.txt", if (value) "1" else "0")
        }

    /** Words this person told the spell checker to accept (per device). */
    var dictionaryWords: List<String>
        get() = decode(ProjectStorage.readPrefs("dictionary.json"), idList) ?: emptyList()
        set(value) {
            ProjectStorage.writePrefs("dictionary.json", json.encodeToString(idList, value))
        }

    /** nodeId ("" = board inbox) → highest message seq this user has seen in that thread. */
    fun lastRead(boardId: String): Map<String, Long> =
        decode(ProjectStorage.readPrefs("read_$boardId.json"), readMap) ?: emptyMap()

    fun saveLastRead(boardId: String, read: Map<String, Long>) {
        ProjectStorage.writePrefs("read_$boardId.json", json.encodeToString(readMap, read))
    }

    private fun <T> decode(text: String?, serializer: kotlinx.serialization.KSerializer<T>): T? =
        text?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
}
