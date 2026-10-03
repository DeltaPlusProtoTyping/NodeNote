package com.nodenote.export

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Writes a [Scene] as a one-page PDF 1.4 file that opens in any viewer: 1 scene unit = 1 pt (scaled
 * down uniformly if a side would exceed Acrobat's 14400 pt page limit), text in the viewer's built-in
 * Helvetica (nothing embedded), JPEGs passed through and other images as raw RGB + alpha mask.
 */
object PdfWriter {
    private const val MAX_PAGE = 14400f
    /** Acrobat's smallest page side. */
    private const val MIN_PAGE = 3f
    /** Bézier control-point distance for a quarter circle, as a fraction of the radius. */
    private const val KAPPA = 0.5522848f
    /** Objects 1–7 are fixed (see [write]); images follow. */
    private const val FIRST_IMAGE = 8

    fun write(scene: Scene): ByteArray {
        val sceneW = sceneSide(scene.width)
        val sceneH = sceneSide(scene.height)
        val scale = min(1f, MAX_PAGE / max(sceneW, sceneH))
        val pageW = formatNumber(max(sceneW * scale, MIN_PAGE))
        val pageH = formatNumber(max(sceneH * scale, MIN_PAGE))

        val page = Page()
        // PDF's y axis points up: flip (and scale) so the content below is written in scene units.
        page.op("${formatNumber(scale, 6)} 0 0 ${formatNumber(-scale, 6)} 0 $pageH cm")
        scene.background?.let { page.draw(SceneRect(0f, 0f, sceneW, sceneH, fill = it)) }
        for (item in scene.items) page.draw(item)

        val group = if (page.usesTransparency) " /Group << /Type /Group /S /Transparency /CS /DeviceRGB >>" else ""
        val objects = listOf(
            PdfObject("/Type /Catalog /Pages 2 0 R"),
            PdfObject("/Type /Pages /Kids [3 0 R] /Count 1"),
            PdfObject("/Type /Page /Parent 2 0 R /MediaBox [0 0 $pageW $pageH] /Resources << ${page.resources()} >> /Contents 4 0 R$group"),
            PdfObject("", page.content.toByteArray()),
            PdfObject("/Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding"),
            PdfObject("/Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding"),
            PdfObject("/Title ${textString(scene.title)} /Producer (NodeNote)"),
        ) + page.images

        val out = ByteBuf()
        out.ascii("%PDF-1.4\n%")
        for (b in intArrayOf(0xE2, 0xE3, 0xCF, 0xD3)) out.byte(b) // marks the file as binary for transfer tools
        out.ascii("\n")
        val offsets = IntArray(objects.size)
        objects.forEachIndexed { i, obj ->
            offsets[i] = out.size
            out.ascii("${i + 1} 0 obj\n")
            val stream = obj.stream
            if (stream == null) {
                out.ascii("<< ${obj.dict} >>\nendobj\n")
            } else {
                out.ascii("<< ${"${obj.dict} /Length ${stream.size}".trim()} >>\nstream\n")
                out.bytes(stream)
                out.ascii("\nendstream\nendobj\n")
            }
        }
        val xref = out.size
        out.ascii("xref\n0 ${objects.size + 1}\n0000000000 65535 f\r\n")
        for (offset in offsets) out.ascii("${offset.toString().padStart(10, '0')} 00000 n\r\n")
        out.ascii("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R /Info 7 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }

    /** [text] in WinAnsiEncoding as it is drawn (see [forEachWinAnsi]); characters WinAnsi lacks become '?'. */
    internal fun encodeText(text: String): ByteArray {
        val out = ByteArray(text.length)
        var n = 0
        forEachWinAnsi(text) { out[n++] = (if (it < 0) '?'.code else it).toByte() }
        return out.copyOf(n)
    }

    /** [s] as a PDF text string: a literal when it's printable ASCII, else UTF-16BE with a BOM, in hex. */
    private fun textString(s: String): String = buildString {
        if (s.all { it in ' '..'~' }) {
            append('(')
            for (c in s) {
                if (c == '(' || c == ')' || c == '\\') append('\\')
                append(c)
            }
            append(')')
        } else {
            append("<FEFF")
            for (c in s) append(c.code.toString(16).uppercase().padStart(4, '0'))
            append('>')
        }
    }

    /** A passed-through JPEG: frame size and components, plus its EXIF orientation (1–8, 1 = as stored). */
    private class JpegInfo(val width: Int, val height: Int, val components: Int, val orientation: Int)

    /**
     * Frame header and EXIF orientation of a JPEG, or null if [b] isn't a JPEG that every PDF viewer
     * decodes (8-bit Huffman baseline/extended/progressive with 1, 3 or 4 components).
     */
    private fun jpegInfo(b: ByteArray): JpegInfo? {
        fun u8(i: Int) = b[i].toInt() and 0xFF
        fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        if (b.size < 4 || u8(0) != 0xFF || u8(1) != 0xD8) return null
        var orientation = 1
        var i = 2
        while (i + 3 < b.size) {
            if (u8(i) != 0xFF) return null
            val marker = u8(i + 1)
            when {
                marker == 0xFF -> { i++; continue } // fill byte
                marker == 0x01 || marker in 0xD0..0xD8 -> { i += 2; continue } // markers without a length
                marker == 0xD9 || marker == 0xDA -> return null // end of image / scan data before any frame header
            }
            val length = u16(i + 2)
            if (length < 2) return null
            if (marker == 0xE1) orientation = exifOrientation(b, i + 4, min(b.size, i + 2 + length)) ?: orientation
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                // SOF: precision, height, width, components. Lossless, hierarchical and arithmetic-coded
                // JPEGs (C3, C5+) aren't supported by every viewer.
                if (marker > 0xC2 || i + 9 >= b.size) return null
                val width = u16(i + 7)
                val height = u16(i + 5)
                val components = u8(i + 9)
                val ok = u8(i + 4) == 8 && width > 0 && height > 0 && (components == 1 || components == 3 || components == 4)
                return if (ok) JpegInfo(width, height, components, orientation) else null
            }
            i += 2 + length
        }
        return null
    }

    /** The orientation tag (1–8) of an APP1 segment whose payload is b[start, end), or null if it has none. */
    private fun exifOrientation(b: ByteArray, start: Int, end: Int): Int? {
        val exif = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0) // "Exif\0\0", then a TIFF header
        if (end - start < 14 || exif.indices.any { b[start + it] != exif[it] }) return null
        val tiff = start + 6
        val little = when {
            b[tiff] == 0x49.toByte() && b[tiff + 1] == 0x49.toByte() -> true // "II"
            b[tiff] == 0x4D.toByte() && b[tiff + 1] == 0x4D.toByte() -> false // "MM"
            else -> return null
        }
        fun u16(i: Int): Int {
            val first = b[i].toInt() and 0xFF
            val second = b[i + 1].toInt() and 0xFF
            return if (little) first or (second shl 8) else (first shl 8) or second
        }
        fun u32(i: Int): Long =
            if (little) u16(i).toLong() or (u16(i + 2).toLong() shl 16) else (u16(i).toLong() shl 16) or u16(i + 2).toLong()
        if (u16(tiff + 2) != 42) return null
        val ifdOffset = u32(tiff + 4)
        if (ifdOffset < 8 || ifdOffset > end - tiff - 2) return null
        val ifd = tiff + ifdOffset.toInt()
        for (k in 0 until u16(ifd)) {
            val entry = ifd + 2 + 12 * k
            if (entry + 12 > end) break
            if (u16(entry) == 0x0112) return u16(entry + 8).takeIf { it in 1..8 } // SHORT value, left-justified
        }
        return null
    }

    /**
     * For each EXIF orientation 1–8: the box corners (0 top-left, 1 top-right, 2 bottom-left,
     * 3 bottom-right) where the stored image's top-left, top-right and bottom-left corners belong.
     */
    private val ORIENTED_CORNERS = listOf("012", "103", "321", "230", "021", "130", "312", "203")

    /** One indirect object: a dictionary (without << >>), plus its stream data if it's a stream. */
    private class PdfObject(val dict: String, val stream: ByteArray? = null)

    /** The page's content stream plus the resources (alpha states, images) it uses. */
    private class Page {
        val content = ByteBuf()
        val images = ArrayList<PdfObject>()
        var usesTransparency = false
            private set
        private val alphas = LinkedHashSet<Int>()
        private val xObjects = ArrayList<Pair<String, Int>>()

        // Graphics state as last written (starting from PDF's defaults), so operators are only emitted on change.
        private var fillRgb = 0L
        private var strokeRgb = 0L
        private var alpha = 255
        private var lineWidth = 1f
        private var roundCap = false
        private var dash: List<Float>? = null
        private var charSpacing = 0f

        fun op(s: String) {
            content.ascii(s)
            content.byte('\n'.code)
        }

        fun draw(item: SceneItem) {
            when (item) {
                is SceneRect -> {
                    val w = abs(item.width)
                    val h = abs(item.height)
                    if (!(w > 0f && h > 0f)) return
                    val x = min(item.x, item.x + item.width)
                    val y = min(item.y, item.y + item.height)
                    item.fill?.let { fill(it) { rectPath(x, y, w, h, item.radius) } }
                    item.stroke?.let { stroke(it, item.strokeWidth, false, item.dash) { rectPath(x, y, w, h, item.radius) } }
                }
                is SceneCircle -> {
                    if (!(item.radius > 0f)) return
                    item.fill?.let { fill(it) { circlePath(item.cx, item.cy, item.radius) } }
                    item.stroke?.let { stroke(it, item.strokeWidth, false, null) { circlePath(item.cx, item.cy, item.radius) } }
                }
                is SceneLine -> stroke(item.color, item.width, item.roundCap, item.dash) {
                    op("${num(item.x1)} ${num(item.y1)} m ${num(item.x2)} ${num(item.y2)} l")
                }
                is ScenePolygon -> if (item.points.size >= 6) fill(item.fill) {
                    val p = item.points
                    op("${num(p[0])} ${num(p[1])} m")
                    for (i in 1 until p.size / 2) op("${num(p[2 * i])} ${num(p[2 * i + 1])} l")
                    op("h")
                }
                is SceneText -> text(item)
                is SceneImage -> image(item)
            }
        }

        private fun text(t: SceneText) {
            val bytes = encodeText(t.text)
            if (bytes.isEmpty() || !(t.size > 0f) || !paints(t.color)) return
            var units = 0
            for (b in bytes) units += Helvetica.advance(b.toInt() and 0xFF, t.bold)
            val width = units * t.size / 1000f + t.letterSpacing * bytes.size
            val x = when (t.anchor) {
                TextAnchor.Start -> t.x
                TextAnchor.Middle -> t.x - width / 2
                TextAnchor.End -> t.x - width
            }
            setAlpha(alphaOf(t.color))
            setColor(t.color, stroke = false)
            if (t.letterSpacing != charSpacing) {
                op("${num(t.letterSpacing)} Tc")
                charSpacing = t.letterSpacing
            }
            op("BT")
            op("/${if (t.bold) "F2" else "F1"} ${num(t.size)} Tf")
            op("1 0 0 -1 ${num(x)} ${num(t.y)} Tm") // un-flips the glyphs on the y-down page
            content.byte('('.code)
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                if (v == '('.code || v == ')'.code || v == '\\'.code) content.byte('\\'.code)
                content.byte(v)
            }
            op(") Tj")
            op("ET")
        }

        private fun image(img: SceneImage) {
            val w = abs(img.width)
            val h = abs(img.height)
            if (!(w > 0f && h > 0f)) return
            val x = min(img.x, img.x + img.width)
            val y = min(img.y, img.y + img.height)
            val embedded = addImage(img)
            if (embedded == null) {
                fill(PLACEHOLDER_FILL) { rectPath(x, y, w, h, 0f) }
                stroke(PLACEHOLDER_STROKE, 1f, false, null) { rectPath(x, y, w, h, 0f) }
                return
            }
            // An image fills the unit square with its first row at the top (y = 1). Map that square onto
            // the box, turned the way the EXIF orientation says (browsers do the same for the SVG).
            val corners = ORIENTED_CORNERS[embedded.orientation - 1]
            fun cx(i: Int) = x + ((corners[i] - '0') and 1) * w
            fun cy(i: Int) = y + ((corners[i] - '0') shr 1) * h
            val matrix = listOf(cx(1) - cx(0), cy(1) - cy(0), cx(0) - cx(2), cy(0) - cy(2), cx(2), cy(2))
            op("q ${matrix.joinToString(" ") { num(it) }} cm /${embedded.name} Do Q")
        }

        private class Embedded(val name: String, val orientation: Int)

        /** Adds [img] as an image XObject (plus soft mask), or returns null if it can't be embedded. */
        private fun addImage(img: SceneImage): Embedded? {
            val number = FIRST_IMAGE + images.size
            val jpeg = jpegInfo(img.bytes)
            if (jpeg != null) {
                val colorSpace = when (jpeg.components) {
                    1 -> "/DeviceGray"
                    3 -> "/DeviceRGB"
                    else -> "/DeviceCMYK /Decode [1 0 1 0 1 0 1 0]" // CMYK JPEGs (Adobe) store inverted values
                }
                images += PdfObject(
                    "/Type /XObject /Subtype /Image /Width ${jpeg.width} /Height ${jpeg.height} " +
                        "/ColorSpace $colorSpace /BitsPerComponent 8 /Filter /DCTDecode",
                    img.bytes,
                )
            } else {
                val argb = img.argb ?: return null
                val w = img.pixelWidth
                val h = img.pixelHeight
                if (w <= 0 || h <= 0 || argb.size.toLong() != w.toLong() * h || argb.size > Int.MAX_VALUE / 3) return null
                val rgb = ByteArray(argb.size * 3)
                var alpha: ByteArray? = null
                for (i in argb.indices) {
                    val p = argb[i]
                    rgb[3 * i] = (p shr 16).toByte()
                    rgb[3 * i + 1] = (p shr 8).toByte()
                    rgb[3 * i + 2] = p.toByte()
                    val a = p ushr 24
                    if (a != 255) {
                        if (alpha == null) alpha = ByteArray(argb.size) { -1 }
                        alpha[i] = a.toByte()
                    }
                }
                val mask = alpha
                val smask = if (mask != null) " /SMask ${number + 1} 0 R" else ""
                images += PdfObject("/Type /XObject /Subtype /Image /Width $w /Height $h /ColorSpace /DeviceRGB /BitsPerComponent 8$smask", rgb)
                if (mask != null) {
                    images += PdfObject("/Type /XObject /Subtype /Image /Width $w /Height $h /ColorSpace /DeviceGray /BitsPerComponent 8", mask)
                    usesTransparency = true
                }
            }
            val name = "Im${xObjects.size + 1}"
            xObjects += name to number
            return Embedded(name, jpeg?.orientation ?: 1)
        }

        fun resources(): String = buildString {
            append("/ProcSet [/PDF /Text /ImageB /ImageC] /Font << /F1 5 0 R /F2 6 0 R >>")
            if (alphas.isNotEmpty()) {
                append(" /ExtGState <<")
                for (a in alphas) {
                    val v = formatNumber(a / 255.0, 3)
                    append(" /GA$a << /Type /ExtGState /ca $v /CA $v >>")
                }
                append(" >>")
            }
            if (xObjects.isNotEmpty()) {
                append(" /XObject <<")
                for ((name, number) in xObjects) append(" /$name $number 0 R")
                append(" >>")
            }
        }

        private inline fun fill(argb: Long, path: () -> Unit) {
            if (!paints(argb)) return
            setAlpha(alphaOf(argb))
            setColor(argb, stroke = false)
            path()
            op("f")
        }

        private inline fun stroke(argb: Long, width: Float, roundCap: Boolean, dash: List<Float>?, path: () -> Unit) {
            if (!strokes(argb, width)) return
            setAlpha(alphaOf(argb))
            setColor(argb, stroke = true)
            if (width != lineWidth) {
                op("${num(width)} w")
                lineWidth = width
            }
            if (roundCap != this.roundCap) {
                op(if (roundCap) "1 J" else "0 J")
                this.roundCap = roundCap
            }
            val pattern = usableDash(dash)
            if (pattern != this.dash) {
                op(pattern?.joinToString(" ", "[", "] 0 d") { num(it) } ?: "[] 0 d")
                this.dash = pattern
            }
            path()
            op("S")
        }

        /** Selects the alpha (for fill and stroke alike) through an ExtGState, one per distinct value. */
        private fun setAlpha(a: Int) {
            if (a == alpha) return
            alphas += a
            if (a < 255) usesTransparency = true
            op("/GA$a gs")
            alpha = a
        }

        private fun setColor(argb: Long, stroke: Boolean) {
            val rgb = argb and 0xFFFFFF
            if (rgb == (if (stroke) strokeRgb else fillRgb)) return
            fun channel(shift: Int) = formatNumber(((rgb shr shift) and 0xFF) / 255.0, 3)
            op("${channel(16)} ${channel(8)} ${channel(0)} ${if (stroke) "RG" else "rg"}")
            if (stroke) strokeRgb = rgb else fillRgb = rgb
        }

        private fun rectPath(x: Float, y: Float, w: Float, h: Float, radius: Float) {
            val r = min(radius, min(w, h) / 2)
            if (!(r > 0f)) {
                op("${num(x)} ${num(y)} ${num(w)} ${num(h)} re")
                return
            }
            val k = r * (1 - KAPPA) // corner to control point
            val x2 = x + w
            val y2 = y + h
            op("${num(x + r)} ${num(y)} m ${num(x2 - r)} ${num(y)} l")
            curve(x2 - k, y, x2, y + k, x2, y + r)
            op("${num(x2)} ${num(y2 - r)} l")
            curve(x2, y2 - k, x2 - k, y2, x2 - r, y2)
            op("${num(x + r)} ${num(y2)} l")
            curve(x + k, y2, x, y2 - k, x, y2 - r)
            op("${num(x)} ${num(y + r)} l")
            curve(x, y + k, x + k, y, x + r, y)
            op("h")
        }

        private fun circlePath(cx: Float, cy: Float, r: Float) {
            val k = r * KAPPA
            op("${num(cx + r)} ${num(cy)} m")
            curve(cx + r, cy + k, cx + k, cy + r, cx, cy + r)
            curve(cx - k, cy + r, cx - r, cy + k, cx - r, cy)
            curve(cx - r, cy - k, cx - k, cy - r, cx, cy - r)
            curve(cx + k, cy - r, cx + r, cy - k, cx + r, cy)
            op("h")
        }

        private fun curve(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) =
            op("${num(x1)} ${num(y1)} ${num(x2)} ${num(y2)} ${num(x3)} ${num(y3)} c")

        private fun num(v: Float) = formatNumber(v)
    }

    /** Growable byte buffer (common Kotlin has no ByteArrayOutputStream). */
    private class ByteBuf {
        private var data = ByteArray(4096)
        var size = 0
            private set

        private fun ensure(extra: Int) {
            if (size + extra > data.size) data = data.copyOf(max(data.size * 2, size + extra))
        }

        fun byte(b: Int) {
            ensure(1)
            data[size++] = b.toByte()
        }

        fun bytes(b: ByteArray) {
            ensure(b.size)
            b.copyInto(data, size)
            size += b.size
        }

        /** Appends [s], which must be ASCII. */
        fun ascii(s: String) {
            ensure(s.length)
            for (c in s) data[size++] = c.code.toByte()
        }

        fun toByteArray(): ByteArray = data.copyOf(size)
    }
}
