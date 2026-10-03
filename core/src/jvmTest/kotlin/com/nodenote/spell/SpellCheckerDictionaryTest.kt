package com.nodenote.spell

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The checker against the real word list (server/src/main/resources/dict/en_US.txt). */
class SpellCheckerDictionaryTest {

    companion object {
        private val listText = listOf("../server", "server")
            .map { File(it, "src/main/resources/dict/en_US.txt") }
            .first { it.exists() }
            .readText()

        // Timed here, before any test, so these are the cold (un-JIT-ed) first runs.
        private val loadMs: Double
        private val checker: SpellChecker
        private val firstSuggestMs: Double

        init {
            var t = System.nanoTime()
            checker = SpellChecker(listText)
            loadMs = (System.nanoTime() - t) / 1e6
            t = System.nanoTime()
            checker.suggest("Inneficiencies")
            firstSuggestMs = (System.nanoTime() - t) / 1e6
        }

        private fun millis(block: () -> Unit): Double {
            val t = System.nanoTime()
            block()
            return (System.nanoTime() - t) / 1e6
        }

        private fun Double.ms() = "%.1f ms".format(this)
    }

    @Test
    fun loadsFast() {
        val again = (1..5).map { millis { SpellChecker(listText) } }
        println("load ${checker.size} words: first ${loadMs.ms()}, then ${again.joinToString { it.ms() }}")
        assertTrue(checker.size > 100_000)
        assertTrue(loadMs < 150, "first load took ${loadMs.ms()}")
        assertTrue(again.max() < 150)
    }

    @Test
    fun suggestsFast() {
        val word = "Inneficiencies" // 14 letters
        val again = (1..10).map { millis { checker.suggest(word) } }
        println("suggest(\"$word\"): first ${firstSuggestMs.ms()}, then ${again.joinToString { it.ms() }}")
        for (short in listOf("teh", "dont", "quanity")) println("suggest(\"$short\"): ${millis { checker.suggest(short) }.ms()}")
        assertTrue(firstSuggestMs < 150, "first suggest took ${firstSuggestMs.ms()}")
        assertTrue(again.max() < 150)
    }

    @Test
    fun suggestsTheIntendedWord() {
        val expected = mapOf(
            "Inneficiencies" to "Inefficiencies",
            "motiviated" to "motivated",
            "quanity" to "quantity",
            "oblious" to "obvious",
            "Maintence" to "Maintenance",
            "effeciency" to "efficiency",
            "QUANITY" to "QUANTITY",
        )
        for (typo in expected.keys + listOf("bettery", "teh", "custmer", "fixure", "scedule", "occured", "dont")) {
            println("$typo -> ${checker.suggest(typo)}")
        }
        for ((typo, fix) in expected) assertEquals(fix, checker.suggest(typo).firstOrNull(), "suggest(\"$typo\")")
        assertTrue("better" in checker.suggest("bettery").take(2), "suggest(\"bettery\") = ${checker.suggest("bettery")}")
    }

    @Test
    fun flagsOnlyRealMisspellings() {
        assertEquals(listOf("bettery"), checker.misspellings("Have bettery employee metrics / reviews").map { it.word })
        for (
            text in listOf(
                "LTL jobs require manual conversion to mcMaster PN",
                "Shit Takes Too Long To Make",
                "ask @Rivva about [[Quoting Takes too long]]",
                "don't", "it's", "employees'", "don’t",
            )
        ) {
            assertEquals(emptyList(), checker.misspellings(text), text)
        }
        val typos = "Inneficiencies / motiviated / quanity / oblious / Maintence / effeciency"
        assertEquals(6, checker.misspellings(typos).size)
    }

    @Test
    fun noSuggestWordsAreAcceptedButNeverSuggested() {
        val flagged = listText.split('\n').filter { fnv1a(it) in NoSuggestHashes }
        // Every hash matches a listed word, so the Kotlin and Python hashes agree.
        assertEquals(NoSuggestHashes.size, flagged.map(::fnv1a).toSet().size)
        for (word in flagged) {
            assertTrue(checker.isKnown(word))
            val near = checker.suggest(word + word.last(), limit = 10) // a doubled last letter would rank it first
            assertTrue(near.none { fnv1a(it) in NoSuggestHashes }, "a NOSUGGEST word was suggested")
        }
    }
}
