package com.nodenote.spell

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Suggestions are at most this many edits (Damerau–Levenshtein) away. */
private const val MAX_EDITS = 2

/** A word that isn't in the dictionary: [start] until [end] (exclusive) in the checked text. */
data class Misspelling(val start: Int, val end: Int, val word: String)

/**
 * Offline English spell checker over a sorted word list.
 * [wordList] is the content of dict/en_US.txt (one lowercase word per line, sorted). [extraWords] are accepted too
 * (people's names, the user's own "add to dictionary" words, board jargon) — compared case-insensitively.
 *
 * The list is kept as the one big String plus an IntArray of line offsets and searched by binary search, so it
 * costs about its own size in memory (no 120k-String HashSet) — it runs in phone browsers too.
 */
class SpellChecker private constructor(private val dict: WordList, private val extra: Map<String, String>) {

    constructor(wordList: String, extraWords: Collection<String> = emptyList()) :
        this(WordList.parse(wordList), withWords(emptyMap(), extraWords))

    /** Number of dictionary words (extra words not included). */
    val size: Int get() = dict.size

    /**
     * Whether [word] is listed or an extra word (any case, ’ = '), ignoring a possessive 's or trailing '.
     * Also accepts a regular inflection (-s, -es, -ed, -ing, -er, -est, -ly, -ness, with e-restoration, consonant
     * doubling and y→i) of a known word — unless the list spells that inflection differently, so "deburred" passes
     * once "deburr" is an extra word but "occured" ("occurred"), "makeing" ("making") and "thats" ("that's") don't.
     * Applies none of [misspellings]' skip rules.
     */
    fun isKnown(word: String): Boolean {
        val w = normalize(word.trim()).trimEnd('\'')
        if (w.isEmpty()) return false
        if (has(w) || isRegularInflection(w)) return true
        if (!w.endsWith("'s")) return false
        val base = w.dropLast(2)
        return has(base) || isRegularInflection(base)
    }

