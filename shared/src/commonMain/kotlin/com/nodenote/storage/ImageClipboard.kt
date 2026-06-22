package com.nodenote.storage

import androidx.compose.ui.graphics.ImageBitmap

/** Puts a rendered image on the OS clipboard (paste into chat/email/docs). Returns false where unsupported. */
expect fun copyImageToClipboard(bitmap: ImageBitmap): Boolean
