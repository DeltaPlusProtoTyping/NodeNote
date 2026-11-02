package com.nodenote.server

import com.nodenote.api.Activity
import com.nodenote.api.ApiJson
import com.nodenote.api.BoardSnapshot
import com.nodenote.api.HistoryEntry
import com.nodenote.api.JoinRequest
import com.nodenote.api.MembersRequest
import com.nodenote.api.PatchRequest
import com.nodenote.api.Reply
import com.nodenote.api.ServerInfo
import com.nodenote.model.BoardDiff
import com.nodenote.model.BoardPatch
import com.nodenote.model.Edge
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Board versions, the activity view, replies, member names and the APK download. */
class TeamFeaturesTest {

    @Test
    fun aBoardCanBePutBackAndTheRestoreUndone() = serverTest {
        val board = createBoard("Plans")
        patch(board.id, 1, BoardPatch(upsertNodes = listOf(node("a", "Keep me"), node("b", "Me too"))))
        // Someone deletes a card (the first change in this window saved the board before it).
        val wrecked = patch(board.id, 2, BoardPatch(deleteNodes = listOf("b")))
        val history: List<HistoryEntry> = client.get("/api/boards/${board.id}/history").body()
        assertEquals(1, history.size)
        assertEquals(0, history.single().cards, "saved before the first change: the empty board")

        val now: BoardSnapshot = client.get("/api/boards/${board.id}").body()
        val old: BoardSnapshot = client.get("/api/boards/${board.id}/history/${history.single().id}").body()
        assertEquals(0, old.project.nodes.size)

        // Restore = an ordinary patch from now to then, marked as a restore.
        val restore = client.post("/api/boards/${board.id}/patch") {
            json(PatchRequest(wrecked.rev, "Ben", BoardDiff.diff(now.project, old.project), restoredFrom = history.single().id))
        }.body<com.nodenote.api.PatchResponse>()
        assertEquals(wrecked.rev + 1, restore.rev)
        val after: List<HistoryEntry> = client.get("/api/boards/${board.id}/history").body()
        assertEquals(2, after.size, "the board as it was before the restore was kept")
        assertEquals(1, after.first().cards)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/boards/${board.id}/history/999").status)
    }

    @Test
    fun activityShowsWhoChangedWhatAndChatted() = serverTest { builder ->
        val board = createBoard("Plans")
        patch(board.id, 1, BoardPatch(upsertNodes = listOf(node("a", "Parts"), node("b", "Machines"))))
        patch(board.id, 2, BoardPatch(upsertEdges = listOf(Edge(id = "e", fromNodeId = "a", toNodeId = "b"))))
        val nick = signIn("Nick", builder = builder)
        nick.post("/api/boards/${board.id}/messages") { json(message("m1", board.id, "a", "Spindle warm-up takes 20 min")) }

        val activity: Activity = client.get("/api/activity").body()
        assertEquals(setOf("Ben", "Nick"), activity.people.map { it.name }.toSet())
        assertTrue(activity.people.single { it.name == "Nick" }.lastChange > 0)
        val ben = activity.recent.single { it.author == "Ben" }
        assertEquals("added 2 cards, linked 1 pair of cards", ben.what, "one person's edits close together are one item")
        assertEquals("chatted on Parts: Spindle warm-up takes 20 min", activity.recent.single { it.author == "Nick" }.what)
    }

    @Test
    fun repliesAreOtherPeoplesChatsOnMyCards() = serverTest { builder ->
        val board = createBoard("Plans")
        patch(board.id, 1, BoardPatch(upsertNodes = listOf(node("a", "Mine").copy(createdBy = "Ben"), node("b", "Nick's").copy(createdBy = "Nick"), node("c", "Chatted"))))
        post(board.id, message("m0", board.id, "c", "I said something here"))
        val nick = signIn("Nick", builder = builder)
        for ((id, card) in listOf("m1" to "a", "m2" to "b", "m3" to "c")) {
            nick.post("/api/boards/${board.id}/messages") { json(message(id, board.id, card, "Nick on $card")) }
        }
        val all: List<Reply> = client.get("/api/replies?since=0").body()
        assertEquals(listOf("Nick on a", "Nick on c"), all.map { it.text })
        assertEquals("Mine", all.first().nodeTitle)
        val newer: List<Reply> = client.get("/api/replies?since=${all.first().seq}").body()
        assertEquals(listOf("Nick on c"), newer.map { it.text })
        assertTrue(nick.get("/api/replies?since=0").body<List<Reply>>().none { it.author == "Nick" })
    }

    @Test
    fun theGroupPasswordListsMembersAndTheApkDownloads() {
        val dir = Files.createTempDirectory("nodenote-team-test")
        try {
            val config = ServerConfig(port = 0, dataDir = dir.resolve("data"), vaultDir = dir.resolve("vault"))
            Database(config.databaseFile).use { db ->
                val accounts = Accounts(db, groupPasswordFile = config.groupPasswordFile)
                accounts.addUser("Ben", TEST_PASSWORD)
                accounts.setGroupPassword("group-secret")
            }
            testApplication {
                application { nodeNoteModule(config, vaultDebounceMillis = 60_000) }
                val client = createClient { install(ContentNegotiation) { json(ApiJson) } }
                assertFalse(client.get("/api/info").body<ServerInfo>().androidApp)
                assertEquals(HttpStatusCode.NotFound, client.get("/download/NodeNote.apk").status)

                client.post("/api/auth/join") { json(JoinRequest("Sam", "group-secret")) }
                assertEquals(HttpStatusCode.Unauthorized, client.post("/api/auth/members") { json(MembersRequest("nope")) }.status)
                val names: List<String> = client.post("/api/auth/members") { json(MembersRequest("group-secret")) }.body()
                assertEquals(listOf("Sam"), names, "members only, never the real accounts")

                val apk = byteArrayOf(80, 75, 3, 4, 1, 2, 3)
                Files.createDirectories(config.androidApk.parent)
                Files.write(config.androidApk, apk)
                assertTrue(client.get("/api/info").body<ServerInfo>().androidApp)
                val download = client.get("/download/NodeNote.apk")
                assertEquals(HttpStatusCode.OK, download.status)
                assertContentEquals(apk, download.bodyAsBytes())
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