    /**
     * Misspelled words in [text], in order.
     *
     * Words are letters with inner apostrophes ("don't"); hyphen/slash compounds are checked part by part. Skipped:
     * words with digits, words of 1–2 letters, ALL-CAPS words (LTL), words with a capital after the first letter
     * (McMaster, eBay), URLs (http://, https://, www.), @mentions, `[[node links]]` and email addresses.
     * For "Jhon's" only "Jhon" is reported, so a suggestion can replace it and keep the 's.
     */
    fun misspellings(text: String): List<Misspelling> {
        val out = ArrayList<Misspelling>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            i = when {
                c == '[' && text.startsWith("[[", i) -> linkEnd(text, i)
                isUrlStart(text, i) -> chunkEnd(text, i)
                c == '@' -> {
                    // An @mention or email address: skip it, including what was flagged before the @ (jsmith.ext@…).
                    val chunkStart = chunkStart(text, i)
                    while (out.isNotEmpty() && out.last().start >= chunkStart) out.removeAt(out.lastIndex)
                    chunkEnd(text, i)
                }
                c.isLetterOrDigit() -> {
                    val end = wordEnd(text, i)
                    misspelling(text, i, end)?.let(out::add)
                    end
                }
                else -> i + 1
            }
        }
        return out
    }

    /**
     * Best corrections for [word], best first, matching the word's capitalization (Title / UPPER / lower); extra
     * words with capitals of their own keep them (McMaster).
     *
     * Candidates are listed and extra words within Damerau–Levenshtein distance 2, ranked by how likely a typing
     * slip each one is: a doubled/undoubled letter or a missing/extra apostrophe 0.5, two swapped letters 0.7, a
     * neighboring QWERTY key 0.75, a dropped letter 0.85, an extra letter 0.9, any other letter 1.0. Ties go to the
     * closer length, then the same first letter, then the shorter word, then alphabetical. Words the dictionary
     * flags NOSUGGEST (slurs, profanity) are accepted but never suggested.
     */
    fun suggest(word: String, limit: Int = 3): List<String> {
        val typed = normalize(word.trim())
        if (limit <= 0 || typed.isEmpty()) return emptyList()
        val found = ArrayList<Candidate>()
        val osa = BoundedOsa(typed.length + MAX_EDITS)
        for (i in 0 until dict.size) {
            val length = dict.length(i)
            if (abs(length - typed.length) > MAX_EDITS) continue
            if (osa.distance(typed, dict.text, dict.start(i), length) > MAX_EDITS) continue
            val candidate = dict.word(i)
            if (candidate != typed && fnv1a(candidate) !in NoSuggestHashes) {
                found += Candidate(candidate, candidate, typingCost(typed, candidate))
            }
        }
        for ((key, spelling) in extra) {
            if (key == typed || abs(key.length - typed.length) > MAX_EDITS) continue
            if (osa.distance(typed, key, 0, key.length) <= MAX_EDITS) found += Candidate(key, spelling, typingCost(typed, key))
        }
        found.sortWith(
            compareBy(
                { it.cost },
                { abs(it.key.length - typed.length) },
                { if (it.key[0] == typed[0]) 0 else 1 },
                { it.key.length },
                { it.key },
            ),
        )
        return found.distinctBy { it.key }.take(limit).map { matchCase(it.spelling, word) }
    }

    /** A copy that also accepts [words]. Cheap: it shares this checker's parsed list. */
    fun withExtraWords(words: Collection<String>): SpellChecker = SpellChecker(dict, withWords(extra, words))

    private class Candidate(val key: String, val spelling: String, val cost: Int)

    private fun has(w: String) = w in dict || w in extra

    /** The reportable misspelling for the word at text[start, end), or null when it's skipped or known. */
    private fun misspelling(text: String, start: Int, end: Int): Misspelling? {
        val stemEnd = if (end - start > 2 && isApostrophe(text[end - 2]) && text[end - 1].lowercaseChar() == 's') end - 2 else end
        var letters = 0
        for (k in start until stemEnd) {
            val c = text[k]
            when {
                isApostrophe(c) -> {}
                c.isDigit() -> return null // 3rd, M8, 6061
                c.isUpperCase() -> if (letters++ > 0) return null // LTL, McMaster, eBay
                c.isLowerCase() -> letters++
                else -> return null // letters without case (not English)
            }
        }
        if (letters < 3 || isKnown(text.substring(start, end))) return null
        return Misspelling(start, stemEnd, text.substring(start, stemEnd))
    }

    /**
     * Whether [w] is a regular inflection of a known word that the list doesn't spell some other way. That other
     * spelling is the tell for the classic slips: "occured" ("occurred"), "begining", "makeing", "stoped".
     */
    private fun isRegularInflection(w: String): Boolean = stems(w).any { (stem, suffix) ->
        has(stem) && inflections(stem, suffix).none { it != w && has(it) }
    }

    companion object {
        // Typing-slip costs in hundredths.
        private const val DOUBLED = 50 // "occured", "untill"
        private const val APOSTROPHE = 50 // "dont"
        private const val TRANSPOSED = 70 // "teh"
        private const val NEIGHBOR_KEY = 75 // "wprk"
        private const val MISSING_LETTER = 85 // "quanity"
        private const val EXTRA_LETTER = 90 // "bettery"
        private const val OTHER_LETTER = 100 // "effeciency"

        private val KEY_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

        private fun normalize(word: String) = word.lowercase().replace('’', '\'')

        private fun isApostrophe(c: Char) = c == '\'' || c == '’'

        /** [current] plus the words in [words] (an entry may hold several, e.g. "Kurt Smith"): normalized → as written. */
        private fun withWords(current: Map<String, String>, words: Collection<String>): Map<String, String> {
            if (words.isEmpty()) return current
            val out = current.toMutableMap()
            for (entry in words) {
                var i = 0
                while (i < entry.length) {
                    if (!entry[i].isLetterOrDigit()) {
                        i++
                        continue
                    }
                    val end = wordEnd(entry, i)
                    val token = entry.substring(i, end)
                    if (token.any { it.isLetter() }) out.getOrPut(normalize(token)) { token }
                    i = end
                }
            }
            return out
        }

        /** End of the word starting at [start]: letters and digits with inner apostrophes ("don't", not "dogs'"). */
        private fun wordEnd(s: String, start: Int): Int {
            var k = start
            while (k < s.length && (s[k].isLetterOrDigit() || (isApostrophe(s[k]) && k + 1 < s.length && s[k + 1].isLetterOrDigit()))) k++
            return k
        }

        /** Start and end of the whitespace-delimited chunk around [i]. */
        private fun chunkStart(s: String, i: Int): Int {
            var k = i
            while (k > 0 && !s[k - 1].isWhitespace()) k--
            return k
        }

        private fun chunkEnd(s: String, i: Int): Int {
            var k = i
            while (k < s.length && !s[k].isWhitespace()) k++
            return k
        }

        /** Just past the "]]" closing the link that starts at [i], or the end of the line while it's still being typed. */
        private fun linkEnd(s: String, i: Int): Int {
            var k = i + 2
            while (k < s.length && s[k] != '\n') {
                if (s[k] == ']' && k + 1 < s.length && s[k + 1] == ']') return k + 2
                k++
            }
            return k
        }

        private fun isUrlStart(s: String, i: Int) =
            s.startsWith("http://", i, ignoreCase = true) || s.startsWith("https://", i, ignoreCase = true) ||
                s.startsWith("www.", i, ignoreCase = true)

        /**
         * (stem, suffix) pairs regular spelling could have turned into [w]: "stopped" → ("stop", "ed"), … Stems are 4+
         * letters: shorter words are fully inflected in the list, and "thes" or "wholy" shouldn't pass as the+s, who+ly.
         */
        private fun stems(w: String): List<Pair<String, String>> {
            val out = ArrayList<Pair<String, String>>()
            fun add(stem: String, suffix: String) {
                if (stem.length >= 4) out += stem to suffix
            }
            if (w.endsWith("s")) add(w.dropLast(1), "s")
            if (w.endsWith("es")) add(w.dropLast(2), "s")
            if (w.endsWith("ies")) add(w.dropLast(3) + "y", "s")
            for (suffix in listOf("ed", "ing", "er", "est")) {
                if (!w.endsWith(suffix)) continue
                val base = w.dropLast(suffix.length)
                add(base, suffix) // walk·ed
                add(base + "e", suffix) // bak·ed
                if (base.length > 1 && base.last() == base[base.length - 2] && base.last() !in "aeiou") {
                    add(base.dropLast(1), suffix) // stopp·ed
                }
                if (suffix != "ing" && base.endsWith("i")) add(base.dropLast(1) + "y", suffix) // copi·ed
            }
            if (w.endsWith("ly")) {
                val base = w.dropLast(2)
                add(base, "ly") // quick·ly
                add(base + "e", "ly") // tru·ly
                add(w.dropLast(1) + "e", "ly") // simpl·y
                if (base.endsWith("i")) add(base.dropLast(1) + "y", "ly") // happi·ly
                if (base.endsWith("al")) add(base.dropLast(2), "ly") // basic·ally
            }
            if (w.endsWith("ness")) {
                val base = w.dropLast(4)
                add(base, "ness")
                if (base.endsWith("i")) add(base.dropLast(1) + "y", "ness") // happi·ness
            }
            return out
        }

        /** Every regular spelling of [stem] + [suffix] (the forms [stems] reads back); for -s also 's ("thats"). */
        private fun inflections(stem: String, suffix: String): List<String> {
            val y = if (stem.endsWith("y")) stem.dropLast(1) + "i" else null
            val e = if (stem.endsWith("e")) stem.dropLast(1) else null
            return when (suffix) {
                "s" -> listOfNotNull(stem + "s", stem + "es", y?.plus("es"), "$stem's")
                "ly" -> listOfNotNull(stem + "ly", e?.plus("ly"), y?.plus("ly"), stem + "ally", if (stem.endsWith("le")) stem.dropLast(1) + "y" else null)
                "ness" -> listOfNotNull(stem + "ness", y?.plus("ness"))
                else -> listOfNotNull(stem + suffix, e?.plus(suffix), stem + stem.last() + suffix, if (suffix != "ing") y?.plus(suffix) else null)
            }
        }

        /** [suggestion] in the capitalization of [typed]. */
        private fun matchCase(suggestion: String, typed: String): String {
            val letters = typed.filter { it.isLetter() }
            val cased = when {
                letters.length >= 2 && letters.all { it.isUpperCase() } -> suggestion.uppercase()
                suggestion.any { it.isUpperCase() } -> suggestion // an extra word with its own capitals
                typed.trim().firstOrNull()?.isUpperCase() == true -> suggestion.replaceFirstChar { it.uppercaseChar() }
                else -> suggestion
            }
            return if ('’' in typed) cased.replace('\'', '’') else cased
        }

        /** Weighted OSA cost, in hundredths, of [b] having been typed as [a]: common slips cost least. */
        private fun typingCost(a: String, b: String): Int {
            val w = b.length + 1
            val d = IntArray((a.length + 1) * w)
            for (i in 0..a.length) {
                for (j in 0..b.length) {
                    if (i == 0 && j == 0) continue
                    var v = Int.MAX_VALUE
                    if (i > 0) v = min(v, d[(i - 1) * w + j] + extraCost(a, i - 1, b, j))
                    if (j > 0) v = min(v, d[i * w + j - 1] + missingCost(a, i, b, j - 1))
                    if (i > 0 && j > 0) v = min(v, d[(i - 1) * w + j - 1] + keyCost(a[i - 1], b[j - 1]))
                    if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1] && a[i - 1] != a[i - 2]) {
                        v = min(v, d[(i - 2) * w + j - 2] + TRANSPOSED)
                    }
                    d[i * w + j] = v
                }
            }
            return d[a.length * w + b.length]
        }

        /** Cost of the typed char `a[x]` being extra, where it sits between `b[j - 1]` and `b[j]`. */
        private fun extraCost(a: String, x: Int, b: String, j: Int): Int {
            val c = a[x]
            return when {
                c == '\'' -> APOSTROPHE
                (x > 0 && a[x - 1] == c && j > 0 && b[j - 1] == c) ||
                    (x + 1 < a.length && a[x + 1] == c && j < b.length && b[j] == c) -> DOUBLED
                else -> EXTRA_LETTER
            }
        }

        /** Cost of `b[y]` missing from the typed word, where it belongs between `a[i - 1]` and `a[i]`. */
        private fun missingCost(a: String, i: Int, b: String, y: Int): Int {
            val c = b[y]
            return when {
                c == '\'' -> APOSTROPHE
                (y > 0 && b[y - 1] == c && i > 0 && a[i - 1] == c) ||
                    (y + 1 < b.length && b[y + 1] == c && i < a.length && a[i] == c) -> DOUBLED
                else -> MISSING_LETTER
            }
        }

        private fun keyCost(typed: Char, meant: Char) = when {
            typed == meant -> 0
            neighborKeys(typed, meant) -> NEIGHBOR_KEY
            else -> OTHER_LETTER
        }

        /** Whether two letters are neighboring QWERTY keys (each row sits about half a key right of the one above). */
        private fun neighborKeys(a: Char, b: Char): Boolean {
            val ra = KEY_ROWS.indexOfFirst { a in it }
            val rb = KEY_ROWS.indexOfFirst { b in it }
            if (ra < 0 || rb < 0) return false
            val ca = KEY_ROWS[ra].indexOf(a)
            val cb = KEY_ROWS[rb].indexOf(b)
            return when (rb - ra) {
                0 -> abs(ca - cb) == 1
                1 -> cb == ca || cb == ca - 1
                -1 -> ca == cb || ca == cb - 1
                else -> false
            }
        }
    }
}

