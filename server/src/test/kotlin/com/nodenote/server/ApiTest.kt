package com.nodenote.server

import com.nodenote.api.BoardSnapshot
import com.nodenote.api.BoardSummary
import com.nodenote.api.MessageUpdate
import com.nodenote.api.PatchRequest
import com.nodenote.api.SyncResponse
import com.nodenote.model.BoardPatch
import com.nodenote.model.Message
import com.nodenote.model.MessageKind
import com.nodenote.model.NodeStatus
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiTest {

    @Test
    fun createAndPatchBoard() = serverTest {
        val board = createBoard("Shop Floor")
        assertEquals(1, board.rev)
        assertEquals("Shop Floor", board.name)

        // Up-to-date client: the server result is base + patch, so no project comes back.
        val a = node("a", "Spindle warm-up").copy(status = NodeStatus.Open)
        val first = patch(board.id, baseRev = 1, BoardPatch(upsertNodes = listOf(a)))
        assertEquals(2, first.rev)
        assertNull(first.project)

        // Stale client (still on rev 1): gets the merged board, including the other edit.
        val b = node("b", "Haas VF-2")
        val second = patch(board.id, baseRev = 1, BoardPatch(upsertNodes = listOf(b)))
        assertEquals(3, second.rev)
        assertEquals(listOf("a", "b"), assertNotNull(second.project).nodes.map { it.id })

        // Stale client whose patch changes nothing: no rev bump, but it still needs the board.
        val noOpStale = patch(board.id, baseRev = 2, BoardPatch(upsertNodes = listOf(b)))
        assertEquals(3, noOpStale.rev)
        assertEquals(listOf("a", "b"), assertNotNull(noOpStale.project).nodes.map { it.id })

        // Up-to-date no-op: nothing to send.
        val noOp = patch(board.id, baseRev = 3, BoardPatch(upsertNodes = listOf(b)))
        assertEquals(3, noOp.rev)
        assertNull(noOp.project)

        val snapshot = client.get("/api/boards/${board.id}").body<BoardSnapshot>()
        assertEquals(3, snapshot.rev)
        assertEquals(board.id, snapshot.project.id)

        val list = client.get("/api/boards").body<List<BoardSummary>>()
        assertEquals(1, list.single().openCount)
        assertEquals(2, list.single().nodeCount)

        assertEquals(HttpStatusCode.NotFound, client.get("/api/boards/nope").status)
        assertEquals(
            HttpStatusCode.NotFound,
            client.post("/api/boards/nope/patch") { json(PatchRequest(1, "x", BoardPatch())) }.status,
        )
    }

    @Test
    fun messagesAreIdempotentAndEditable() = serverTest {
        val board = createBoard("Chat")
        val posted = post(board.id, message("m1", "ignored", "", "First thought", importance = 15))
        assertEquals(board.id, posted.boardId)
        assertEquals(12, posted.importance) // clamped to the top (urgent) level
        assertTrue(posted.createdAt > 0)
        assertTrue(posted.seq > 0)
        assertEquals(0, posted.editedAt)

        // Retried post (e.g. the response was lost): same stored message, no new seq.
        val again = post(board.id, message("m1", board.id, "", "Different text"))
        assertEquals(posted, again)

        val blank = client.post("/api/boards/${board.id}/messages") { json(message("m2", board.id, "", "   ")) }
        assertEquals(HttpStatusCode.BadRequest, blank.status)

        val edited = client.patch("/api/messages/m1") {
            json(MessageUpdate(text = "Second thought", kind = MessageKind.Decision))
        }.body<Message>()
        assertEquals("Second thought", edited.text)
        assertEquals(MessageKind.Decision, edited.kind)
        assertTrue(edited.editedAt > 0)
        assertTrue(edited.seq > posted.seq)

        val all = client.get("/api/boards/${board.id}/messages?since=0").body<List<Message>>()
        assertEquals(listOf(edited), all)
        assertEquals(emptyList(), client.get("/api/boards/${board.id}/messages?since=${edited.seq}").body<List<Message>>())

        val deleted = client.patch("/api/messages/m1") { json(MessageUpdate(deleted = true)) }.body<Message>()
        assertTrue(deleted.deleted)
        val tombstones = client.get("/api/boards/${board.id}/messages?since=${edited.seq}").body<List<Message>>()
        assertEquals(listOf(deleted), tombstones)

        assertEquals(HttpStatusCode.NotFound, client.patch("/api/messages/nope") { json(MessageUpdate(done = true)) }.status)

        // 11 and 12 are the urgent levels, above the old 1-10.
        val urgent = post(board.id, message("m3", board.id, "", "Coolant leak on the VF-2", importance = 11))
        assertEquals(11, urgent.importance)
    }

    @Test
    fun syncLongPollWakesOnNewMessage() = serverTest {
        val board = createBoard("Sync")

        // Behind on rev: answered immediately, with the board.
        val initial = client.get("/api/boards/${board.id}/sync?rev=0&seq=0&wait=25").body<SyncResponse>()
        assertEquals(1, initial.rev)
        assertNotNull(initial.project)

        coroutineScope {
            val started = System.nanoTime()
            val poll = async { client.get("/api/boards/${board.id}/sync?rev=1&seq=0&wait=25").body<SyncResponse>() }
            delay(500)
            assertFalse(poll.isCompleted, "sync should wait while nothing changed")

            val posted = post(board.id, message("m1", board.id, "", "Anyone there?"))
            val result = withTimeout(10_000) { poll.await() }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            assertTrue(elapsedMs < 10_000, "long-poll took $elapsedMs ms")
            assertEquals(listOf(posted), result.messages)
            assertEquals(posted.seq, result.seq)
            assertNull(result.project) // board rev didn't move
        }

        // Nothing new and wait=0: returns at once with the client's own cursor.
        val idle = client.get("/api/boards/${board.id}/sync?rev=1&seq=999&wait=0").body<SyncResponse>()
        assertEquals(999, idle.seq)
        assertTrue(idle.messages.isEmpty())
    }

    @Test
    fun dataSurvivesARestart() {
        val root = Files.createTempDirectory("nodenote-restart-test")
        try {
            var boardId = ""
            var firstSeq = 0L
            serverTest(root = root) {
                val board = createBoard("Persistent")
                boardId = board.id
                patch(board.id, 1, BoardPatch(upsertNodes = listOf(node("a", "Kept"))))
                firstSeq = post(board.id, message("m1", board.id, "a", "Still here?")).seq
            }
            serverTest(root = root) {
                val snapshot = client.get("/api/boards/$boardId").body<BoardSnapshot>()
                assertEquals(2, snapshot.rev)
                assertEquals(listOf("Kept"), snapshot.project.nodes.map { it.title })
                assertEquals(listOf("m1"), client.get("/api/boards/$boardId/messages").body<List<Message>>().map { it.id })
                // The global seq counter resumes where it left off.
                assertTrue(post(boardId, message("m2", boardId, "a", "Yes")).seq > firstSeq)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun corsGrantsOnlyLocalOriginsAndAllowsProxiedSameOrigin() = serverTest {
        val preflight = client.options("/api/boards") {
            header(HttpHeaders.Origin, "http://localhost:8080")
            header(HttpHeaders.AccessControlRequestMethod, "POST")
            header(HttpHeaders.AccessControlRequestHeaders, "Content-Type")
        }
        assertEquals(HttpStatusCode.OK, preflight.status)
        assertEquals("http://localhost:8080", preflight.headers[HttpHeaders.AccessControlAllowOrigin])

        val foreign = client.get("/api/boards") { header(HttpHeaders.Origin, "https://evil.example") }
        assertEquals(HttpStatusCode.Forbidden, foreign.status)

        // The web app served through `tailscale serve`: https origin, forwarded to plain http.
        val proxied = client.post("/api/boards") {
            header(HttpHeaders.Origin, "https://shop-pc.tail1234.ts.net")
            header(HttpHeaders.Host, "shop-pc.tail1234.ts.net")
            header(HttpHeaders.XForwardedHost, "shop-pc.tail1234.ts.net")
            header(HttpHeaders.XForwardedProto, "https")
            json(com.nodenote.api.CreateBoardRequest("From the browser"))
        }
        assertEquals(HttpStatusCode.OK, proxied.status)
    }
}
