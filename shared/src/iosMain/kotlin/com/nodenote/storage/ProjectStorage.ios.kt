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
 * iOS storage, V1: preferences and saved files go to the app's Documents
 * directory; picking files needs UIDocumentPickerViewController and is not
 * implemented yet. Unverified — iOS only builds on a macOS host.
 */
actual object ProjectStorage {

    private fun documentsPath(): String =
        NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String

    actual fun readPrefs(fileName: String): String? =
        NSString.stringWithContentsOfFile(documentsPath() + "/" + fileName, NSUTF8StringEncoding, null)

    actual fun writePrefs(fileName: String, content: String): Boolean {
        @Suppress("CAST_NEVER_SUCCEEDS")
        return (content as NSString).writeToFile(
            documentsPath() + "/" + fileName,
            atomically = true,
            encoding = NSUTF8StringEncoding,
            error = null,
        )
    }

    actual fun pickFile(accept: String, onResult: (PickedFile?) -> Unit) = onResult(null)

    actual fun saveFile(suggestedFileName: String, bytes: ByteArray, onResult: (String?) -> Unit) {
        val path = documentsPath() + "/" + suggestedFileName
        onResult(if (bytes.toNSData().writeToFile(path, atomically = true)) path else null)
    }
}

actual fun defaultServerUrl(): String = "http://localhost:8870"

actual val isWebPlatform: Boolean = false

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData()
    else usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = size.toULong()) }

actual fun readClipboardText(onResult: (String?) -> Unit) =
    onResult(platform.UIKit.UIPasteboard.generalPasteboard.string)

actual fun setPageTitle(title: String) = Unit
