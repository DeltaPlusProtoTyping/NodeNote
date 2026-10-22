package com.nodenote.local

import com.nodenote.api.CreateBoardRequest
import com.nodenote.api.MessageUpdate
import com.nodenote.api.PatchRequest
import com.nodenote.model.BoardDiff
import com.nodenote.model.Message
import com.nodenote.model.Node
import com.nodenote.model.Project
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceBoardsTest {

    /** A device's storage; [full] makes every save fail, like a browser out of space. */
    private class MemoryStore : DeviceStore {
        val files = LinkedHashMap<String, String>()
        var full = false
        override fun read(key: String) = files[key]
        override fun write(key: String, value: String): Boolean {
            if (full) return false
            files[key] = value
            return true
        }
    }

    private var now = 1_000L
    private fun boards(store: DeviceStore) = DeviceBoards(store, clock = { now++ })
    private fun card(id: String, title: String) = Node(id = id, type = "Issue", title = title, x = 0f, y = 0f)

    private suspend fun DeviceBoards.addCard(boardId: String, node: Node) {
        val before = snapshot(boardId)!!
        val after = before.project.copy(nodes = before.project.nodes + node)
        patch(boardId, PatchRequest(before.rev, "Me", BoardDiff.diff(before.project, after)))
    }

    @Test
    fun boardsAndChatsAreStillThereWhenTheAppOpensAgain() = runTest {
        val store = MemoryStore()
        val first = boards(store)
        val board = first.create(CreateBoardRequest("Shop floor"))
        first.addCard(board.id, card("n1", "Spindle warm-up"))
        val posted = first.postMessage(board.id, Message(id = "m1", boardId = "", nodeId = "n1", author = "x", text = "Takes 20 min"), author = "Ben")!!
        assertEquals("Ben", posted.author)

        val reopened = boards(store)
        val snap = reopened.snapshot(board.id)!!
        assertEquals(listOf("Spindle warm-up"), snap.project.nodes.map { it.title })
        assertEquals(2, snap.rev)
        assertEquals(listOf("Takes 20 min"), reopened.messagesSince(board.id, 0)!!.map { it.text })
        // Sequence numbers carry on rather than starting over.
        val next = reopened.postMessage(board.id, Message(id = "m2", boardId = board.id, author = "", text = "Second"), author = "Ben")!!
        assertTrue(next.seq > posted.seq)
    }

    @Test
    fun aChangeThatCantBeSavedIsRefusedAndNothingIsLost() = runTest {
        val store = MemoryStore()
        val device = boards(store)
        val board = device.create(CreateBoardRequest("Shop floor"))
        device.addCard(board.id, card("n1", "Kept"))

        store.full = true
        assertFailsWith<DeviceSaveException> { device.addCard(board.id, card("n2", "Not saved yet")) }
        assertFailsWith<DeviceSaveException> {
            device.postMessage(board.id, Message(id = "m1", boardId = board.id, author = "", text = "Not saved yet"), author = "Ben")
        }
        // The board in memory is still what's on the device.
        assertEquals(listOf("Kept"), device.snapshot(board.id)!!.project.nodes.map { it.title })
        assertTrue(device.messagesSince(board.id, 0)!!.isEmpty())

        // Space again: the same edit goes through on retry.
        store.full = false
        device.addCard(board.id, card("n2", "Saved now"))
        assertEquals(listOf("Kept", "Saved now"), boards(store).snapshot(board.id)!!.project.nodes.map { it.title })
    }

    @Test
    fun aSaveCutShortFallsBackToTheVersionBefore() = runTest {
        val store = MemoryStore()
        val device = boards(store)
        val board = device.create(CreateBoardRequest("Shop floor"))
        device.addCard(board.id, card("n1", "First"))
        device.addCard(board.id, card("n2", "Second"))
        store.files["board-${board.id}.json"] = "{\"id\":\"" // the app died mid-write

        val nodes = boards(store).snapshot(board.id)!!.project.nodes.map { it.title }
        assertEquals(listOf("First"), nodes)
    }

    @Test
    fun messagesFollowTheServersRules() = runTest {
        val device = boards(MemoryStore())
        val board = device.create(CreateBoardRequest("", project = Project(id = "x", name = "Imported")))
        assertEquals("Imported", board.name)
        val m = Message(id = "m1", boardId = board.id, author = "", text = "Hello", importance = 15)
        val posted = device.postMessage(board.id, m, author = "Ben")!!
        assertEquals(12, posted.importance)
        assertEquals(posted, device.postMessage(board.id, m.copy(text = "retry"), author = "Ben")) // retried post
        assertFailsWith<IllegalArgumentException> {
            device.postMessage(board.id, Message(id = "m2", boardId = board.id, author = "", text = "  "), author = "Ben")
        }
        val gone = device.updateMessage("m1", MessageUpdate(deleted = true))!!
        assertTrue(gone.deleted && gone.seq > posted.seq)
        assertNull(device.updateMessage("nope", MessageUpdate(done = true)))
        assertNull(device.snapshot("nope"))
    }

    @Test
    fun syncHandsOverWhatChangedSinceTheGivenRevision() = runTest {
        val device = boards(MemoryStore())
        val board = device.create(CreateBoardRequest("Shop floor"))
        val idle = device.sync(board.id, rev = 1, seq = 0, waitMillis = 0)!!
        assertNull(idle.project)
        device.addCard(board.id, card("n1", "New"))
        val changed = device.sync(board.id, rev = 1, seq = 0, waitMillis = 0)!!
        assertNotNull(changed.project)
        assertEquals(2, changed.rev)
    }

    @Test
    fun renamingTheUserCarriesOverToTheirCardsAndMessages() = runTest {
        val store = MemoryStore()
        val device = boards(store)
        val board = device.create(CreateBoardRequest("Shop floor"))
        device.addCard(board.id, card("n1", "Mine").copy(createdBy = "Me", owner = "Me"))
        device.postMessage(board.id, Message(id = "m1", boardId = board.id, author = "", text = "Hi"), author = "Me")
        device.renameAuthor("Me", "Ben")
        val reopened = boards(store)
        assertEquals("Ben", reopened.snapshot(board.id)!!.project.nodes.single().createdBy)
        assertEquals("Ben", reopened.messagesSince(board.id, 0)!!.single().author)
    }
}
