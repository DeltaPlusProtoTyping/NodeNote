package com.nodenote.spell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpellCheckerTest {

    private val words = """
        about and ask battery better betters buttery conversion don't employee employee's employees have it it's jobs
        long mail maintenance make making manual metrics motivate motivated occur occurred quality quantity require
        reviews see takes tech ten that that's the too work
    """.split(' ', '\n').filter { it.isNotBlank() }.sorted().joinToString("\n")

    private val checker = SpellChecker(words)

    @Test
    fun flagsOnlyUnknownWords() {
        assertEquals(listOf(Misspelling(5, 12, "bettery")), checker.misspellings("Have bettery employee metrics / reviews"))
        // Compounds are checked part by part, with offsets into the original text.
        assertEquals(
            listOf(Misspelling(0, 5, "Wrokd"), Misspelling(14, 21, "reviwes"), Misspelling(25, 29, "wrok")),
            checker.misspellings("Wrokd metrics/reviwes re-wrok"),
        )
    }

    @Test
    fun skipsWhatIsntProse() {
        for (
            text in listOf(
                "LTL jobs require manual conversion to mcMaster PN",
                "QC ERP eBay ProShop McMaster",
                "M8x1.25 3rd 6061-T6 2x4 qty12",
                "ask @Rivva about [[Quoting Takes too long]] and [[Scrap|scarp rate]]",
                "see https://exmaple.com/qoute or www.exmaple.com, mail kurt@exmaple.com or jsmith.ext@exmaple.com",
                "ok so xq zz",
                "[[Unfinished linkk",
            )
        ) {
            assertEquals(emptyList(), checker.misspellings(text), text)
        }
    }

    @Test
    fun apostrophesAndPossessives() {
        for (text in listOf("don't", "it's", "employees'", "don’t", "the employee's jobs", "Employees’ jobs")) {
            assertEquals(emptyList(), checker.misspellings(text), text)
        }
        // Only the word is flagged, so replacing it keeps the possessive.
        assertEquals(listOf(Misspelling(0, 4, "Jhon")), checker.misspellings("Jhon's jobs"))
        assertEquals(listOf(Misspelling(4, 8, "jobz")), checker.misspellings("the jobz’ metrics"))
    }

    @Test
    fun extraWordsAreCaseInsensitiveAndShareTheList() {
        assertEquals(listOf("Rivva", "needs", "deburring"), checker.misspellings("Rivva needs deburring").map { it.word })
        val more = checker.withExtraWords(listOf("Rivva", "deburr", "Kurt Smith", "needs"))
        assertEquals(checker.size, more.size)
        assertEquals(emptyList(), more.misspellings("Rivva needs deburring; rivva and Smith deburred the jobs"))
        assertTrue(more.isKnown("DEBURR"))
        assertFalse(checker.isKnown("deburr"))
        assertTrue(SpellChecker(words, listOf("rivva")).isKnown("Rivva's"))
    }

    @Test
    fun inflectionsOnlyFillGaps() {
        val c = SpellChecker(words, listOf("deburr"))
        for (w in listOf("deburrs", "deburred", "deburring", "makes", "works", "worked", "occurring")) assertTrue(c.isKnown(w), w)
        // The list spells these inflections differently, so they're the classic slips.
        for (w in listOf("occured", "makeing", "occurrs", "thats")) assertFalse(c.isKnown(w), w)
        // Short words are fully inflected in the list: "thes" isn't the + s.
        assertFalse(c.isKnown("thes"))
    }

    @Test
    fun suggestionsFollowTypingSlips() {
        assertEquals("quantity", checker.suggest("quanity").first()) // dropped letter beats "quality" (wrong key)
        assertEquals(listOf("better", "battery"), checker.suggest("bettery", limit = 2)) // extra letter beats a vowel swap
        assertEquals("the", checker.suggest("teh").first()) // swap beats "ten" (h and n are neighbors)
        assertEquals("work", checker.suggest("wprk").first()) // neighboring key
        assertEquals("occurred", checker.suggest("occured").first()) // undoubled letter
        assertEquals("don't", checker.suggest("dont").first()) // missing apostrophe
        assertEquals("maintenance", checker.suggest("maintence").first())
        assertTrue("better" !in checker.suggest("better"))
        assertEquals(emptyList(), checker.suggest("zzzzzzzz"))
        assertEquals(1, checker.suggest("bettery", limit = 1).size)
    }

    @Test
    fun suggestionsMatchCapitalization() {
        assertEquals("Maintenance", checker.suggest("Maintence").first())
        assertEquals("QUANTITY", checker.suggest("QUANITY").first())
        assertEquals("Don't", checker.suggest("Dont").first())
        assertEquals("don’t", checker.suggest("do’nt").first())
        assertEquals("McMaster", SpellChecker(words, listOf("McMaster")).suggest("mcmastr").first())
    }

    @Test
    fun acceptsMessyWordLists() {
        val c = SpellChecker("Zebra\r\napple\r\n\r\n  Mango \napple\n")
        assertEquals(3, c.size)
        for (w in listOf("apple", "Zebra", "mango")) assertTrue(c.isKnown(w), w)
        assertFalse(c.isKnown("kiwi"))
        assertEquals(2, SpellChecker("apple\r\nmango\r\n").size)
        assertEquals(0, SpellChecker("").size)
        assertFalse(SpellChecker("").isKnown("apple"))
    }
}
