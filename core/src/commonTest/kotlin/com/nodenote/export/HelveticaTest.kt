package com.nodenote.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HelveticaTest {

    @Test
    fun helloMatchesTheAfmWidths() {
        // H 722 + e 556 + l 222 + l 222 + o 556 = 2278 units
        assertEquals(22.78f, Helvetica.width("Hello", 10f), 0.0001f)
        assertEquals(0f, Helvetica.width("", 10f))
    }

    @Test
    fun boldHasItsOwnWidths() {
        // H 722 + e 556 + l 278 + l 278 + o 611 = 2445 units
        assertEquals(24.45f, Helvetica.width("Hello", 10f, bold = true), 0.0001f)
        assertNotEquals(Helvetica.width("Hello", 10f), Helvetica.width("Hello", 10f, bold = true))
    }

    @Test
    fun letterSpacingIsAddedAfterEveryCharacter() {
        assertEquals(22.78f + 5 * 1.5f, Helvetica.width("Hello", 10f, letterSpacing = 1.5f), 0.0001f)
    }

    @Test
    fun winAnsiExtrasAndUnknownCharacters() {
        assertEquals(5.56f, Helvetica.width("é", 10f), 0.0001f)
        assertEquals(10f, Helvetica.width("—", 10f), 0.0001f)
        assertEquals(2.22f, Helvetica.width("’", 10f), 0.0001f)
        assertEquals(10f, Helvetica.width("…", 10f), 0.0001f)
        assertEquals(3.5f, Helvetica.width("•", 10f), 0.0001f)
        assertEquals(5.56f, Helvetica.width("€", 10f), 0.0001f)
        assertEquals(1.91f, Helvetica.width("'", 10f), 0.0001f) // quotesingle, not quoteright
        assertEquals(3.33f, Helvetica.width("`", 10f), 0.0001f) // grave, not quoteleft
        assertEquals(5.56f, Helvetica.width("漢", 10f), 0.0001f) // unknown
        assertEquals(5.56f, Helvetica.width("😀", 10f), 0.0001f) // an emoji is one character
        assertEquals(Helvetica.width("ab", 10f), Helvetica.width("a\u200Bb\u00AD", 10f)) // invisible characters
    }

    private val text = "The quick brown fox jumps over the lazy dog while the shop floor keeps humming along"

    @Test
    fun wrapKeepsEveryLineWithinTheWidth() {
        val lines = Helvetica.wrap(text, 100f, 12f)
        assertTrue(lines.size > 2)
        for (line in lines) assertTrue(Helvetica.width(line, 12f) <= 100f, "'$line' is too wide")
        assertEquals(text, lines.joinToString(" "))
    }

    @Test
    fun wrapFillsEachLineGreedily() {
        val lines = Helvetica.wrap(text, 100f, 12f, bold = true)
        for (i in 0 until lines.size - 1) {
            val nextWord = lines[i + 1].substringBefore(' ')
            assertTrue(Helvetica.width("${lines[i]} $nextWord", 12f, bold = true) > 100f, "'$nextWord' fits after '${lines[i]}'")
        }
    }

    @Test
    fun wrapHonorsNewlinesAndCollapsesSpaces() {
        assertEquals(listOf("one", "two three"), Helvetica.wrap("one\ntwo   three", 1000f, 12f))
        assertEquals(listOf("a", "", "b"), Helvetica.wrap("a\r\n\r\nb", 1000f, 12f))
        assertEquals(listOf("a b"), Helvetica.wrap("\n  a \t  b  \n", 1000f, 12f))
    }

    @Test
    fun wrapBreaksWordsWiderThanALine() {
        val word = "Supercalifragilisticexpialidocious"
        val lines = Helvetica.wrap("x $word y", 40f, 12f)
        assertTrue(lines.size > 3)
        for (line in lines) assertTrue(Helvetica.width(line, 12f) <= 40f, "'$line' is too wide")
        assertEquals("x${word}y", lines.joinToString("").replace(" ", ""))
    }

    @Test
    fun wrapEndsWithAnEllipsisWhenCutAtMaxLines() {
        val all = Helvetica.wrap(text, 100f, 12f)
        val cut = Helvetica.wrap(text, 100f, 12f, maxLines = 2)
        assertEquals(2, cut.size)
        assertEquals(all[0], cut[0])
        assertTrue(cut[1].endsWith("…"))
        assertTrue(Helvetica.width(cut[1], 12f) <= 100f)
        // Nothing cut, no ellipsis.
        assertEquals(listOf("short"), Helvetica.wrap("short", 100f, 12f, maxLines = 1))
        assertEquals(all, Helvetica.wrap(text, 100f, 12f, maxLines = all.size))
    }

    @Test
    fun wrapReturnsOneEmptyLineForBlankInput() {
        assertEquals(listOf(""), Helvetica.wrap("", 100f, 12f))
        assertEquals(listOf(""), Helvetica.wrap("  \n \n", 100f, 12f))
    }
}
