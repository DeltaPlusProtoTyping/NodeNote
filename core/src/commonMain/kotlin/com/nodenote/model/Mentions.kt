package com.nodenote.model

/*
 * People on a board are named with an @ ("ask @Kurt", "@Rivva updates it by hand"). Plain
 * loops, no regex: the browser build's regex engine rejects Unicode classes.
 */

private fun Char.isNamePart() = isLetterOrDigit() || this == '_' || this == '-' || this == '.'

/** The names @-mentioned in [text], without the @, in order (repeats included). An e-mail address is not a mention. */
fun mentionsIn(text: String): List<String> {
    val out = ArrayList<String>()
    var i = 0
    while (i < text.length) {
        val at = text.indexOf('@', i)
        if (at < 0) break
        i = at + 1
        if (at > 0 && text[at - 1].isLetterOrDigit()) continue // someone@example.com
        if (i >= text.length || !text[i].isLetter()) continue
        var end = i
        while (end < text.length && text[end].isNamePart()) end++
        val name = text.substring(i, end).trimEnd('.', '-', '_')
        if (name.isNotEmpty()) out.add(name)
        i = end
    }
    return out
}

/** True if [text] contains [name] as a whole word, with or without a leading @, ignoring case. */
fun mentionsWord(text: String, name: String): Boolean {
    if (name.isEmpty()) return false
    var from = 0
    while (true) {
        val at = text.indexOf(name, from, ignoreCase = true)
        if (at < 0) return false
        val before = text.getOrNull(at - 1)
        val after = text.getOrNull(at + name.length)
        if ((before == null || !before.isLetterOrDigit()) && (after == null || !after.isLetterOrDigit())) return true
        from = at + 1
    }
}
