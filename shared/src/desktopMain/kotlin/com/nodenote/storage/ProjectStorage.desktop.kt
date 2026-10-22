package com.nodenote.storage

import java.awt.FileDialog
import java.awt.Frame
import com.nodenote.LocalServer
import com.nodenote.local.DeviceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Desktop file access using AWT's native [FileDialog].
 *
 * FileDialog maps to the real Windows file dialog, needs no extra dependency,
 * and blocks until the user picks a file — which is fine here because picking
 * and saving are explicit user actions on the UI thread.
 */
actual object ProjectStorage {

    // App-level preferences (theme, user name, server address…) live in ~/.nodenote/.
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

    actual fun pickFile(accept: String, onResult: (PickedFile?) -> Unit) {
        val dialog = FileDialog(null as Frame?, "Open", FileDialog.LOAD)
        // An extension filter pre-fills the name box, which the Windows dialog honours as a filter.
        if (accept.startsWith(".")) dialog.file = "*$accept"
        dialog.isVisible = true

        val dir = dialog.directory
        val name = dialog.file
        onResult(
            if (dir == null || name == null) {
                null
            } else {
                try {
                    PickedFile(name, File(dir, name).readBytes())
                } catch (e: Exception) {
                    null
                }
            },
        )
    }

    actual fun saveFile(suggestedFileName: String, bytes: ByteArray, onResult: (String?) -> Unit) {
        val dialog = FileDialog(null as Frame?, "Save", FileDialog.SAVE)
        dialog.file = suggestedFileName
        dialog.isVisible = true

        val dir = dialog.directory
        val name = dialog.file
        if (dir == null || name == null) {
            onResult(null)
            return
        }
        // Keep the suggested extension if the user typed a bare name.
        val ext = suggestedFileName.substringAfterLast('.', "")
        val file = File(dir, if (ext.isNotEmpty() && !name.contains('.')) "$name.$ext" else name)
        onResult(
            try {
                file.writeBytes(bytes)
                file.absolutePath
            } catch (e: Exception) {
                null
            },
        )
    }
}

actual fun defaultServerUrl(): String = "http://localhost:8870"

actual val isWebPlatform: Boolean = false

actual fun readClipboardText(onResult: (String?) -> Unit) {
    onResult(
        runCatching {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                .getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
        }.getOrNull(),
    )
}

actual fun setPageTitle(title: String) = Unit

actual fun reloadPage(): Boolean = false

actual fun reloadWhenHidden(canReload: () -> Boolean) = Unit

actual fun deviceStore(): DeviceStore = FileDeviceStore(File(System.getProperty("user.home"), ".nodenote/device"))

/** A file per key; written to a temporary file first and swapped in, so a crash never leaves half a file. */
private class FileDeviceStore(private val dir: File) : DeviceStore {
    override fun read(key: String): String? = try {
        File(dir, key).takeIf { it.isFile }?.readText()
    } catch (e: Exception) {
        null
    }

    override fun write(key: String, value: String): Boolean = try {
        dir.mkdirs()
        val tmp = File(dir, "$key.tmp")
        tmp.writeText(value)
        Files.move(tmp.toPath(), File(dir, key).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        true
    } catch (e: Exception) {
        false
    }
}

/** The word list comes with the built-in server's files. */
actual suspend fun bundledDictionary(): String? = withContext(Dispatchers.IO) {
    ProjectStorage::class.java.getResourceAsStream("/dict/en_US.txt")?.use { it.readBytes().decodeToString() }
}

actual fun startServerOnThisComputer(serverUrl: String) = LocalServer.startIfNeeded(serverUrl)
