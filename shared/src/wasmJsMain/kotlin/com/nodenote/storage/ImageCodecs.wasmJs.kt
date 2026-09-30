package com.nodenote.storage

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

// The browser build renders with Skia (Skiko on Wasm), so these match the desktop actuals.

actual fun decodeImageOrNull(bytes: ByteArray): ImageBitmap? = try {
    Image.makeFromEncoded(bytes).toComposeImageBitmap()
} catch (e: Exception) {
    null
}

actual fun encodeToPng(bitmap: ImageBitmap): ByteArray? = try {
    Image.makeFromBitmap(bitmap.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes
} catch (e: Exception) {
    null
}

/** Writing images to the browser clipboard needs a secure-context async API; use Export PNG instead. */
actual fun copyImageToClipboard(bitmap: ImageBitmap): Boolean = false
