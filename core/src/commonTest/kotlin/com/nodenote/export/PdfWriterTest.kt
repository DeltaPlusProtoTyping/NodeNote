package com.nodenote.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PdfWriterTest {

    /** The file as one char per byte, so string offsets are byte offsets. */
    private fun pdf(scene: Scene): String {
        val bytes = PdfWriter.write(scene)
        return CharArray(bytes.size) { (bytes[it].toInt() and 0xFF).toChar() }.concatToString()
    }

    private fun codes(text: String) = PdfWriter.encodeText(text).map { it.toInt() and 0xFF }

    @Test
    fun encodesTextAsWinAnsi() {
        assertEquals(listOf(0xE9), codes("é"))
        assertEquals(listOf(0x97), codes("—"))
        assertEquals(listOf(0x92), codes("’"))
        assertEquals(listOf('?'.code), codes("漢"))
        assertEquals(listOf(0x80, 0x85, 0x95, 0x93, 0x94, 0x96, 0x91), codes("€…•“”–‘"))
        assertEquals("Hi ?!".map { it.code }, codes("Hi 😀!")) // a surrogate pair is one '?'
        assertEquals(listOf(0x41, 0x20, 0x42), codes("A\tB"))
        assertEquals(listOf(0x61, 0x62), codes("a\u0007\u00AD\u200Bb")) // control, soft hyphen, zero-width space
    }

    @Test
    fun writesASinglePageWithBase14Fonts() {
        val s = pdf(Scene(300f, 200f, listOf(SceneText(10f, 20f, "Café (draft) \\ 1", 12f, 0xFF000000)), title = "Shop"))
        assertTrue(s.startsWith("%PDF-1.4\n%"))
        assertTrue(s.substring(10, 14).all { it.code >= 0x80 })
        assertTrue(s.endsWith("%%EOF\n"))
        assertTrue(s.contains("/Type /Pages /Kids [3 0 R] /Count 1"))
        assertTrue(s.contains("/MediaBox [0 0 300 200]"))
        assertTrue(s.contains("1 0 0 -1 0 200 cm"))
        assertTrue(s.contains("/BaseFont /Helvetica /Encoding /WinAnsiEncoding"))
        assertTrue(s.contains("/BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding"))
        assertTrue(s.contains("BT\n/F1 12 Tf\n1 0 0 -1 10 20 Tm\n(Café \\(draft\\) \\\\ 1) Tj\nET"))
        assertTrue(s.contains("/Title (Shop) /Producer (NodeNote)"))
        assertFalse(s.contains("/Group"))
    }

    @Test
    fun anchorsAndLetterSpacingUseHelveticaWidths() {
        // "Hello" at 10 pt is 22.78 wide; letter spacing 1 adds 5.
        val s = pdf(
            Scene(
                100f, 100f,
                listOf(
                    SceneText(50f, 20f, "Hello", 10f, 0xFF000000, anchor = TextAnchor.Middle),
                    SceneText(50f, 40f, "Hello", 10f, 0xFF000000, bold = true, letterSpacing = 1f, anchor = TextAnchor.End),
                ),
            ),
        )
        assertTrue(s.contains("1 0 0 -1 38.61 20 Tm"))
        assertTrue(s.contains("1 Tc\nBT\n/F2 10 Tf\n1 0 0 -1 20.55 40 Tm")) // 50 - (24.45 + 5)
    }

    @Test
    fun nonAsciiTitleIsUtf16Hex() {
        assertTrue(pdf(Scene(10f, 10f, emptyList(), title = "Plan é")).contains("/Title <FEFF0050006C0061006E002000E9>"))
    }

    @Test
    fun usesOneExtGStatePerAlphaValue() {
        val s = pdf(
            Scene(
                100f, 100f,
                listOf(
                    SceneRect(0f, 0f, 10f, 10f, fill = 0x80FF0000),
                    SceneRect(20f, 0f, 10f, 10f, fill = 0x8000FF00),
                    SceneLine(0f, 50f, 100f, 50f, 0x40000000),
                    SceneRect(40f, 0f, 10f, 10f, fill = 0xFF0000FF),
                ),
            ),
        )
        assertTrue(s.contains("/GA128 << /Type /ExtGState /ca 0.502 /CA 0.502 >>"))
        assertTrue(s.contains("/GA64 << /Type /ExtGState /ca 0.251 /CA 0.251 >>"))
        assertTrue(s.contains("/GA255 << /Type /ExtGState /ca 1 /CA 1 >>"))
        assertEquals(3, s.split("/GA128 ").size) // one resource, selected once (the second red rect reuses it)
        assertTrue(s.contains("/Group << /Type /Group /S /Transparency /CS /DeviceRGB >>"))
    }

    @Test
    fun strokesWithDashesAndRoundCaps() {
        val s = pdf(Scene(100f, 100f, listOf(SceneLine(1f, 2f, 3f, 4f, 0xFF000000, width = 2f, dash = listOf(4f, 2.5f)))))
        assertTrue(s.contains("2 w\n1 J\n[4 2.5] 0 d\n1 2 m 3 4 l\nS"))
    }

    @Test
    fun passesJpegsThroughWithTheColorSpaceFromTheFrameHeader() {
        val image = SceneImage(5f, 5f, 30f, 20f, jpegHeader(components = 4, width = 3, height = 2))
        val s = pdf(Scene(50f, 50f, listOf(image)))
        assertTrue(s.contains("/Width 3 /Height 2 /ColorSpace /DeviceCMYK /Decode [1 0 1 0 1 0 1 0] /BitsPerComponent 8 /Filter /DCTDecode"))
        assertTrue(s.contains("/XObject << /Im1 8 0 R >>"))
        assertTrue(s.contains("q 30 0 0 -20 5 25 cm /Im1 Do Q"))
        val gray = pdf(Scene(50f, 50f, listOf(SceneImage(0f, 0f, 1f, 1f, jpegHeader(components = 1, width = 7, height = 9)))))
        assertTrue(gray.contains("/Width 7 /Height 9 /ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /DCTDecode"))
    }

    @Test
    fun embedsDecodedPixelsWithASoftMaskOnlyWhenTranslucent() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        fun image(vararg argb: Long) = SceneImage(0f, 0f, 10f, 10f, png, IntArray(argb.size) { argb[it].toInt() }, argb.size, 1)

        val opaque = pdf(Scene(50f, 50f, listOf(image(0xFFFF0000, 0xFF00FF00))))
        assertTrue(opaque.contains("/Width 2 /Height 1 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Length 6 >>\nstream\n\u00FF\u0000\u0000\u0000\u00FF\u0000\n"))
        assertFalse(opaque.contains("/SMask"))

        val translucent = pdf(Scene(50f, 50f, listOf(image(0x80FF0000, 0xFF00FF00))))
        assertTrue(translucent.contains("/BitsPerComponent 8 /SMask 9 0 R /Length 6"))
        assertTrue(translucent.contains("/ColorSpace /DeviceGray /BitsPerComponent 8 /Length 2 >>\nstream\n\u0080\u00FF\n"))

        // Not a JPEG and no pixels (or wrong pixel count): grey placeholder box.
        for (img in listOf(SceneImage(0f, 0f, 10f, 10f, png), SceneImage(0f, 0f, 10f, 10f, png, IntArray(3), 2, 2))) {
            val s = pdf(Scene(50f, 50f, listOf(img)))
            assertFalse(s.contains("/XObject"))
            assertTrue(s.contains("0.933 0.933 0.933 rg\n0 0 10 10 re\nf"))
        }
    }

    @Test
    fun turnsJpegsByTheirExifOrientation() {
        fun cm(orientation: Int, littleEndian: Boolean): String {
            val jpeg = jpegHeader(components = 3, width = 2, height = 3, exifOrientation = orientation, littleEndian = littleEndian)
            val s = pdf(Scene(50f, 50f, listOf(SceneImage(5f, 5f, 30f, 20f, jpeg))))
            return s.substringBefore(" cm /Im1 Do Q").substringAfterLast("q ")
        }
        assertEquals("30 0 0 -20 5 25", cm(1, littleEndian = false)) // as stored
        assertEquals("0 20 30 0 5 5", cm(6, littleEndian = false)) // stored turned left: first row runs down the right edge
        assertEquals("-30 0 0 20 35 5", cm(3, littleEndian = true)) // upside down
        assertEquals("0 -20 -30 0 35 25", cm(8, littleEndian = true))
    }

    /** Just enough of a JPEG for the header parser: SOI, APP0, optional EXIF APP1 with an orientation, SOF0, EOI. */
    private fun jpegHeader(components: Int, width: Int, height: Int, exifOrientation: Int? = null, littleEndian: Boolean = false): ByteArray {
        val b = mutableListOf(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x04, 0x00, 0x00)
        if (exifOrientation != null) {
            fun u16(v: Int) = if (littleEndian) listOf(v and 0xFF, v shr 8) else listOf(v shr 8, v and 0xFF)
            fun u32(v: Int) = if (littleEndian) u16(v) + u16(0) else u16(0) + u16(v)
            b += listOf(0xFF, 0xE1, 0x00, 34, 0x45, 0x78, 0x69, 0x66, 0, 0) // APP1 "Exif\0\0"
            b += (if (littleEndian) listOf(0x49, 0x49) else listOf(0x4D, 0x4D)) + u16(42) + u32(8) // TIFF header, IFD0 at 8
            b += u16(1) + u16(0x0112) + u16(3) + u32(1) + u16(exifOrientation) + u16(0) + u32(0) // one SHORT entry
        }
        b += listOf(0xFF, 0xC0, 0x00, 8 + 3 * components, 8, height shr 8, height and 0xFF, width shr 8, width and 0xFF, components)
        repeat(components) { b += listOf(it + 1, 0x11, 0) }
        b += listOf(0xFF, 0xD9)
        return ByteArray(b.size) { b[it].toByte() }
    }
}
