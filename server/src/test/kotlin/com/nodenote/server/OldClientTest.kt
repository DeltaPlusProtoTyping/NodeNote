package com.nodenote.server

import com.nodenote.api.ApiJson
import com.nodenote.api.BoardSnapshot
import com.nodenote.model.BoardPatch
import com.nodenote.model.Node
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A browser tab opened before an update must not be able to reset fields it doesn't know about. */
class OldClientTest {

    @Test
    fun fieldsAnOldClientDoesNotSendAreKept() = serverTest {
        val board = createBoard("Safety")
        val node = node("a", "Pushback").copy(isPrivate = true, effort = 4, scale = 1.5f, width = 285f, height = 138f, keywords = "trust")
        patch(board.id, 1, BoardPatch(upsertNodes = listOf(node)))

        // What a tab from before these fields existed sends after dragging the node: everything it knows, nothing else.
        val full = ApiJson.encodeToJsonElement(Node.serializer(), node.copy(x = 50f)).jsonObject
        val oldShape = JsonObject(full - setOf("private", "effort", "scale", "keywords", "solvedAt"))
        val body = buildJsonObject {
            put("baseRev", 2)
            put("author", "old tab")
            putJsonObject("patch") { putJsonArray("upsertNodes") { add(oldShape) } }
        }
        val response = client.post("/api/boards/${board.id}/patch") {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val stored = client.get("/api/boards/${board.id}").body<BoardSnapshot>().project.nodes.single()
        assertEquals(50f, stored.x) // the move went through
        assertTrue(stored.isPrivate)
        assertEquals(4, stored.effort)
        assertEquals(1.5f, stored.scale)
        assertEquals("trust", stored.keywords)

        // A current client sends every field, so it can still clear one.
        patch(board.id, 3, BoardPatch(upsertNodes = listOf(stored.copy(isPrivate = false))))
        val after = client.get("/api/boards/${board.id}").body<BoardSnapshot>().project.nodes.single()
        assertFalse(after.isPrivate)
        assertEquals(4, after.effort)
    }

    @Test
    fun malformedPatchesAreRejected() = serverTest {
        val board = createBoard("Bad")
        val response = client.post("/api/boards/${board.id}/patch") {
            contentType(ContentType.Application.Json)
            setBody("""{"baseRev": 1, "author": "x", "patch": [1, 2]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
