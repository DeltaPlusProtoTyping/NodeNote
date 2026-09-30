package com.nodenote.server

import com.nodenote.model.Message
import com.nodenote.model.Project
import com.nodenote.api.ApiJson
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Guards for "an app update must never lose or corrupt what's already been entered". */
class DataSafetyTest {

    @Test
    fun backupIsACompleteReadableCopy() {
        val dir = Files.createTempDirectory("nn-backup")
        Database(dir.resolve("nodenote.db")).use { db ->
            val backups = Backups(db, dir.resolve("backups"))
            assertNull(backups.snapshot("empty"), "an empty database isn't worth a backup")

            val project = Project("b1", "Shop floor")
            db.insertBoard(BoardRow("b1", "Shop floor", ApiJson.encodeToString(Project.serializer(), project), 1, 1, 1, false, null))
            db.upsertMessage(Message("m1", "b1", author = "Ben", text = "hello", seq = 1))
            val file = assertNotNull(backups.snapshot("test"))

            Database(file).use { copy ->
                assertEquals("Shop floor", copy.loadBoards().single().name)
                assertEquals("hello", copy.loadMessages().single().text)
            }
            backups.close()
        }
    }

    @Test
    fun keepsOnlyTheNewestBackups() {
        val dir = Files.createTempDirectory("nn-prune")
        Database(dir.resolve("nodenote.db")).use { db ->
            db.upsertMessage(Message("m1", "b1", author = "Ben", text = "x", seq = 1))
            val backupDir = dir.resolve("backups")
            Files.createDirectories(backupDir)
            repeat(5) { Files.createFile(backupDir.resolve("nodenote-2020010$it-000000-old.db")) }
            Backups(db, backupDir, keep = 3).use { it.snapshot("now") }
            val names = backupDir.listDirectoryEntries().map { it.fileName.toString() }
            assertEquals(3, names.size)
            assertTrue(names.any { it.endsWith("-now.db") })
        }
    }

    @Test
    fun refusesADatabaseFromANewerServer() {
        val file = Files.createTempDirectory("nn-schema").resolve("nodenote.db")
        Database(file).close()
        DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
            c.createStatement().use { it.execute("PRAGMA user_version = ${Database.SCHEMA_VERSION + 1}") }
        }
        assertFailsWith<IllegalStateException> { Database(file) }
    }
}
