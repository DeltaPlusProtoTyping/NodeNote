package com.nodenote.model

import kotlinx.serialization.Serializable

@Serializable
enum class ElementKind { Text, Image }

/**
 * A non-node item on the canvas: a free-floating text block or an image.
 * Annotation layer — elements can't have connections, but they select, drag,
 * align, duplicate, delete and export exactly like nodes.
 *
 * For [ElementKind.Text], [text] is the content and [fontSize] its size (sp).
 * For [ElementKind.Image], [content] is the Base64-encoded image (embedded,
 * like attachments, so projects stay self-contained) and [text] holds the
 * original file name.
 */
@Serializable
data class CanvasElement(
    val id: String,
    val kind: ElementKind,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val text: String = "",
    val fontSize: Float = 14f,
    val content: String = "",
)
