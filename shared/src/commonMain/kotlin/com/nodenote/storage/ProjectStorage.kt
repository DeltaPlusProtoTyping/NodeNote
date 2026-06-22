package com.nodenote.storage

/**
 * Platform file access — the only expect/actual seam in the app.
 *
 * Desktop shows native save/open dialogs (see ProjectStorage.desktop.kt);
 * iOS reads/writes a fixed file in the app's Documents directory for V1
 * (see ProjectStorage.ios.kt). A future document-picker implementation
 * only needs to touch the actuals.
 */
/** A file the user picked to attach to a node, fully read into memory. */
class PickedFile(val name: String, val bytes: ByteArray)

/** An entry in the file-explorer tree: a folder or a project (.json) file. */
class FileNode(val name: String, val path: String, val isDirectory: Boolean)

expect object ProjectStorage {

    /** Writes [json] to a user-chosen (or platform-default) location. Returns the saved path, or null if cancelled/failed. */
    fun saveProject(json: String, suggestedFileName: String): String?

    /** Returns (path, file content) of a user-chosen (or platform-default) project file, or null if cancelled/missing. */
    fun loadProject(): Pair<String, String>?

    /** Writes [content] directly to [path] with no dialog (used by plain Save once a file is known). */
    fun writeToPath(path: String, content: String): Boolean

    /** Reads a file directly by path with no dialog (used by Open Recent). Null if missing/unreadable. */
    fun readFromPath(path: String): String?

    /** Reads an app-level preferences file (e.g. export presets), or null if it doesn't exist. */
    fun readPrefs(fileName: String): String?

    /** Writes an app-level preferences file. */
    fun writePrefs(fileName: String, content: String): Boolean

    /** Opens a file picker and reads the chosen file (for node attachments). Null if cancelled or unreadable. */
    fun pickAttachmentFile(): PickedFile?

    /** Writes attachment [bytes] back out to disk. Returns the saved path, or null if cancelled/failed. */
    fun saveAttachmentAs(suggestedFileName: String, bytes: ByteArray): String?

    /** Lists folders and .json files directly under [path] (folders first, alphabetical). For the file explorer. */
    fun listDirectory(path: String): List<FileNode>

    /** Default starting folder for the file explorer (the user's home directory on desktop). */
    fun homeDirectory(): String

    /** The parent of [path], or null if it's a filesystem root. */
    fun parentDirectory(path: String): String?

    /** Opens a native folder picker; returns the chosen folder path or null if cancelled. */
    fun pickDirectory(): String?
}
