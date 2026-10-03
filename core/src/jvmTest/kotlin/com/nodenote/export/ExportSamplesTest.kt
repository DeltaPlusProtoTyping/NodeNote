package com.nodenote.export

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Writes the demo scene to core/build/export-samples/ so a human can open it in a browser and PDF viewers. */
class ExportSamplesTest {

    @Test
    fun writesSampleSvgAndPdf() {
        val scene = DemoScene.build()
        val dir = File("build/export-samples").apply { mkdirs() }
        val svg = File(dir, "sample.svg").apply { writeText(SvgWriter.write(scene), Charsets.UTF_8) }
        val pdf = File(dir, "sample.pdf").apply { writeBytes(PdfWriter.write(scene)) }
        println("Export samples: ${svg.absolutePath}, ${pdf.absolutePath}")
        assertTrue(svg.length() > 0 && pdf.length() > 0)
    }
}
