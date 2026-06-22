package com.nodenote.storage

import androidx.compose.ui.graphics.ImageBitmap

/** Not implemented on iOS yet (needs UIPasteboard + UIImage conversion). */
actual fun copyImageToClipboard(bitmap: ImageBitmap): Boolean = false
