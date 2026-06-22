package com.nodenote.storage

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * iOS file access, V1: reads and writes a single project file in the app's
 * Documents directory (no document picker yet). Replace these two functions
 * with UIDocumentPickerViewController-based versions later without touching
 * any common code.
 */
actual object ProjectStorage {

    private const val DEFAULT_FILE = "project.json"

    private fun documentsPath(): String =
        NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String

    actual fun saveProject(json: String, suggestedFileName: String): String? {
        val path = documentsPath() + "/" + DEFAULT_FILE
        @Suppress("CAST_NEVER_SUCCEEDS")
        val ok = (json as NSString).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
        return if (ok) path else null
    }

    actual fun loadProject(): Pair<String, String>? {
        val path = documentsPath() + "/" + DEFAULT_FILE
        val content = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) ?: return null
        return path to content
    }

    // File-explorer browsing is a desktop feature for V1; iOS uses its single Documents file.
    actual fun listDirectory(path: String): List<FileNode> = emptyList()

    actual fun homeDirectory(): String = documentsPath()

    actual fun parentDirectory(path: String): String? = null

    actual fun pickDirectory(): String? = null

    actual fun writeToPath(path: String, content: String): Boolean {
        @Suppress("CAST_NEVER_SUCCEEDS")
        return (content as NSString).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }

    actual fun readFromPath(path: String): String? =
        NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)

    actual fun readPrefs(fileName: String): String? {
        val path = documentsPath() + "/" + fileName
        return NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)
    }

    actual fun writePrefs(fileName: String, content: String): Boolean =
        writeToPath(documentsPath() + "/" + fileName, content)

    /** Needs UIDocumentPickerViewController — not implemented in V1 (attachments can still be viewed/edited). */
    actual fun pickAttachmentFile(): PickedFile? = null

    actual fun saveAttachmentAs(suggestedFileName: String, bytes: ByteArray): String? {
        val path = documentsPath() + "/" + suggestedFileName
        return if (bytes.toNSData().writeToFile(path, atomically = true)) path else null
    }
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData()
    else usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = size.toULong()) }
