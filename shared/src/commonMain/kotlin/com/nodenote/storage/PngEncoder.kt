package com.nodenote.storage

import androidx.compose.ui.graphics.ImageBitmap

/** Encodes a rendered bitmap to PNG bytes (Skia on both desktop and iOS), or null on failure. */
expect fun encodeToPng(bitmap: ImageBitmap): ByteArray?
