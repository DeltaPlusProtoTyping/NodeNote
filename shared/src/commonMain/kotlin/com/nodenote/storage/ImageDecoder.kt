package com.nodenote.storage

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decodes encoded image bytes (PNG/JPEG/…) into a Compose [ImageBitmap], or
 * null if the bytes aren't a decodable image. Desktop and iOS both implement
 * this with Skia, but the API isn't exposed in common code, hence expect/actual.
 */
expect fun decodeImageOrNull(bytes: ByteArray): ImageBitmap?
