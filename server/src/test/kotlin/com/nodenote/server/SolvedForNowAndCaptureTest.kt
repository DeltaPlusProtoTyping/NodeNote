package com.nodenote.server

import com.nodenote.api.BoardSummary
import com.nodenote.api.CreateBoardRequest
import com.nodenote.api.PatchRequest
import com.nodenote.model.BoardPatch
import com.nodenote.model.Node
import com.nodenote.model.NodeStatus
import com.nodenote.model.Project
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SolvedForNowAndCaptureTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    private fun card(id: String, title: String, vararg tweaks: (Node) -> Node): Node =
        tweaks.fold(Node(id, "Issue", title, x = 0f, y = 0f, createdAt = 1, status = NodeStatus.Open)) { n, t -> t(n) }

    @Test
    fun theDigestListsWhatsDueAgainAndWhatsComingBack() {
        val project = Project(
            id = "b",
            name = "Shop",
            nodes = listOf(
                card("a", "Re-train second shift", { it.copy(status = NodeStatus.Solved, forNow = true, solvedAt = now - 40 * day, recheckAt = now - 2 * day) }),
                card("b", "Recalibrate the CMM", { it.copy(status = NodeStatus.Solved, forNow = true, solvedAt = now - 20 * day, recheckAt = now + 5 * day) }),
                card("c", "Quoting takes too long", { it.copy(importance = 9) }),
                card("d", "Spindle warm-up"),
                card("e", "Secret", { it.copy(importance = 12, isPrivate = true) }),
            ),
        )
        val summary = BoardSummary("b", "Shop", 1, 1, now, 5, 0, 2)
        val digest = MarkdownRenderer().renderDigest(listOf(VaultBoard(summary, project, emptyList()) to "Shop"), now)
        assertTrue(digest.startsWith("---\nnodenote: generated\n"), digest)
        assertTrue("## Due again\n\n- [[Shop/Re-train second shift|Re-train second shift]] · due since" in digest, digest)
        assertTrue("## Most important open work\n\n- **9** [[Shop/Quoting takes too long|Quoting takes too long]] · Open" in digest, digest)
        assertTrue("## Coming back in the next 14 days\n\n- " in digest && "Recalibrate the CMM" in digest, digest)
        assertTrue("Shop: 1 open card without an importance" in digest, digest)
        assertFalse("Secret" in digest, digest)

        // The node notes say so too.
        val files = MarkdownRenderer().renderBoard(VaultBoard(summary, project, emptyList()), "Shop", now)
        assertTrue("status: Due again" in files.getValue("Re-train second shift.md"))
        assertTrue("status: Solved for now" in files.getValue("Recalibrate the CMM.md"))
        assertTrue("recheck: " in files.getValue("Recalibrate the CMM.md"))
    }

    @Test
    fun captureLinesSkipInstructionsAndFollowBoardHeadings() {
        val lines = VaultCapture.captureLines(
            """
            |---
            |tags: [x]
            |---
            |# Capture
            |
            |> Anything written here goes to NodeNote.
            |- Coffee machine makes everyone late
            |  - [ ] Ask about a second   supplier
            |## Estimating
            |1. Quotes need the material cost
            |- [x] already done
            |---
            |
            """.trimMargin(),
        )
        assertEquals(
            listOf(
                VaultCapture.Line(null, "Coffee machine makes everyone late"),
                VaultCapture.Line(null, "Ask about a second supplier"),
                VaultCapture.Line("Estimating", "Quotes need the material cost"),
            ),
            lines,
        )
    }

    @Test
    fun captureSendsEachLineOnceAndWaitsForTypingToStop() = runBlocking {
        val dir = Files.createTempDirectory("nodenote-capture-test")
        Database(dir.resolve("db.sqlite")).use { db ->
            val store = BoardStore(db)
            val shop = store.create(CreateBoardRequest("Shop"))
            val estimating = store.create(CreateBoardRequest("Estimating"))
            val note = dir.resolve("vault").resolve("Capture.md")
            var clock = now
            val capture = VaultCapture(note, dir.resolve("sent.txt"), store, author = { "Ben" }, clock = { clock })

            assertEquals(0, capture.poll())
            assertTrue("# Capture" in note.readText(), "a missing note is created with instructions")

            note.writeText(note.readText() + "- Coffee machine makes everyone late\n## Estimating\n- Quotes need the material cost\n")
            Files.setLastModifiedTime(note, FileTime.fromMillis(now))
            clock = now + 10_000
            assertEquals(0, capture.poll(), "still being typed in")
            clock = now + 120_000
            assertEquals(2, capture.poll())
            assertEquals(0, capture.poll(), "sent once")

            val shopChat = store.messagesSince(shop.id, 0)!!.map { it.text to it.author }
            val estChat = store.messagesSince(estimating.id, 0)!!.map { it.text to it.author }
            // "Estimating" was made last, so it's the most recently changed board.
            assertEquals(listOf("Coffee machine makes everyone late" to "Ben", "Quotes need the material cost" to "Ben"), estChat + shopChat)
            assertTrue(store.messagesSince(estimating.id, 0)!!.all { it.nodeId == "" }, "lands in Unsorted")
        }
    }

    @Test
    fun movingCardsIsNotEditingThem() = runBlocking {
        val dir = Files.createTempDirectory("nodenote-activity-test")
        Database(dir.resolve("db.sqlite")).use { db ->
            val store = BoardStore(db)
            val board = store.create(CreateBoardRequest("Shop"))
            val a = card("a", "Parts take too long")
            var rev = store.patch(board.id, PatchRequest(board.rev, "Ben", BoardPatch(upsertNodes = listOf(a))))!!.rev
            rev = store.patch(board.id, PatchRequest(rev, "Ben", BoardPatch(upsertNodes = listOf(a.copy(x = 300f, height = 140f)))))!!.rev
            val described = store.activity(emptyList()).recent.single { !it.what.startsWith("chatted") }.what
            assertEquals("added 1 card", described)
            store.patch(board.id, PatchRequest(rev, "Ben", BoardPatch(upsertNodes = listOf(a.copy(x = 300f, height = 140f, importance = 7)))))
            assertEquals("added 1 card, changed 1 card", store.activity(emptyList()).recent.first().what)
        }
    }
}
