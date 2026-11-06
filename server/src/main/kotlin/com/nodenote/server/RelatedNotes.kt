package com.nodenote.server

import com.nodenote.api.RelatedDoc
import com.nodenote.api.RelatedItem
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import kotlin.concurrent.thread

/**
 * What [RelatedNotes] searches, from `<data>/related.txt`. No file, no feature. One setting a line:
 *   user=Ben                       the one account that may use it
 *   notes=Work email|C:\mail       every .md under the folder is one note
 *   rows=Purchases|C:\buys.md      every `| date | who | text | … |` table row is one note
 */
class RelatedConfig(val user: String, val sources: List<Source>) {
    class Source(val label: String, val path: File, val rows: Boolean)

    companion object {
        fun load(file: Path): RelatedConfig? {
            if (!Files.isRegularFile(file)) return null
            var user = ""
            val sources = mutableListOf<Source>()
            Files.readAllLines(file).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
                val key = line.substringBefore('=').trim()
                val value = line.substringAfter('=', "").trim()
                when (key) {
                    "user" -> user = value
                    "notes", "rows" -> {
                        val label = value.substringBefore('|').trim()
                        val path = value.substringAfter('|', "").trim()
                        if (label.isNotEmpty() && path.isNotEmpty()) sources += Source(label, File(path), key == "rows")
                    }
                }
            }
            return if (user.isEmpty() || sources.isEmpty()) null else RelatedConfig(user, sources)
        }
    }
}

/**
 * Finds notes outside the board that relate to a card: a private search index over folders of
 * markdown on this computer (mail, old chats, purchases). It only ever reads those folders, and
 * keeps its index and its pins and dismissals in a file of its own beside the database, so
 * neither the boards nor the vault mirror can pick anything up from it. A broken index costs
 * this feature, never the server: delete the file and it rebuilds.
 */
class RelatedNotes(val config: RelatedConfig, indexFile: Path) : AutoCloseable {
    private val log = LoggerFactory.getLogger("RelatedNotes")
    private val db: Connection = run {
        Files.createDirectories(indexFile.parent)
        DriverManager.getConnection("jdbc:sqlite:$indexFile")
    }
    @Volatile private var closed = false

    init {
        db.createStatement().use { st ->
            st.execute("PRAGMA journal_mode = WAL")
            st.execute("CREATE TABLE IF NOT EXISTS docs(id INTEGER PRIMARY KEY, path TEXT UNIQUE NOT NULL, source TEXT NOT NULL, title TEXT NOT NULL, who TEXT NOT NULL, date TEXT NOT NULL, thread TEXT NOT NULL, stamp TEXT NOT NULL)")
            st.execute("CREATE INDEX IF NOT EXISTS docs_thread ON docs(thread)")
            st.execute("CREATE VIRTUAL TABLE IF NOT EXISTS docs_fts USING fts5(title, body, who, tokenize = 'porter unicode61')")
            // Marks are by thread, so dismissing one email also dismisses its replies and copies.
            st.execute("CREATE TABLE IF NOT EXISTS marks(board_id TEXT NOT NULL, node_id TEXT NOT NULL, thread TEXT NOT NULL, state TEXT NOT NULL, PRIMARY KEY(board_id, node_id, thread))")
        }
    }

    /** Indexes now and then every [INDEX_EVERY_MILLIS] on a background thread. */
    fun start() {
        thread(name = "related-index", isDaemon = true, priority = Thread.MIN_PRIORITY) {
            while (!closed) {
                try {
                    indexOnce()
                } catch (e: Exception) {
                    if (!closed) log.warn("Indexing stopped: ${e.message}")
                }
                try {
                    Thread.sleep(INDEX_EVERY_MILLIS)
                } catch (e: InterruptedException) {
                    return@thread
                }
            }
        }
    }

    // ---- indexing ----

    fun indexOnce() {
        val labels = config.sources.map { it.label }.toSet()
        // A source taken out of related.txt leaves the index.
        synchronized(this) {
            query("SELECT DISTINCT source FROM docs") { it.getString(1) }.filter { it !in labels }.forEach { gone ->
                query("SELECT id FROM docs WHERE source = ?", gone) { it.getLong(1) }.forEach(::deleteDoc)
            }
        }
        for (source in config.sources) {
            if (closed) return
            // Missing for now (a drive, a sync): keep what's indexed rather than forget it all.
            if (!source.path.exists()) {
                log.warn("Related source not found: ${source.label}")
                continue
            }
            if (source.rows) indexRows(source) else indexFolder(source)
        }
    }

