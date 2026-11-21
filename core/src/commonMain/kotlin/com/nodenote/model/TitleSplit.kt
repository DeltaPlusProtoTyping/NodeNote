package com.nodenote.model

/** A title longer than this reads as notes on a card; [splitLongTitle] offers a shorter one. */
const val LONG_TITLE = 60

/** A long title cut in two: a short [title] for the card and the [rest] for its description. */
data class TitleSplit(val title: String, val rest: String)

/**
 * Where a long title breaks into a headline and the rest: at its first sentence end (". ", "..",
 * "?", "!"), else, unless [sentenceOnly], at a dash, colon, semicolon, bracket or quote. Null for
 * titles of [LONG_TITLE] characters or fewer, or with no break that leaves a headline of at least
 * [MIN_HEAD] characters. Plain character scanning: Kotlin/Wasm's regex engine is best avoided.
 */
fun splitLongTitle(title: String, sentenceOnly: Boolean = false): TitleSplit? {
    val t = title.trim()
    if (t.length <= LONG_TITLE) return null
    sentenceBreak(t)?.let { return it }
    if (sentenceOnly) return null
    var best: TitleSplit? = null
    var bestAt = Int.MAX_VALUE
    for ((sep, keep) in OTHER_BREAKS) {
        var from = 0
        while (true) {
            val at = t.indexOf(sep, from)
            if (at < 0) break
            val head = t.substring(0, at).trim().trimEnd(',', '-')
            val rest = (if (keep) t.substring(at) else t.substring(at + sep.length)).trim()
            if (head.length >= MIN_HEAD && rest.isNotEmpty()) {
                if (at < bestAt) {
                    best = TitleSplit(head, rest)
                    bestAt = at
                }
                break
            }
            from = at + sep.length
        }
    }
    return best
}

private const val MIN_HEAD = 12

/** Separators besides sentence ends; true = the separator starts the rest (a bracket or quote). */
private val OTHER_BREAKS = listOf(" - " to false, " — " to false, " – " to false, ": " to false, "; " to false, " (" to true, " \"" to true)

private fun sentenceBreak(t: String): TitleSplit? {
    var i = 0
    while (i < t.length) {
        val c = t[i]
        if (c == '.' || c == '?' || c == '!') {
            var j = i
            while (j + 1 < t.length && (t[j + 1] == '.' || t[j + 1] == '?' || t[j + 1] == '!')) j++
            if (j + 1 < t.length && t[j + 1].isWhitespace()) {
                // A question keeps its mark; trailing dots ("..", "...") go.
                val head = t.substring(0, j + 1).trimEnd('.').trim()
                val rest = t.substring(j + 1).trim()
                if (head.length >= MIN_HEAD && rest.isNotEmpty()) return TitleSplit(head, rest)
            }
            i = j + 1
        } else {
            i++
        }
    }
    return null
}
