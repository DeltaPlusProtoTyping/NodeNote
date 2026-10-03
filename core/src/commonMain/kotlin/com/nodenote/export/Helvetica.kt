package com.nodenote.export

/** Standard Helvetica / Helvetica-Bold metrics (Adobe AFM advance widths, per 1000 em). Arial has identical widths, so SVG text set in "Helvetica, Arial, sans-serif" matches. */
object Helvetica {
    const val ASCENT = 0.718f   // AFM Ascender / 1000 (use for baseline placement)
    const val DESCENT = 0.207f  // -AFM Descender / 1000

    /** Advance assumed for characters the fonts don't have. */
    private const val UNKNOWN = 556
    /** Advance of the space between wrapped words (same in both weights). */
    private const val SPACE = 278
    private const val ELLIPSIS = "\u2026"

    /** Advance width of [text] at font [size], plus [letterSpacing] after every character (as SVG/PDF apply it). */
    fun width(text: String, size: Float, bold: Boolean = false, letterSpacing: Float = 0f): Float {
        var units = 0
        var count = 0
        forEachWinAnsi(text) {
            units += advance(it, bold)
            count++
        }
        return units * size / 1000f + letterSpacing * count
    }

    /**
     * Greedy word wrap into lines no wider than [maxWidth]. Explicit '\n' starts a new line; runs of spaces collapse;
     * a word wider than [maxWidth] is broken by characters. If more than [maxLines] lines would result, the last kept
     * line is shortened so that it plus "…" fits, and ends with "…". Returns at least one line ("" for blank input);
     * blank lines at the very start and end are dropped.
     */
    fun wrap(text: String, maxWidth: Float, size: Float, bold: Boolean = false, maxLines: Int = Int.MAX_VALUE): List<String> {
        // Widths are summed in integer font units, so a line's width here equals width(line) exactly.
        fun fits(units: Int) = units * size / 1000f <= maxWidth
        val lines = ArrayList<String>()
        val line = StringBuilder()
        var lineUnits = 0
        fun flush() {
            lines += line.toString()
            line.clear()
            lineUnits = 0
        }
        for (paragraph in text.replace("\r\n", "\n").split('\n', '\r')) {
            for (word in paragraph.split(' ', '\t')) {
                if (word.isEmpty()) continue
                val wordUnits = units(word, bold)
                if (line.isNotEmpty()) {
                    if (fits(lineUnits + SPACE + wordUnits)) {
                        line.append(' ').append(word)
                        lineUnits += SPACE + wordUnits
                        continue
                    }
                    flush()
                }
                if (fits(wordUnits)) {
                    line.append(word)
                    lineUnits = wordUnits
                    continue
                }
                // Wider than a whole line: break between characters (never inside a surrogate pair).
                var i = 0
                while (i < word.length) {
                    val end = if (word[i].isHighSurrogate() && i + 1 < word.length && word[i + 1].isLowSurrogate()) i + 2 else i + 1
                    val charUnits = units(word.substring(i, end), bold)
                    if (line.isNotEmpty() && !fits(lineUnits + charUnits)) flush()
                    line.append(word, i, end)
                    lineUnits += charUnits
                    i = end
                }
            }
            flush()
        }
        while (lines.size > 1 && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)
        while (lines.size > 1 && lines.first().isEmpty()) lines.removeAt(0)

        val max = maxLines.coerceAtLeast(1)
        if (lines.size <= max) return lines
        var last = lines[max - 1]
        val ellipsisUnits = units(ELLIPSIS, bold)
        while (last.isNotEmpty() && !fits(units(last, bold) + ellipsisUnits)) last = last.dropLastChar()
        return lines.subList(0, max - 1) + (last.trimEnd(' ') + ELLIPSIS)
    }

    /** Advance of WinAnsi [code] (-1 = not in WinAnsi), in 1/1000 em. */
    internal fun advance(code: Int, bold: Boolean): Int =
        if (code in 32..255) (if (bold) BOLD else REGULAR)[code - 32] else UNKNOWN

    private fun units(text: String, bold: Boolean): Int {
        var units = 0
        forEachWinAnsi(text) { units += advance(it, bold) }
        return units
    }

    private fun String.dropLastChar(): String =
        dropLast(if (length >= 2 && this[length - 1].isLowSurrogate() && this[length - 2].isHighSurrogate()) 2 else 1)

