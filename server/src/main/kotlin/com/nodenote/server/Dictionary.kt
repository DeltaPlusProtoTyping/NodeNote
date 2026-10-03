package com.nodenote.server

/** The English word list the apps spell-check against (see resources/dict/README.txt), loaded on first request. */
object Dictionary {
    val words: String? by lazy {
        Dictionary::class.java.getResourceAsStream("/dict/en_US.txt")?.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