    private fun indexFolder(source: RelatedConfig.Source) {
        val known = synchronized(this) { known(source.label) }
        val seen = HashSet<String>()
        val pending = ArrayList<ParsedNote>()
        source.path.walkTopDown().filter { it.isFile && it.extension.equals("md", ignoreCase = true) }.forEach { file ->
            if (closed) return
            val path = file.path
            seen += path
            val stamp = "${file.lastModified()}:${file.length()}"
            if (known[path] != stamp) {
                try {
                    pending += parseNote(file.readText(), file.nameWithoutExtension, path, stamp)
                } catch (e: Exception) {
                    log.warn("Skipped a note that wouldn't read: ${e.message}")
                }
                if (pending.size >= BATCH) flush(source.label, pending)
            }
        }
        flush(source.label, pending)
        removeMissing(known.keys, seen)
    }

    private fun indexRows(source: RelatedConfig.Source) {
        val known = synchronized(this) { known(source.label) }
        val seen = HashSet<String>()
        val pending = ArrayList<ParsedNote>()
        source.path.readLines().forEach { line ->
            val note = parseRow(line, source.path.path) ?: return@forEach
            seen += note.path
            if (note.path !in known) pending += note
            if (pending.size >= BATCH) flush(source.label, pending)
        }
        flush(source.label, pending)
        removeMissing(known.keys, seen)
    }

    private fun known(source: String): Map<String, String> {
        val out = HashMap<String, String>()
        query("SELECT path, stamp FROM docs WHERE source = ?", source) { out[it.getString(1)] = it.getString(2) }
        return out
    }

    private fun removeMissing(known: Set<String>, seen: Set<String>) {
        val gone = known - seen
        if (gone.isEmpty()) return
        synchronized(this) {
            gone.forEach { path -> query("SELECT id FROM docs WHERE path = ?", path) { it.getLong(1) }.forEach(::deleteDoc) }
        }
    }

    /** One short transaction at a time, so a search never waits long on the indexer. */
    private fun flush(source: String, notes: MutableList<ParsedNote>) {
        if (notes.isEmpty()) return
        synchronized(this) {
            db.autoCommit = false
            try {
                notes.forEach { note ->
                    query("SELECT id FROM docs WHERE path = ?", note.path) { it.getLong(1) }.forEach(::deleteDoc)
                    db.prepareStatement("INSERT INTO docs(path, source, title, who, date, thread, stamp) VALUES(?,?,?,?,?,?,?)").use { st ->
                        listOf(note.path, source, note.title, note.who, note.date, threadKey(source, note.title), note.stamp)
                            .forEachIndexed { i, v -> st.setString(i + 1, v) }
                        st.executeUpdate()
                    }
                    val id = query("SELECT id FROM docs WHERE path = ?", note.path) { it.getLong(1) }.single()
                    db.prepareStatement("INSERT INTO docs_fts(rowid, title, body, who) VALUES(?,?,?,?)").use { st ->
                        st.setLong(1, id)
                        st.setString(2, note.title)
                        st.setString(3, note.body)
                        st.setString(4, note.who)
                        st.executeUpdate()
                    }
                }
                db.commit()
            } catch (e: Exception) {
                db.rollback()
                throw e
            } finally {
                db.autoCommit = true
            }
        }
        notes.clear()
    }

    private fun deleteDoc(id: Long) {
        for (sql in listOf("DELETE FROM docs_fts WHERE rowid = ?", "DELETE FROM docs WHERE id = ?")) {
            db.prepareStatement(sql).use { it.setLong(1, id); it.executeUpdate() }
        }
    }

    // ---- searching ----

    /** Pinned notes, then up to [MAX_RESULTS] others: best the ones sharing the card's words and a linked card's. */
    @Synchronized
    fun search(boardId: String, nodeId: String, title: String, linkedTitles: List<String>): List<RelatedItem> {
        val marks = HashMap<String, String>()
        query("SELECT thread, state FROM marks WHERE board_id = ? AND node_id = ?", boardId, nodeId) { marks[it.getString(1)] = it.getString(2) }

        val out = LinkedHashMap<String, RelatedItem>()
        marks.filterValues { it == PIN }.keys.forEach { thread ->
            query(
                "SELECT d.id, d.source, d.title, d.who, d.date, substr(f.body, 1, 160) FROM docs d JOIN docs_fts f ON f.rowid = d.id WHERE d.thread = ? ORDER BY d.date DESC LIMIT 1",
                thread,
            ) { RelatedItem(it.getLong(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6).oneLine(), pinned = true) }
                .firstOrNull()?.let { out[thread] = it }
        }

        val terms = terms(title)
        if (terms.isNotEmpty()) {
            val tiers = tiers(terms, linkedTitles.flatMap(::terms).distinct().filter { it !in terms }.take(MAX_TERMS))
            val limit = out.size + MAX_RESULTS
            for (match in tiers) {
                if (out.size >= limit) break
                val found = query(
                    """SELECT d.id, d.source, d.title, d.who, d.date, snippet(docs_fts, 1, '', '', '...', 24), d.thread
                       FROM docs_fts JOIN docs d ON d.id = docs_fts.rowid WHERE docs_fts MATCH ?
                       ORDER BY bm25(docs_fts, 10.0, 1.0, 3.0) LIMIT $CANDIDATES""",
                    match,
                ) { it.getString(7) to RelatedItem(it.getLong(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5), it.getString(6).oneLine()) }
                for ((thread, item) in found) {
                    if (out.size >= limit) break
                    if (thread !in marks && thread !in out) out[thread] = item
                }
            }
        }
        return out.values.toList()
    }

