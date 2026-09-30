package com.nodenote.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Keeps the Obsidian mirror in step with the [BoardStore].
 *
 * Changes are debounced per board (a burst of drags or a typing session becomes
 * one write), and a file is only rewritten when its content actually changed, so
 * Obsidian Sync / git / Dropbox don't see churn.
 *
 * The writer only ever deletes or overwrites files whose frontmatter carries
 * `nodenote: generated`. The mirror can therefore share a vault — even a folder —
 * with hand-written notes without ever touching them.
 */
class VaultWriter(
    private val vaultDir: Path,
    private val store: BoardStore,
    private val debounceMillis: Long = 1500,
    private val renderer: MarkdownRenderer = MarkdownRenderer(),
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("vault-writer"))
    private val pending = ConcurrentHashMap<String, Job>()
    /** One pass at a time: folder assignment and `Boards.md` look at every board. */
    private val lock = Mutex()

    /** Regenerates [boardId] once it has been quiet for the debounce interval. */
    fun schedule(boardId: String) {
        pending.compute(boardId) { _, previous ->
            previous?.cancel()
            scope.launch {
                delay(debounceMillis)
                // Past the debounce: a newer change now schedules a fresh pass instead of cancelling this one.
                pending.remove(boardId, coroutineContext.job)
                write(setOf(boardId))
            }
        }
    }

    /** Regenerates every board in the background (used at startup). */
    fun rebuildAllAsync(): Job = scope.launch { rebuildAll() }

    /** Regenerates every board now, and returns when the files are written. */
    suspend fun rebuildAll() {
        val ids = store.vaultEntries().map { it.summary.id }.toSet()
        ids.forEach { pending.remove(it)?.cancel() }
        write(ids)
    }

    /** Writes whatever is still waiting out its debounce — called on shutdown. */
    suspend fun flush() {
        val ids = pending.keys.toSet()
        ids.forEach { pending.remove(it)?.cancel() }
        if (ids.isNotEmpty()) write(ids)
    }

    override fun close() {
        scope.cancel()
    }

    private suspend fun write(requested: Set<String>) = lock.withLock {
        withContext(Dispatchers.IO) {
            try {
                writeLocked(requested)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Vault write failed", e)
            }
        }
    }

    private suspend fun writeLocked(requested: Set<String>) {
        Files.createDirectories(vaultDir)
        val entries = store.vaultEntries()
        val folders = VaultNames.boardFolders(entries.map { it.summary })
        // Besides the boards asked for, rewrite any whose folder moved — a rename, or another
        // board's rename freeing up / taking over a name — and any never written at all.
        val targets = entries.filter { it.summary.id in requested || it.vaultFolder != folders[it.summary.id] }
        for (entry in targets) {
            val id = entry.summary.id
            val folder = folders.getValue(id)
            try {
                val board = store.vaultBoard(id) ?: continue
                writeBoard(board, folder, entry.vaultFolder, folders.values)
                store.setVaultFolder(id, folder)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Vault write failed for board $id (\"${entry.summary.name}\")", e)
            }
        }
        val index = renderer.renderBoardsIndex(entries.map { it.summary to folders.getValue(it.summary.id) })
        val indexName = "${VaultNames.BOARDS_INDEX}.md"
        writeGenerated(vaultDir, indexName, index, markdownFiles(vaultDir)[indexName.lowercase()])
    }

    private fun writeBoard(board: VaultBoard, folder: String, previousFolder: String?, allFolders: Collection<String>) {
        ensureExactCase(vaultDir, folder) // "shop floor" → "Shop Floor" is the same directory on Windows
        val dir = vaultDir.resolve(folder)
        Files.createDirectories(dir)

        val files = renderer.renderBoard(board, folder)
        val existing = markdownFiles(dir)
        var written = 0
        for ((name, content) in files) {
            if (writeGenerated(dir, name, content, existing[name.lowercase()])) written++
        }
        val expected = files.keys.mapTo(HashSet()) { it.lowercase() }
        val stale = existing.filterKeys { it !in expected }.values.filter(::isGenerated)
        stale.forEach(::deleteQuietly)
        if (written > 0 || stale.isNotEmpty()) {
            log.info("Vault: \"$folder\" — $written written, ${stale.size} removed (${files.size} notes)")
        }

        // Renamed board: clear the generated notes out of the old folder, unless another
        // board has just been given that name.
        if (previousFolder != null &&
            !previousFolder.equals(folder, ignoreCase = true) &&
            allFolders.none { it.equals(previousFolder, ignoreCase = true) }
        ) {
            removeGeneratedFolder(vaultDir.resolve(previousFolder))
        }
    }

    /**
     * Writes [content] to [dir]/[name] unless that would change nothing or clobber a
     * hand-written note. [existing] is the file already there under any letter case.
     * Returns true if the file was written.
     */
    private fun writeGenerated(dir: Path, name: String, content: String, existing: Path?): Boolean {
        if (existing != null) {
            if (!isGenerated(existing)) {
                log.warn("Vault: not overwriting $existing — it has no `$MARKER` frontmatter")
                return false
            }
            if (existing.name != name) {
                renameCase(existing, name)
            } else if (readOrNull(existing) == content) {
                return false
            }
        }
        val target = dir.resolve(name)
        // Write-then-rename, so Obsidian never picks up a half-written note. The dot prefix
        // and .tmp suffix keep the temp file out of Obsidian's index.
        val tmp = dir.resolve(".$name.tmp")
        Files.writeString(tmp, content)
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
        return true
    }

    /** Deletes the generated notes in [dir], then [dir] itself if nothing else is left. */
    private fun removeGeneratedFolder(dir: Path) {
        if (!dir.isDirectory()) return
        val removed = markdownFiles(dir).values.filter(::isGenerated)
        removed.forEach(::deleteQuietly)
        val empty = Files.list(dir).use { !it.findAny().isPresent }
        if (empty) deleteQuietly(dir)
        log.info("Vault: cleaned old folder \"${dir.name}\" (${removed.size} notes removed${if (empty) ", folder deleted" else ""})")
    }

    /** If [parent] holds [name] in a different letter case, rename it to exactly [name]. */
    private fun ensureExactCase(parent: Path, name: String) {
        if (!parent.isDirectory()) return
        val entries = Files.list(parent).use { s -> s.map { it.name }.toList() }
        if (name in entries) return
        entries.firstOrNull { it.equals(name, ignoreCase = true) }?.let { renameCase(parent.resolve(it), name) }
    }

    /** Case-only rename. Via a temp name, because case-insensitive file systems treat a direct move as a no-op. */
    private fun renameCase(path: Path, newName: String) {
        val tmp = path.resolveSibling(".${newName}.renaming")
        Files.move(path, tmp)
        Files.move(tmp, path.resolveSibling(newName))
    }

    private companion object {
        val log = LoggerFactory.getLogger(VaultWriter::class.java)
        const val MARKER = "nodenote: generated"

        /** `.md` files directly in [dir], keyed by lower-cased name (file systems here are case-insensitive). */
        fun markdownFiles(dir: Path): Map<String, Path> {
            if (!dir.isDirectory()) return emptyMap()
            return Files.list(dir).use { s ->
                s.filter { it.isRegularFile() && it.name.endsWith(".md", ignoreCase = true) }.toList()
            }.associateBy { it.name.lowercase() }
        }

        /** True if [path] starts with YAML frontmatter containing the generated marker. */
        fun isGenerated(path: Path): Boolean = try {
            Files.newBufferedReader(path).use { reader ->
                if (reader.readLine()?.trim() != "---") return false
                repeat(200) {
                    val line = reader.readLine()?.trim() ?: return false
                    if (line == "---") return false
                    if (line == MARKER) return true
                }
                false
            }
        } catch (e: IOException) {
            false // unreadable or not UTF-8: certainly not ours
        }

        fun readOrNull(path: Path): String? = try {
            Files.readString(path)
        } catch (e: IOException) {
            null
        }

        fun deleteQuietly(path: Path) {
            try {
                Files.deleteIfExists(path)
            } catch (e: IOException) {
                log.warn("Vault: could not delete $path: ${e.message}")
            }
        }
    }
}
