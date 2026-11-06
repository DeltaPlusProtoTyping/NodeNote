package com.nodenote.server

import com.nodenote.api.ApiJson
import com.nodenote.api.JoinRequest
import com.nodenote.api.LoginResponse
import com.nodenote.api.PatchResponse
import com.nodenote.api.RelatedDoc
import com.nodenote.api.RelatedMark
import com.nodenote.api.RelatedResults
import com.nodenote.api.RelatedStatus
import com.nodenote.model.BoardPatch
import com.nodenote.model.Edge
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A card's related notes: found in private folders, for one account, and only when it signed in itself. */
class RelatedNotesTest {

    private fun email(subject: String, from: String, date: String, body: String) =
        "---\nsubject: \"$subject\"\ndate: $date\nfrom: [\"$from\"]\n---\n\n$body\n"

    /** A data dir with related.txt, shared sign-in that opens real accounts, and a few notes. */
    private fun setUpNotes(root: Path, config: ServerConfig) {
        val mail = root.resolve("mail").also { Files.createDirectories(it) }
        Files.writeString(mail.resolve("a.md"), email("Sealing Plastics with Cerakote", "Mark Jones <mark@x.com>", "2026-07-03", "Oven space for the cerakote line is short."))
        Files.writeString(mail.resolve("b.md"), email("Re: Sealing Plastics with Cerakote", "Mark Jones <mark@x.com>", "2026-07-04", "Replying about cerakote and the oven."))
        Files.writeString(mail.resolve("c.md"), email("Thank you for your Cerakote order", "Shop <shop@x.com>", "2026-01-10", "Your cerakote order shipped."))
        Files.writeString(mail.resolve("d.md"), email("Lunch on Friday", "Sam <sam@x.com>", "2026-02-01", "Nothing to do with it."))
        val buys = root.resolve("buys.md")
        Files.writeString(buys, "# Purchases\n| Date | Vendor | What | Cost | Note |\n|---|---|---|---|---|\n| 2026-03-01 | Amazon | Cerakote gun cleaner | \$12.50 | [[n]] |\n")
        Files.createDirectories(config.dataDir)
        Files.writeString(config.relatedFile, "user=$TEST_USER\nnotes=Work email|$mail\nrows=Purchases|$buys\n")
        Database(config.databaseFile).use { db ->
            Accounts(db, groupPasswordFile = config.groupPasswordFile, groupOpensAccountsFile = config.groupOpensAccountsFile).run {
                setGroupPassword("group-secret")
                setGroupOpensAccounts(true)
            }
        }
    }

