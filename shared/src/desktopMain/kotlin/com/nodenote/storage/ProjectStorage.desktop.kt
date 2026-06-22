package com.nodenote.storage

import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Desktop file access using AWT's native [FileDialog].
 *
 * FileDialog maps to the real Windows file dialog, needs no extra dependency,
 * and blocks until the user picks a file — which is fine here because saving
 * and loading are explicit user actions on the UI thread.
 */
actual object ProjectStorage {

    actual fun saveProject(json: String, suggestedFileName: String): String? {
        val dialog = FileDialog(null as Frame?, "Save Project", FileDialog.SAVE)
        dialog.file = suggestedFileName
        dialog.isVisible = true

        val dir = dialog.directory ?: return null
        val name = dialog.file ?: return null
        val file = File(dir, if (name.endsWith(".json", ignoreCase = true)) name else "$name.json")
        return try {
            file.writeText(json)
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    actual fun loadProject(): Pair<String, String>? {
        val dialog = FileDialog(null as Frame?, "Open Project", FileDialog.LOAD)
        dialog.file = "*.json"
        dialog.isVisible = true

        val dir = dialog.directory ?: return null
        val name = dialog.file ?: return null
        val file = File(dir, name)
        return try {
            file.absolutePath to file.readText()
        } catch (e: Exception) {
            null
        }
    }

    actual fun writeToPath(path: String, content: String): Boolean = try {
        File(path).writeText(content)
        true
    } catch (e: Exception) {
        false
    }

    actual fun readFromPath(path: String): String? = try {
        File(path).takeIf { it.exists() }?.readText()
    } catch (e: Exception) {
        null
    }

    // App-level preferences (export presets etc.) live in ~/.nodenote/.
    private fun prefsDir(): File = File(System.getProperty("user.home"), ".nodenote").apply { mkdirs() }

    actual fun readPrefs(fileName: String): String? = try {
        File(prefsDir(), fileName).takeIf { it.exists() }?.readText()
    } catch (e: Exception) {
        null
    }

    actual fun writePrefs(fileName: String, content: String): Boolean = try {
        File(prefsDir(), fileName).writeText(content)
        true
    } catch (e: Exception) {
        false
    }

    actual fun pickAttachmentFile(): PickedFile? {
        val dialog = FileDialog(null as Frame?, "Attach File", FileDialog.LOAD)
        dialog.isVisible = true

        val dir = dialog.directory ?: return null
        val name = dialog.file ?: return null
        return try {
            PickedFile(name, File(dir, name).readBytes())
        } catch (e: Exception) {
            null
        }
    }

    actual fun listDirectory(path: String): List<FileNode> = try {
        val entries = File(path).listFiles() ?: emptyArray()
        entries
            .filterNot { it.isHidden }
            .filter { it.isDirectory || it.extension.equals("json", ignoreCase = true) }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .map { FileNode(it.name, it.absolutePath, it.isDirectory) }
    } catch (e: Exception) {
        emptyList()
    }

    actual fun homeDirectory(): String = System.getProperty("user.home") ?: "."

    actual fun parentDirectory(path: String): String? = File(path).parentFile?.absolutePath

    actual fun pickDirectory(): String? {
        val chooser = javax.swing.JFileChooser().apply {
            fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
            dialogTitle = "Open Folder"
        }
        return if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile?.absolutePath
        } else {
            null
        }
    }

    actual fun saveAttachmentAs(suggestedFileName: String, bytes: ByteArray): String? {
        val dialog = FileDialog(null as Frame?, "Save Attachment", FileDialog.SAVE)
        dialog.file = suggestedFileName
        dialog.isVisible = true

        val dir = dialog.directory ?: return null
        val name = dialog.file ?: return null
        val file = File(dir, name)
        return try {
            file.writeBytes(bytes)
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }
}
