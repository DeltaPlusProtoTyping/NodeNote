package com.nodenote.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Snapshots of the whole database in `<data>/backups/`, so no app update (or bad
 * edit) can cost more than a day of work: one at every server start — i.e. before
 * a new build touches the data — and one every 24 h while running. The newest
 * [keep] copies are kept. Each file is a complete SQLite database; to restore,
 * stop the server and copy it over `nodenote.db`.
 */
class Backups(
    private val db: Database,
    private val dir: Path,
    private val keep: Int = 30,
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(Backups::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start() {
        snapshot("startup")
        job = scope.launch {
            while (isActive) {
                delay(DAY_MILLIS)
                snapshot("daily")
            }
        }
    }

    /** Writes one backup (skipped while the database is still empty). Never throws. */
    fun snapshot(reason: String): Path? = try {
        if (db.isEmpty()) {
            null
        } else {
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val target = dir.resolve("nodenote-$stamp-$reason.db")
            db.backupTo(target)
            prune()
            log.info("Backup written: {}", target)
            target
        }
    } catch (e: Exception) {
        log.error("Backup ($reason) failed", e)
        null
    }

    private fun prune() {
        val files = Files.list(dir).use { s ->
            s.filter { it.isRegularFile() && it.name.startsWith("nodenote-") && it.name.endsWith(".db") }.toList()
        }
        files.sortedByDescending { it.name }.drop(keep).forEach { Files.deleteIfExists(it) }
    }

    override fun close() = scope.cancel()

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