    // Advance widths by WinAnsiEncoding code 0x20..0xFF, from Adobe's Core14 AFM files. Codes WinAnsi
    // leaves unassigned (0x7F, 0x81, 0x8D, 0x8F, 0x90, 0x9D) are 0 and never looked up.
    private val REGULAR = intArrayOf(
        278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,  // 0x20-0x2F
        556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556,  // 0x30-0x3F
        1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778, // 0x40-0x4F
        667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556,  // 0x50-0x5F
        333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556,  // 0x60-0x6F
        556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584, 0,    // 0x70-0x7F
        556, 0, 222, 556, 333, 1000, 556, 556, 333, 1000, 667, 333, 1000, 0, 611, 0,     // 0x80-0x8F
        0, 222, 222, 333, 333, 350, 556, 1000, 333, 1000, 500, 333, 944, 0, 500, 667,    // 0x90-0x9F
        278, 333, 556, 556, 556, 556, 260, 556, 333, 737, 370, 556, 584, 333, 737, 333,  // 0xA0-0xAF
        400, 584, 333, 333, 333, 556, 537, 278, 333, 333, 365, 556, 834, 834, 834, 611,  // 0xB0-0xBF
        667, 667, 667, 667, 667, 667, 1000, 722, 667, 667, 667, 667, 278, 278, 278, 278, // 0xC0-0xCF
        722, 722, 778, 778, 778, 778, 778, 584, 778, 722, 722, 722, 722, 667, 667, 611,  // 0xD0-0xDF
        556, 556, 556, 556, 556, 556, 889, 500, 556, 556, 556, 556, 278, 278, 278, 278,  // 0xE0-0xEF
        556, 556, 556, 556, 556, 556, 556, 584, 611, 556, 556, 556, 556, 500, 556, 500,  // 0xF0-0xFF
    )
    private val BOLD = intArrayOf(
        278, 333, 474, 556, 556, 889, 722, 238, 333, 333, 389, 584, 278, 333, 278, 278,  // 0x20-0x2F
        556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 333, 333, 584, 584, 584, 611,  // 0x30-0x3F
        975, 722, 722, 722, 722, 667, 611, 778, 722, 278, 556, 722, 611, 833, 722, 778,  // 0x40-0x4F
        667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 333, 278, 333, 584, 556,  // 0x50-0x5F
        333, 556, 611, 556, 611, 556, 333, 611, 611, 278, 278, 556, 278, 889, 611, 611,  // 0x60-0x6F
        611, 611, 389, 556, 333, 611, 556, 778, 556, 556, 500, 389, 280, 389, 584, 0,    // 0x70-0x7F
        556, 0, 278, 556, 500, 1000, 556, 556, 333, 1000, 667, 333, 1000, 0, 611, 0,     // 0x80-0x8F
        0, 278, 278, 500, 500, 350, 556, 1000, 333, 1000, 556, 333, 944, 0, 500, 667,    // 0x90-0x9F
        278, 333, 556, 556, 556, 556, 280, 556, 333, 737, 370, 556, 584, 333, 737, 333,  // 0xA0-0xAF
        400, 584, 333, 333, 333, 611, 556, 278, 333, 333, 365, 556, 834, 834, 834, 611,  // 0xB0-0xBF
        722, 722, 722, 722, 722, 722, 1000, 722, 667, 667, 667, 667, 278, 278, 278, 278, // 0xC0-0xCF
        722, 722, 778, 778, 778, 778, 778, 584, 778, 722, 722, 722, 722, 667, 667, 611,  // 0xD0-0xDF
        556, 556, 556, 556, 556, 556, 889, 556, 556, 556, 556, 556, 278, 278, 278, 278,  // 0xE0-0xEF
        611, 611, 611, 611, 611, 611, 611, 584, 611, 611, 611, 611, 611, 556, 611, 556,  // 0xF0-0xFF
    )
}

/** What WinAnsiEncoding puts at 0x80..0x9F ('\u0000' = unassigned); 0x20–0x7E and 0xA0–0xFF are Latin-1 as-is. */
private const val WIN_ANSI_80 =
    "\u20AC\u0000\u201A\u0192\u201E\u2026\u2020\u2021\u02C6\u2030\u0160\u2039\u0152\u0000\u017D\u0000" +
        "\u0000\u2018\u2019\u201C\u201D\u2022\u2013\u2014\u02DC\u2122\u0161\u203A\u0153\u0000\u017E\u0178"

/** WinAnsiEncoding code of [c] (the encoding of the PDF's Helvetica fonts), or -1 if it has none. */
internal fun winAnsiCode(c: Char): Int {
    val code = c.code
    return when {
        code in 0x20..0x7E || code in 0xA0..0xFF -> code
        code >= 0x100 -> WIN_ANSI_80.indexOf(c).let { if (it < 0) -1 else 0x80 + it }
        else -> -1
    }
}

/**
 * Calls [action] with the WinAnsi code (-1 if none) of each character of [text] as the writers draw
 * it: tab, CR and LF draw as spaces; controls, invisible formatting characters and lone surrogates
 * draw nothing; a surrogate pair (emoji etc.) is one character.
 */
internal inline fun forEachWinAnsi(text: String, action: (Int) -> Unit) {
    var i = 0
    while (i < text.length) {
        val c = text[i++]
        when {
            c == '\t' || c == '\n' || c == '\r' -> action(0x20)
            isInvisible(c) -> {}
            c.isHighSurrogate() -> if (i < text.length && text[i].isLowSurrogate()) {
                i++
                action(-1)
            }
            c.isLowSurrogate() -> {}
            else -> action(winAnsiCode(c))
        }
    }
}

/** Controls plus invisible formatting characters (soft hyphen, zero-width and bidi marks, variation selectors). */
internal fun isInvisible(c: Char): Boolean =
    isXmlDropped(c) || c == '\u00AD' || c in '\u200B'..'\u200F' || c in '\u202A'..'\u202E' ||
        c in '\u2060'..'\u2064' || c in '\uFE00'..'\uFE0F' || c == '\uFEFF'