    /**
     * Most specific first, and a wrong note is worse than none. A common word alone says nothing
     * ("mark", "air"), so common words count only together: close by in a note, or both in its
     * title. A rare word ("cerakote") counts alone. A linked card's words pick the best of each.
     */
    private fun tiers(terms: List<String>, linked: List<String>): List<String> {
        fun anyOf(words: List<String>) = words.joinToString(" OR ") { "\"$it\"" }
        val rare = terms.filter { term ->
            query("SELECT count(*) FROM docs_fts WHERE docs_fts MATCH ?", "\"$term\"") { it.getInt(1) }.single() <= RARE_WORD_NOTES
        }
        val strong = buildList {
            if (terms.size > 1) {
                add("NEAR(${terms.joinToString(" ") { "\"$it\"" }}, $NEAR_WORDS)")
                val first = terms.take(MAX_PAIR_TERMS)
                val pairs = first.flatMapIndexed { i, a -> first.drop(i + 1).map { b -> "(\"$a\" AND \"$b\")" } }
                add("title : (${pairs.joinToString(" OR ")})")
            } else if (terms.single() !in rare) {
                add("title : \"${terms.single()}\"")
            }
            if (rare.isNotEmpty()) add(anyOf(rare))
        }
        return strong.flatMap { tier -> if (linked.isEmpty()) listOf(tier) else listOf("($tier) AND (${anyOf(linked)})", tier) }
    }

    /** The note, trimmed to what reads comfortably, or null if it has left the index. */
    @Synchronized
    fun doc(id: Long): RelatedDoc? = query(
        "SELECT d.source, d.title, d.who, d.date, f.body FROM docs d JOIN docs_fts f ON f.rowid = d.id WHERE d.id = ?",
        id,
    ) {
        val body = it.getString(5)
        RelatedDoc(id, it.getString(1), it.getString(2), it.getString(3), it.getString(4), body.take(MAX_DOC_CHARS), trimmed = body.length > MAX_DOC_CHARS)
    }.firstOrNull()

    /** [state]: [PIN], [DISMISS], or anything else to clear the mark. False if the note has left the index. */
    @Synchronized
    fun mark(boardId: String, nodeId: String, docId: Long, state: String): Boolean {
        val thread = query("SELECT thread FROM docs WHERE id = ?", docId) { it.getString(1) }.firstOrNull() ?: return false
        if (state == PIN || state == DISMISS) {
            db.prepareStatement("INSERT OR REPLACE INTO marks(board_id, node_id, thread, state) VALUES(?,?,?,?)").use { st ->
                listOf(boardId, nodeId, thread, state).forEachIndexed { i, v -> st.setString(i + 1, v) }
                st.executeUpdate()
            }
        } else {
            db.prepareStatement("DELETE FROM marks WHERE board_id = ? AND node_id = ? AND thread = ?").use { st ->
                listOf(boardId, nodeId, thread).forEachIndexed { i, v -> st.setString(i + 1, v) }
                st.executeUpdate()
            }
        }
        return true
    }

    private fun <T> query(sql: String, vararg args: Any, read: (java.sql.ResultSet) -> T): List<T> =
        db.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(read(rs)) } }
        }

    override fun close() {
        closed = true
        synchronized(this) { db.close() }
    }

    private class ParsedNote(val path: String, val stamp: String, val title: String, val who: String, val date: String, val body: String)

    companion object {
        const val PIN = "pin"
        const val DISMISS = "dismiss"
        private const val INDEX_EVERY_MILLIS = 15 * 60_000L
        private const val BATCH = 100
        private const val MAX_RESULTS = 5
        private const val MAX_TERMS = 12
        private const val CANDIDATES = 60
        /** A word in at most this many notes says what they're about. */
        private const val RARE_WORD_NOTES = 40
        private const val NEAR_WORDS = 12
        private const val MAX_PAIR_TERMS = 8
        private const val MAX_BODY_CHARS = 200_000
        private const val MAX_DOC_CHARS = 20_000

        /** Words too common to say what a card is about. */
        private val STOP_WORDS = setOf(
            "a", "about", "all", "an", "and", "are", "as", "at", "be", "because", "but", "by", "can", "do", "does", "doesn", "don",
            "for", "from", "get", "good", "had", "has", "have", "he", "her", "his", "how", "if", "in", "is", "isn", "it", "its", "just",
            "like", "ll", "make", "me", "more", "much", "my", "no", "not", "of", "on", "or", "our", "out", "re", "she", "so", "some",
            "than", "that", "the", "their", "them", "then", "there", "they", "this", "to", "too", "up", "us", "ve", "was", "we", "were",
            "what", "when", "where", "which", "who", "why", "will", "with", "would", "you", "your",
        )

        private val REPLY_PREFIX = Regex("""^\s*((re|fw|fwd)\s*[:\-]\s*)+""", RegexOption.IGNORE_CASE)
        private val COPY_SUFFIX = Regex("""\s*\(\d+\)\s*$""")
        private val ORDER_PREFIX = Regex("""^(shipped|delivered|arriving[^:]*|out for delivery|ordered)\s*:\s*|^your [\w.]+ order of\s+""", RegexOption.IGNORE_CASE)
        private val ORDER_SUFFIX = Regex("""\s*(has shipped!?|\.)\s*$""", RegexOption.IGNORE_CASE)

        /** The words of a title worth searching for: letters and digits only, so safe to quote in a MATCH. */
        fun terms(title: String): List<String> =
            title.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.length >= 2 && it !in STOP_WORDS }
                .distinct()
                .take(MAX_TERMS)

        /** Replies, forwards and "(1)" copies of a note share one key, and show as one result. */
        private fun threadKey(source: String, title: String): String =
            source + "|" + title.replace(REPLY_PREFIX, "").replace(COPY_SUFFIX, "").replace(ORDER_PREFIX, "").replace(ORDER_SUFFIX, "").trim().lowercase()

        private fun String.oneLine() = replace(Regex("\\s+"), " ").trim()

        private fun parseNote(text: String, fileName: String, path: String, stamp: String): ParsedNote {
            val fields = HashMap<String, String>()
            var body = text
            if (text.startsWith("---")) {
                val end = text.indexOf("\n---", 3)
                if (end > 0) {
                    text.substring(3, end).lineSequence().forEach { line ->
                        val colon = line.indexOf(':')
                        // First wins, and only top-level fields: a quoted header inside doesn't count.
                        if (colon > 0 && !line[0].isWhitespace()) fields.putIfAbsent(line.substring(0, colon).trim().lowercase(), line.substring(colon + 1).trim())
                    }
                    body = text.substring(end + 4).trimStart()
                }
            }
            val title = (fields["subject"] ?: fields["title"])?.unquoted()?.takeIf { it.isNotBlank() } ?: fileName
            return ParsedNote(
                path, stamp, title,
                who = fields["from"]?.personName().orEmpty(),
                date = fields["date"]?.unquoted()?.take(10).orEmpty(),
                body = body.take(MAX_BODY_CHARS),
            )
        }

        /** A `| 2026-11-02 | Vendor | What | $10 | [[note]] |` row, or null for anything else. */
        private fun parseRow(line: String, file: String): ParsedNote? {
            if (!line.startsWith("|")) return null
            val cells = line.trim().trim('|').split(Regex("""(?<!\\)\|""")).map { it.trim() }
            val date = cells.firstOrNull().orEmpty()
            if (cells.size < 3 || !Regex("""\d{4}-\d{2}-\d{2}""").matches(date)) return null
            val what = cells[2].replace("\\|", "|")
            val hash = MessageDigest.getInstance("SHA-256").digest(line.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
            val cost = cells.getOrNull(3).orEmpty()
            return ParsedNote("$file#$hash", "row", what, cells[1], date, if (cost.isEmpty()) what else "$what ($cost)")
        }

        private fun String.unquoted() = trim().removeSurrounding("\"").replace("\\\"", "\"")

        /** `["Sam Smith <sam@x.com>"]` to `Sam Smith`. */
        private fun String.personName(): String {
            val first = trim().removePrefix("[").removeSuffix("]").substringBefore("\",").unquoted().removePrefix("\"").trim()
            return first.substringBefore('<').trim().ifEmpty { first.substringBefore('@').removePrefix("<") }
        }
    }
}
