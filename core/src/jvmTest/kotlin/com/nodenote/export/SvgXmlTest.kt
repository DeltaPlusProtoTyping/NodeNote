package com.nodenote.export

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SvgXmlTest {
    private val svgNs = "http://www.w3.org/2000/svg"
    private val xlinkNs = "http://www.w3.org/1999/xlink"
    private val xmlNs = "http://www.w3.org/XML/1998/namespace"

    private fun parse(svg: String): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(InputSource(StringReader(svg)))

    private fun Document.elements(tag: String): List<Element> {
        val nodes = getElementsByTagNameNS(svgNs, tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test
    fun everyItemTypeParsesAsXml() {
        val doc = parse(SvgWriter.write(DemoScene.build()))
        val root = doc.documentElement
        assertEquals("svg", root.localName)
        assertEquals(svgNs, root.namespaceURI)
        assertEquals("0 0 960 600", root.getAttribute("viewBox"))
        assertEquals("NodeNote export sample — Shop board", doc.elements("title").single().textContent)
        for (tag in listOf("rect", "circle", "line", "polygon", "text", "image")) {
            assertTrue(doc.elements(tag).isNotEmpty(), "no <$tag>")
        }

        val images = doc.elements("image")
        assertEquals(4, images.size)
        for (image in images) {
            assertEquals(image.getAttribute("href"), image.getAttributeNS(xlinkNs, "href"))
            assertEquals("none", image.getAttribute("preserveAspectRatio"))
        }
        assertEquals(
            listOf("image/jpeg", "image/png", "image/jpeg", "image/png"),
            images.map { it.getAttribute("href").removePrefix("data:").substringBefore(';') },
        )

        val texts = doc.elements("text")
        for (text in texts) assertEquals("preserve", text.getAttributeNS(xmlNs, "space"))
        assertTrue(texts.any { it.textContent == "Not in WinAnsi (PDF shows ?): 漢字 😀" })
        assertTrue(texts.any { it.getAttribute("font-weight") == "bold" && it.getAttribute("letter-spacing") == "1.5" })
    }

    @Test
    fun hostileTextStaysText() {
        val evil = "</text><script>alert(1)</script> & \"' \u0000\u0008\u001F\uFFFF\uD800 ok"
        val doc = parse(SvgWriter.write(Scene(100f, 100f, listOf(SceneText(1f, 2f, evil, 10f, 0xFF000000)), title = evil)))
        assertTrue(doc.elements("script").isEmpty())
        val expected = "</text><script>alert(1)</script> & \"'  ok"
        assertEquals(expected, doc.elements("text").single().textContent)
        assertEquals(expected, doc.elements("title").single().textContent)
    }
}
