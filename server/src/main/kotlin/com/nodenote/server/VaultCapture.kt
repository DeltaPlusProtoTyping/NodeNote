package com.nodenote.server

import com.nodenote.model.Message
import com.nodenote.model.newId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * `Capture.md` at the vault root: a note written by hand in Obsidian whose lines land in a board's
 * Unsorted pile, as if dumped in the app. A line goes to the board changed most recently, or, under
 * a heading with a board's name (`## Shop floor`), to that board.
 *
 * The server never changes the note (it only creates it, with instructions, when it's missing).
 * What has been sent is remembered in [sentFile], so a line is sent once and can stay in the note
 * or be cleared whenever. A note still being typed in is left alone until it has been quiet for
 * [quietMillis], so half a line never goes out.
 */
class VaultCapture(
    private val file: Path,
    private val sentFile: Path,
    private val store: BoardStore,
    /** Whose name the captured thoughts carry; null = nobody to post as yet, so wait. */
    private val author: suspend () -> String?,
    private val intervalMillis: Long = 30_000,
    private val quietMillis: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("vault-capture"))

    fun start() {
        scope.launch {
            while (isActive) {
                try {
                    poll()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Capture: ${e.message}")
                }
                delay(intervalMillis)
            }
        }
    }

    override fun close() {
        scope.cancel()
    }

    /** Sends the new lines of the note, if it has been quiet long enough. Returns how many were sent. */
    suspend fun poll(): Int = withContext(Dispatchers.IO) {
        if (!Files.exists(file)) {
            createNote()
            return@withContext 0
        }
        if (clock() - Files.getLastModifiedTime(file).toMillis() < quietMillis) return@withContext 0
        val boards = store.list(includeArchived = false)
        if (boards.isEmpty()) return@withContext 0
        val sent = readSent()
        val fresh = captureLines(Files.readString(file)).filter { key(it.text) !in sent }
        if (fresh.isEmpty()) return@withContext 0
        val name = author() ?: return@withContext 0
        val latest = boards.maxBy { it.updatedAt }
        var count = 0
        for (line in fresh) {
            val board = line.board?.let { b -> boards.find { it.name.trim().equals(b, ignoreCase = true) } } ?: latest
            store.postMessage(board.id, Message(id = newId(), boardId = board.id, author = name, text = line.text), name)
            // Recorded after posting: a crash in between sends the line twice rather than never.
            Files.writeString(sentFile, key(line.text) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            count++
        }
        log.info("Capture: sent $count line${if (count == 1) "" else "s"} to Unsorted")
        count
    }

    private fun readSent(): Set<String> = try {
        if (Files.exists(sentFile)) Files.readAllLines(sentFile).toHashSet() else emptySet()
    } catch (e: IOException) {
        // Better to send nothing than to send everything again.
        throw IOException("can't read ${sentFile.fileName}: ${e.message}", e)
    }

    private fun createNote() {
        Files.createDirectories(file.parent)
        Files.writeString(file, INSTRUCTIONS, StandardOpenOption.CREATE_NEW)
        log.info("Capture: created $file")
    }

    data class Line(val board: String?, val text: String)

    companion object {
        private val log = LoggerFactory.getLogger(VaultCapture::class.java)

        const val NAME = "Capture"

        private val INSTRUCTIONS = """
            |# Capture
            |
            |> Anything written here goes to NodeNote. Each new line lands in Unsorted on the board changed most
            |> recently, or, under a heading with a board's name (like `## Shop floor`), on that board. Sort them in the app.
            |> Lines are sent once, about a minute after you stop typing. They stay here afterwards; clear them whenever.
            |
            |""".trimMargin()

        /** What a line is remembered by once sent: its text, whitespace collapsed. */
        fun key(text: String): String = text.split(' ', '\t').filter { it.isNotEmpty() }.joinToString(" ")

        /**
         * The thoughts in the note: every non-empty line outside the frontmatter, with list and
         * checkbox markers taken off, and the board heading it sits under. Instructions (`>`),
         * headings, rules and ticked checkboxes are skipped.
         */
        fun captureLines(text: String): List<Line> {
            val lines = text.replace("\r\n", "\n").split('\n')
            var start = 0
            if (lines.firstOrNull()?.trim() == "---") {
                val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
                if (end >= 0) start = end + 2
            }
            var board: String? = null
            val out = mutableListOf<Line>()
            for (raw in lines.drop(start)) {
                val line = raw.trim()
                when {
                    line.isEmpty() || line.startsWith(">") || line == "---" || line == "***" -> Unit
                    line.startsWith("#") -> {
                        val heading = line.trimStart('#').trim()
                        board = heading.takeUnless { it.equals(NAME, ignoreCase = true) || it.isEmpty() }
                    }
                    line.startsWith("- [x]", ignoreCase = true) -> Unit
                    else -> {
                        val thought = stripMarker(line)
                        if (thought.isNotEmpty()) out += Line(board, key(thought))
                    }
                }
            }
            return out
        }

        private fun stripMarker(line: String): String {
            for (marker in listOf("- [ ] ", "* [ ] ", "- ", "* ", "+ ")) {
                if (line.startsWith(marker)) return line.removePrefix(marker).trim()
            }
            val digits = line.takeWhile { it.isDigit() }
            if (digits.isNotEmpty() && line.drop(digits.length).startsWith(". ")) return line.drop(digits.length + 2).trim()
            return line
        }
    }
}
