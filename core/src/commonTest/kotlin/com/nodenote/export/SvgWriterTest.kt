package com.nodenote.export

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SvgWriterTest {

    private fun svg(vararg items: SceneItem, title: String = "Board") =
        SvgWriter.write(Scene(200f, 100f, items.toList(), title = title))

    @Test
    fun writesAStandaloneDocument() {
        val out = SvgWriter.write(Scene(640.5f, 480f, emptyList(), background = 0xFFFFFFFF, title = "Shop"))
        assertTrue(out.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"))
        assertTrue(
            out.contains(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" version=\"1.1\" " +
                    "width=\"640.5\" height=\"480\" viewBox=\"0 0 640.5 480\">",
            ),
        )
        assertTrue(out.contains("<title>Shop</title>"))
        assertTrue(out.contains("<rect width=\"640.5\" height=\"480\" fill=\"#ffffff\"/>"))
        assertTrue(out.endsWith("</svg>\n"))
        assertFalse(SvgWriter.write(Scene(10f, 10f, emptyList())).contains("<rect"))
    }

    @Test
    fun escapesTextAndTitle() {
        val out = svg(SceneText(10f, 20f, "a & b < c > d \"e\" 'f'", 12f, 0xFF000000), title = "R&D <board>")
        assertTrue(out.contains(">a &amp; b &lt; c &gt; d &quot;e&quot; &apos;f&apos;</text>"))
        assertTrue(out.contains("<title>R&amp;D &lt;board&gt;</title>"))
    }

    @Test
    fun dropsCharactersXmlForbids() {
        val out = svg(SceneText(0f, 10f, "a\u0001b\u0000c\uFFFEd\uD800e 😀", 12f, 0xFF000000))
        assertTrue(out.contains(">abcde 😀</text>"))
    }

    @Test
    fun writesTextAttributes() {
        val out = svg(SceneText(5f, 15.5f, "Hi", 14f, 0xFF336699, bold = true, letterSpacing = 1.25f, anchor = TextAnchor.Middle))
        assertTrue(
            out.contains(
                "<text x=\"5\" y=\"15.5\" font-family=\"Helvetica, Arial, sans-serif\" font-size=\"14\" font-weight=\"bold\" " +
                    "letter-spacing=\"1.25\" text-anchor=\"middle\" fill=\"#336699\" xml:space=\"preserve\">Hi</text>",
            ),
        )
    }

    @Test
    fun alphaBecomesOpacityAndTransparentPaintBecomesNone() {
        val rect = svg(SceneRect(1f, 2f, 30f, 40f, radius = 4f, fill = 0x80FF0000, stroke = 0x00000000, dash = listOf(4f, 2f)))
        assertTrue(rect.contains("<rect x=\"1\" y=\"2\" width=\"30\" height=\"40\" rx=\"4\" ry=\"4\" fill=\"#ff0000\" fill-opacity=\"0.502\"/>"))

        val line = svg(SceneLine(0f, 0f, 10f, 10f, 0x40000000, width = 2f, dash = listOf(3f, 1.5f)))
        assertTrue(
            line.contains(
                "<line x1=\"0\" y1=\"0\" x2=\"10\" y2=\"10\" stroke=\"#000000\" stroke-opacity=\"0.251\" stroke-width=\"2\" " +
                    "stroke-linecap=\"round\" stroke-dasharray=\"3 1.5\"/>",
            ),
        )

        val circle = svg(SceneCircle(5f, 5f, 3f, fill = 0x00FFFFFF, stroke = 0xFF00FF00))
        assertTrue(circle.contains("<circle cx=\"5\" cy=\"5\" r=\"3\" fill=\"none\" stroke=\"#00ff00\" stroke-width=\"1\"/>"))

        val polygon = svg(ScenePolygon(listOf(0f, 0f, 10f, 0f, 5f, 8.5f), 0xFF123456))
        assertTrue(polygon.contains("<polygon points=\"0,0 10,0 5,8.5\" fill=\"#123456\"/>"))

        // Nothing visible, nothing written.
        assertFalse(svg(SceneLine(0f, 0f, 1f, 1f, 0x00FF0000), SceneRect(0f, 0f, 5f, 5f)).contains("<line"))
    }

    @Test
    fun embedsImagesAsDataUris() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0)
        val out = svg(SceneImage(4f, 2f, 20f, 10f, png))
        val uri = "data:image/png;base64,iVBORw0KGgoAAA=="
        assertTrue(out.contains("<image x=\"4\" y=\"2\" width=\"20\" height=\"10\" preserveAspectRatio=\"none\" href=\"$uri\" xlink:href=\"$uri\"/>"))

        val gif = "GIF89a".encodeToByteArray()
        assertTrue(svg(SceneImage(0f, 0f, 1f, 1f, gif)).contains("data:image/gif;base64,"))
        val webp = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".encodeToByteArray()
        assertTrue(svg(SceneImage(0f, 0f, 1f, 1f, webp)).contains("data:image/webp;base64,"))

        // Not an image format: a placeholder box instead.
        val unknown = svg(SceneImage(0f, 0f, 20f, 10f, byteArrayOf(1, 2, 3)))
        assertFalse(unknown.contains("<image"))
        assertTrue(unknown.contains("<rect x=\"0\" y=\"0\" width=\"20\" height=\"10\" fill=\"#eeeeee\" stroke=\"#bbbbbb\""))
    }
}