/** 32-bit FNV-1a over UTF-16 code units; must match fnv1a() in tools/build_dictionary.py ([NoSuggestHashes]). */
internal fun fnv1a(word: String): Int {
    var h = 0x811C9DC5.toInt()
    for (c in word) h = (h xor c.code) * 0x01000193
    return h
}

/** The sorted list as one String plus line-start offsets: ~120k words without ~120k String objects. */
private class WordList(val text: String, private val starts: IntArray) {
    val size: Int get() = starts.size - 1

    fun start(i: Int) = starts[i]

    fun length(i: Int) = starts[i + 1] - 1 - starts[i]

    fun word(i: Int) = text.substring(starts[i], starts[i + 1] - 1)

    operator fun contains(word: String): Boolean {
        var lo = 0
        var hi = size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = compare(mid, word)
            when {
                c < 0 -> lo = mid + 1
                c > 0 -> hi = mid - 1
                else -> return true
            }
        }
        return false
    }

    /** String.compareTo of word [i] against [word], char by char in place. */
    private fun compare(i: Int, word: String): Int {
        val s = starts[i]
        val length = length(i)
        for (k in 0 until min(length, word.length)) {
            val d = text[s + k] - word[k]
            if (d != 0) return d
        }
        return length - word.length
    }

    /** String.compareTo of words [i] and [j]. */
    private fun compareWords(i: Int, j: Int): Int {
        val si = starts[i]
        val sj = starts[j]
        for (k in 0 until min(length(i), length(j))) {
            val d = text[si + k] - text[sj + k]
            if (d != 0) return d
        }
        return length(i) - length(j)
    }

    /** Whether the words are non-empty, strictly ascending and already normalized (lowercase, no ’ or blanks). */
    private fun isCanonical(): Boolean {
        val end = if (size == 0) 0 else starts[size] - 1
        for (k in 0 until end) {
            val c = text[k]
            if (c in 'A'..'Z' || c == ' ' || c == '\t' || c == '’' || (c >= '\u0080' && c.isUpperCase())) return false
        }
        for (i in 0 until size) {
            if (length(i) == 0) return false
            if (i > 0 && compareWords(i - 1, i) >= 0) return false
        }
        return true
    }

    companion object {
        /** Parses dict/en_US.txt; anything else (unsorted, mixed case, CRLF, blank lines) is normalized first. */
        fun parse(wordList: String): WordList {
            val list = offsets(if ('\r' in wordList) wordList.replace("\r", "") else wordList)
            if (list.isCanonical()) return list
            val words = wordList.split('\n').map { it.trim().lowercase().replace('’', '\'') }.filter { it.isNotEmpty() }
            return offsets(words.distinct().sorted().joinToString("\n"))
        }

        private fun offsets(text: String): WordList {
            var end = text.length
            while (end > 0 && text[end - 1] == '\n') end--
            var lines = if (end == 0) 0 else 1
            for (k in 0 until end) if (text[k] == '\n') lines++
            val starts = IntArray(lines + 1)
            var line = 1
            for (k in 0 until end) if (text[k] == '\n') starts[line++] = k + 1
            starts[lines] = end + 1
            return WordList(text, starts)
        }
    }
}

