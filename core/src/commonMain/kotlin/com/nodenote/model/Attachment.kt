package com.nodenote.model

import kotlinx.serialization.Serializable

@Serializable
enum class AttachmentKind { Image, File, Text }

/**
 * A piece of content attached to a node, shown in the inspector.
 *
 * Text attachments keep their text directly in [content]. Images and files are
 * embedded as Base64 in [content], so a saved project is one fully
 * self-contained .json file — nothing breaks when the project is moved to
 * another machine (or platform). The trade-off is project file size, which is
 * why the UI caps embedded files at [MAX_EMBED_BYTES].
 */
@Serializable
data class Attachment(
    val id: String,
    val kind: AttachmentKind,
    val name: String,
    val content: String = "",
) {
    /** Approximate decoded size in bytes for Image/File attachments (Base64 is 4 chars per 3 bytes). */
    val approxByteSize: Int get() = content.length * 3 / 4

    companion object {
        const val MAX_EMBED_BYTES = 25 * 1024 * 1024
    }
}

private val ImageExtensions = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")

/** Files with an image extension become Image attachments (thumbnail in the inspector); everything else is a File. */
fun attachmentKindForFileName(name: String): AttachmentKind {
    val ext = name.substringAfterLast('.', "").lowercase()
    return if (ext in ImageExtensions) AttachmentKind.Image else AttachmentKind.File
}

fun formatByteSize(bytes: Int): String = when {
    bytes >= 1_048_576 -> "${(bytes * 10L / 1_048_576) / 10.0} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
