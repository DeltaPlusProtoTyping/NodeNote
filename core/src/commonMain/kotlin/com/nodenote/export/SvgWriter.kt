package com.nodenote.export

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.abs
import kotlin.math.min

/** Writes a [Scene] as a standalone SVG 1.1 document that opens directly in any browser (save it as UTF-8). */
object SvgWriter {
    private const val FONT = "Helvetica, Arial, sans-serif"
    private const val HEX = "0123456789abcdef"

    fun write(scene: Scene): String {
        val sb = StringBuilder()
        val w = formatNumber(sceneSide(scene.width))
        val h = formatNumber(sceneSide(scene.height))
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" version=\"1.1\"")
            .append(" width=\"$w\" height=\"$h\" viewBox=\"0 0 $w $h\">\n")
        sb.append("<title>").appendEscaped(scene.title).append("</title>\n")
        scene.background?.let { if (paints(it)) sb.append("<rect width=\"$w\" height=\"$h\"").paint("fill", it).append("/>\n") }
        for (item in scene.items) sb.item(item)
        sb.append("</svg>\n")
        return sb.toString()
    }

    private fun StringBuilder.item(item: SceneItem) {
        when (item) {
            is SceneRect -> rect(item.x, item.y, item.width, item.height, item.radius, item.fill, item.stroke, item.strokeWidth, item.dash)
            is SceneCircle -> {
                if (!(item.radius > 0f) || !paints(item.fill) && !strokes(item.stroke, item.strokeWidth)) return
                append("<circle cx=\"").num(item.cx).append("\" cy=\"").num(item.cy).append("\" r=\"").num(item.radius).append('"')
                paint("fill", item.fill)
                stroke(item.stroke, item.strokeWidth, roundCap = false, dash = null)
                append("/>\n")
            }
            is SceneLine -> {
                if (!strokes(item.color, item.width)) return
                append("<line x1=\"").num(item.x1).append("\" y1=\"").num(item.y1)
                    .append("\" x2=\"").num(item.x2).append("\" y2=\"").num(item.y2).append('"')
                stroke(item.color, item.width, item.roundCap, item.dash)
                append("/>\n")
            }
            is ScenePolygon -> {
                val p = item.points
                if (p.size < 6 || !paints(item.fill)) return
                append("<polygon points=\"")
                for (i in 0 until p.size / 2) {
                    if (i > 0) append(' ')
                    num(p[2 * i]).append(',').num(p[2 * i + 1])
                }
                append('"').paint("fill", item.fill).append("/>\n")
            }
            is SceneText -> text(item)
            is SceneImage -> image(item)
        }
    }

    private fun StringBuilder.rect(
        x: Float, y: Float, width: Float, height: Float, radius: Float,
        fill: Long?, stroke: Long?, strokeWidth: Float, dash: List<Float>?,
    ) {
        val w = abs(width)
        val h = abs(height)
        if (!(w > 0f && h > 0f) || !paints(fill) && !strokes(stroke, strokeWidth)) return
        append("<rect x=\"").num(min(x, x + width)).append("\" y=\"").num(min(y, y + height))
            .append("\" width=\"").num(w).append("\" height=\"").num(h).append('"')
        val r = min(radius, min(w, h) / 2)
        if (r > 0f) append(" rx=\"").num(r).append("\" ry=\"").num(r).append('"')
        paint("fill", fill)
        stroke(stroke, strokeWidth, roundCap = false, dash = dash)
        append("/>\n")
    }

    private fun StringBuilder.text(t: SceneText) {
        if (t.text.isEmpty() || !(t.size > 0f) || !paints(t.color)) return
        append("<text x=\"").num(t.x).append("\" y=\"").num(t.y)
            .append("\" font-family=\"").append(FONT).append("\" font-size=\"").num(t.size).append('"')
        if (t.bold) append(" font-weight=\"bold\"")
        if (t.letterSpacing != 0f) append(" letter-spacing=\"").num(t.letterSpacing).append('"')
        val anchor = when (t.anchor) {
            TextAnchor.Start -> "start"
            TextAnchor.Middle -> "middle"
            TextAnchor.End -> "end"
        }
        append(" text-anchor=\"").append(anchor).append('"')
        paint("fill", t.color)
        append(" xml:space=\"preserve\">").appendEscaped(t.text).append("</text>\n")
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun StringBuilder.image(img: SceneImage) {
        val w = abs(img.width)
        val h = abs(img.height)
        if (!(w > 0f && h > 0f)) return
        val x = min(img.x, img.x + img.width)
        val y = min(img.y, img.y + img.height)
        val mime = mimeType(img.bytes)
        if (mime == null) {
            rect(x, y, w, h, 0f, PLACEHOLDER_FILL, PLACEHOLDER_STROKE, 1f, null)
            return
        }
        val uri = "data:$mime;base64," + Base64.encode(img.bytes)
        // href for current browsers, xlink:href for older ones (SVG 1.1).
        append("<image x=\"").num(x).append("\" y=\"").num(y).append("\" width=\"").num(w).append("\" height=\"").num(h)
            .append("\" preserveAspectRatio=\"none\" href=\"").append(uri).append("\" xlink:href=\"").append(uri).append("\"/>\n")
    }

    /** Image MIME type sniffed from the file's first bytes, or null if it isn't a format browsers show. */
    private fun mimeType(b: ByteArray): String? {
        fun startsWith(at: Int, vararg sig: Int) =
            b.size >= at + sig.size && sig.indices.all { (b[at + it].toInt() and 0xFF) == sig[it] }
        return when {
            startsWith(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png"
            startsWith(0, 0xFF, 0xD8, 0xFF) -> "image/jpeg"
            startsWith(0, 0x47, 0x49, 0x46, 0x38) -> "image/gif" // "GIF8"
            startsWith(0, 0x52, 0x49, 0x46, 0x46) && startsWith(8, 0x57, 0x45, 0x42, 0x50) -> "image/webp" // "RIFF....WEBP"
            startsWith(0, 0x42, 0x4D) -> "image/bmp" // "BM"
            else -> null
        }
    }

    /** ` attr="#rrggbb"` plus ` attr-opacity` when translucent; null or fully transparent paint is "none". */
    private fun StringBuilder.paint(attr: String, argb: Long?): StringBuilder {
        if (argb == null || !paints(argb)) return append(' ').append(attr).append("=\"none\"")
        append(' ').append(attr).append("=\"#")
        for (shift in intArrayOf(16, 8, 0)) {
            val v = ((argb shr shift) and 0xFF).toInt()
            append(HEX[v shr 4]).append(HEX[v and 0xF])
        }
        append('"')
        val alpha = alphaOf(argb)
        if (alpha < 255) append(' ').append(attr).append("-opacity=\"").append(formatNumber(alpha / 255.0, 3)).append('"')
        return this
    }

    private fun StringBuilder.stroke(color: Long?, width: Float, roundCap: Boolean, dash: List<Float>?) {
        if (!strokes(color, width)) return
        paint("stroke", color)
        append(" stroke-width=\"").num(width).append('"')
        if (roundCap) append(" stroke-linecap=\"round\"")
        val pattern = usableDash(dash) ?: return
        append(" stroke-dasharray=\"")
        pattern.forEachIndexed { i, v ->
            if (i > 0) append(' ')
            num(v)
        }
        append('"')
    }

    private fun StringBuilder.num(v: Float): StringBuilder = append(formatNumber(v))

    /** Appends [s] escaped for XML text or attribute values, dropping characters XML 1.0 forbids. */
    private fun StringBuilder.appendEscaped(s: String): StringBuilder {
        var i = 0
        while (i < s.length) {
            val c = s[i++]
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                c == '\'' -> append("&apos;")
                isXmlDropped(c) -> {}
                c.isHighSurrogate() -> if (i < s.length && s[i].isLowSurrogate()) {
                    append(c).append(s[i])
                    i++
                }
                c.isLowSurrogate() -> {}
                else -> append(c)
            }
        }
        return this
    }
}
