package com.nodenote.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PdfStructureTest {

    /** The file as one char per byte, so string offsets are byte offsets. */
    private fun pdf(scene: Scene) = String(PdfWriter.write(scene), Charsets.ISO_8859_1)

    @Test
    fun headerAndTrailer() {
        val pdf = pdf(DemoScene.build())
        assertTrue(pdf.startsWith("%PDF-1.4\n%"))
        assertTrue(pdf.substring(10, 14).all { it.code >= 0x80 }, "binary comment after the header")
        assertTrue(pdf.endsWith("\n%%EOF\n"))
    }

    @Test
    fun everyXrefOffsetPointsAtItsObject() {
        val pdf = pdf(DemoScene.build())
        val startxref = assertNotNull(Regex("startxref\n(\\d+)\n%%EOF\n$").find(pdf)).groupValues[1].toInt()
        assertTrue(pdf.startsWith("xref\n0 ", startxref))
        val countEnd = pdf.indexOf('\n', startxref + 7)
        val count = pdf.substring(startxref + 7, countEnd).toInt()
        assertTrue(count > 8, "catalog, pages, page, content, 2 fonts, info + images")

        var entry = countEnd + 1
        assertEquals("0000000000 65535 f\r\n", pdf.substring(entry, entry + 20))
        for (n in 1 until count) {
            entry += 20
            val line = pdf.substring(entry, entry + 20)
            assertTrue(Regex("\\d{10} 00000 n\r\n").matches(line), "xref entry $n: '$line'")
            val offset = line.take(10).toInt()
            assertTrue(pdf.startsWith("$n 0 obj\n", offset), "xref entry $n points at '${pdf.substring(offset, offset + 12)}'")
        }
        assertTrue(pdf.startsWith("trailer\n<< /Size $count /Root 1 0 R /Info 7 0 R >>\nstartxref\n", entry + 20))
    }

    @Test
    fun streamLengthsAreExact() {
        val pdf = pdf(DemoScene.build())
        val streams = Regex("/Length (\\d+) >>\nstream\n").findAll(pdf).toList()
        assertEquals(5, streams.size, "content, color JPEG, avatar RGB + soft mask, gray JPEG")
        for (m in streams) {
            val end = m.range.last + 1 + m.groupValues[1].toInt()
            assertTrue(pdf.startsWith("\nendstream\nendobj\n", end), "stream at ${m.range.first}")
        }
    }

    @Test
    fun mediaBoxMatchesTheScene() {
        val pdf = pdf(Scene(612.5f, 792f, emptyList()))
        assertTrue(pdf.contains("/MediaBox [0 0 612.5 792]"))
        assertTrue(pdf.contains("\n1 0 0 -1 0 792 cm\n"))
        assertTrue(pdf(DemoScene.build()).contains("/MediaBox [0 0 960 600]"))
    }

    @Test
    fun scenesBeyondAcrobatsPageLimitAreScaledToFit() {
        val pdf = pdf(Scene(30000f, 12000f, listOf(SceneRect(0f, 0f, 30000f, 12000f, fill = 0xFF000000))))
        val box = assertNotNull(Regex("/MediaBox \\[0 0 ([\\d.]+) ([\\d.]+)]").find(pdf)).groupValues
        assertEquals(14400f, box[1].toFloat(), 0.01f)
        assertEquals(5760f, box[2].toFloat(), 0.01f)
        assertTrue(pdf.contains("\n0.48 0 0 -0.48 0 5760 cm\n"))
        // Content stays in scene units under that matrix.
        assertTrue(pdf.contains("\n0 0 30000 12000 re\nf\n"))

        val tall = pdf(Scene(500f, 20000f, emptyList()))
        assertTrue(tall.contains("/MediaBox [0 0 360 14400]"))
    }
}