    @Test
    fun findsNotesOnlyForTheAccountThatSignedInItself() {
        val root = Files.createTempDirectory("nodenote-related-test")
        try {
            val config = ServerConfig(port = 0, dataDir = root.resolve("data"), vaultDir = root.resolve("vault"))
            setUpNotes(root, config)
            serverTest(root = root) { app ->
                val board = createBoard("B")
                val made: PatchResponse = patch(
                    board.id, 0,
                    BoardPatch(
                        upsertNodes = listOf(node("n1", "Mark needs more room for cerakote"), node("n2", "Oven")),
                        upsertEdges = listOf(Edge("e1", "n1", "n2")),
                    ),
                )
                assertTrue(made.rev > 0)
                val url = "/api/related/boards/${board.id}/nodes/n1"

                // The owner, signed in with their own password.
                assertEquals(RelatedStatus(available = true), client.get("/api/related/status").body())
                var results: RelatedResults = client.get(url).body()
                repeat(100) {
                    if (results.items.isEmpty()) {
                        delay(100)
                        results = client.get(url).body()
                    }
                }
                val titles = results.items.map { it.title }
                // One line for the thread, and the one that shares the linked card's "oven" comes first.
                assertEquals(1, titles.count { "Sealing Plastics" in it }, titles.toString())
                assertTrue("Sealing Plastics" in titles.first(), titles.toString())
                assertTrue("Thank you for your Cerakote order" in titles, titles.toString())
                assertFalse("Lunch on Friday" in titles, titles.toString())
                val first = results.items.first()
                assertEquals("Work email", first.source)
                assertEquals("Mark Jones", first.who)

                val doc: RelatedDoc = client.get("/api/related/docs/${first.id}").body()
                assertTrue("cerakote" in doc.text)
                assertFalse("subject:" in doc.text, "the note's header isn't part of its text")

                // Table rows are notes of their own.
                val row = client.get(url).body<RelatedResults>().items.map { it.source to it.who }
                assertTrue(("Purchases" to "Amazon") in row, row.toString())

                // Another account sees no sign of it.
                val nick = signIn("Nick", builder = app)
                assertEquals(RelatedStatus(), nick.get("/api/related/status").body())
                assertEquals(HttpStatusCode.NotFound, nick.get(url).status)
                assertEquals(HttpStatusCode.NotFound, nick.get("/api/related/docs/${first.id}").status)

                // The owner's name with the shared password opens the account, but not this.
                for (path in listOf("login", "join")) {
                    val body: Any = if (path == "join") JoinRequest(TEST_USER, "group-secret") else com.nodenote.api.LoginRequest(TEST_USER, "group-secret")
                    val login: LoginResponse = anonymous.post("/api/auth/$path") {
                        if (body is JoinRequest) json(body) else json(body as com.nodenote.api.LoginRequest)
                    }.body()
                    assertEquals(TEST_USER, login.username)
                    val shared = app.createClient {
                        install(ContentNegotiation) { json(ApiJson) }
                        defaultRequest { header(HttpHeaders.Authorization, "Bearer ${login.token}") }
                    }
                    assertEquals(RelatedStatus(needsOwnSignIn = true), shared.get("/api/related/status").body())
                    assertEquals(HttpStatusCode.Forbidden, shared.get(url).status)
                    assertEquals(HttpStatusCode.Forbidden, shared.get("/api/related/docs/${first.id}").status)
                    assertEquals(HttpStatusCode.Forbidden, shared.post("$url/mark") { json(RelatedMark(first.id, RelatedNotes.DISMISS)) }.status)
                }
                assertEquals(HttpStatusCode.Unauthorized, anonymous.get("/api/related/status").status)

                // Dismissing takes the whole thread off this card; pinning stays at the top.
                val thanks = results.items.first { "Thank you" in it.title }
                assertEquals(HttpStatusCode.NoContent, client.post("$url/mark") { json(RelatedMark(first.id, RelatedNotes.DISMISS)) }.status)
                assertEquals(HttpStatusCode.NoContent, client.post("$url/mark") { json(RelatedMark(thanks.id, RelatedNotes.PIN)) }.status)
                val after: RelatedResults = client.get(url).body()
                assertFalse(after.items.any { "Sealing Plastics" in it.title }, after.items.toString())
                assertTrue(after.items.first().pinned)
                assertEquals(thanks.id, after.items.first().id)
                // Clearing brings it back.
                client.post("$url/mark") { json(RelatedMark(first.id, "none")) }
                assertTrue(client.get(url).body<RelatedResults>().items.any { "Sealing Plastics" in it.title })

                // The notes stay out of the board and its vault mirror.
                val mirrored = root.resolve("vault").toFile().walkTopDown().filter { it.isFile && it.extension == "md" }.map { it.readText() }.joinToString()
                assertFalse("Sealing Plastics" in mirrored)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun withoutASettingsFileThereIsNoSignOfIt() = serverTest {
        assertEquals(RelatedStatus(), client.get("/api/related/status").body())
        val board = createBoard("B")
        assertEquals(HttpStatusCode.NotFound, client.get("/api/related/boards/${board.id}/nodes/n1").status)
    }

    @Test
    fun notesThatGoAwayLeaveTheIndexButAMissingFolderIsKept() {
        val root = Files.createTempDirectory("nodenote-related-index")
        try {
            val folder = root.resolve("notes").also { Files.createDirectories(it) }
            val note = folder.resolve("one.md")
            Files.writeString(note, email("Spindle report", "Sam <s@x.com>", "2026-05-01", "Numbers."))
            Files.writeString(folder.resolve("two.md"), email("Spindle plan", "Sam <s@x.com>", "2026-05-02", "Plans."))
            val config = RelatedConfig("Ben", listOf(RelatedConfig.Source("Notes", folder.toFile(), rows = false)))
            RelatedNotes(config, root.resolve("index.db")).use { notes ->
                notes.indexOnce()
                assertEquals(2, notes.search("b", "n", "Spindle", emptyList()).size)

                // Edited: found under its new words, not its old ones.
                Files.writeString(note, email("Coolant report", "Sam <s@x.com>", "2026-05-01", "Numbers and more of them."))
                notes.indexOnce()
                assertEquals(1, notes.search("b", "n", "Spindle", emptyList()).size)
                assertEquals(1, notes.search("b", "n", "Coolant", emptyList()).size)

                Files.delete(note)
                notes.indexOnce()
                val left = notes.search("b", "n", "Coolant", emptyList())
                assertTrue(left.isEmpty())
                assertNull(notes.doc(9_999))

                // The whole folder missing (a drive unplugged): nothing is forgotten.
                val moved = root.resolve("moved")
                Files.move(folder, moved)
                notes.indexOnce()
                assertEquals(1, notes.search("b", "n", "Spindle", emptyList()).size)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun aCardWithOnlyCommonWordsFindsNothing() {
        assertEquals(emptyList(), RelatedNotes.terms("is it the and"))
        assertEquals(listOf("server", "slow"), RelatedNotes.terms("Server is slow?"))
        // Quotes and search syntax in a title can't reach the query.
        assertEquals(listOf("drop", "table", "near"), RelatedNotes.terms("\"DROP\" TABLE* NEAR(x)"))
    }
}