/** Restricted Damerau–Levenshtein (optimal string alignment) distance that gives up past [MAX_EDITS]; reuses its rows. */
private class BoundedOsa(longest: Int) {
    private var twoBack = IntArray(longest + 2)
    private var previous = IntArray(longest + 2)
    private var current = IntArray(longest + 2)

    /** Distance from [a] to the [m] chars of [text] at [from], or [MAX_EDITS] + 1 when it's more than that. */
    fun distance(a: String, text: String, from: Int, m: Int): Int {
        val n = a.length
        val far = MAX_EDITS + 1
        if (abs(n - m) > MAX_EDITS) return far
        for (j in 0..m) previous[j] = j
        for (i in 1..n) {
            // Only the band |i - j| <= MAX_EDITS can stay within reach; the cells beside it read as "far".
            val lo = max(1, i - MAX_EDITS)
            val hi = min(m, i + MAX_EDITS)
            current[lo - 1] = if (lo == 1) i else far
            if (hi < m) current[hi + 1] = far
            var best = current[lo - 1]
            val ai = a[i - 1]
            for (j in lo..hi) {
                val bj = text[from + j - 1]
                var d = min(previous[j - 1] + (if (ai == bj) 0 else 1), min(previous[j], current[j - 1]) + 1)
                if (i > 1 && j > 1 && ai == text[from + j - 2] && a[i - 2] == bj) d = min(d, twoBack[j - 2] + 1)
                current[j] = d
                if (d < best) best = d
            }
            if (best > MAX_EDITS) return far
            val recycled = twoBack
            twoBack = previous
            previous = current
            current = recycled
        }
        return min(previous[m], far)
    }
}
