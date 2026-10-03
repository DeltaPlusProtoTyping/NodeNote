package com.nodenote.server

import com.nodenote.api.BoardSummary
import com.nodenote.model.BoardPatch
import com.nodenote.model.Edge
import com.nodenote.model.MessageKind
import com.nodenote.model.NodeStatus
import com.nodenote.model.Project
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VaultTest {

    @Test
    fun nodeNotesLinkUpAndStaleFilesAreRemoved() = serverTest {
        val board = createBoard("Shop Floor")
        val warmup = node("a", "Spindle warm-up", type = "Issue").copy(status = NodeStatus.Open, importance = 6, owner = "Ben")
        val haas = node("b", "Haas VF-2 #3", type = "Machine", createdAt = 2)
        patch(board.id, 1, BoardPatch(upsertNodes = listOf(warmup, haas), upsertEdges = listOf(Edge("e", "b", "a", type = "Causes"))))
        val msg = post(
            board.id,
            message("m1", board.id, "a", "Idle 20 min, see [[haas vf-2 #3]] and [[Nowhere]]\nSecond line", importance = 8, linked = listOf("b"))
                .copy(kind = MessageKind.Issue),
        )
        rebuild()

        val folder = vaultDir.resolve("Shop Floor")
        val note = folder.resolve("Spindle warm-up.md").readText()
        assertTrue(note.startsWith("---\nnodenote: generated\n"), note)
        assertTrue("board: \"Shop Floor\"" in note)
        assertTrue("type: \"Issue\"" in note)
        assertTrue("status: Open" in note)
        assertTrue("tags: [nodenote, issue, open]" in note)
        // '#' can't appear in a file name, so the note is "Haas VF-2 3"; the link still reads as written.
        assertTrue("see [[Shop Floor/Haas VF-2 3|haas vf-2 #3]] and [[Nowhere]]" in note, note)
        assertTrue("Second line ^m-${msg.id}" in note, note)
        assertTrue("- ← [[Shop Floor/Haas VF-2 3|Haas VF-2 #3]] · Causes" in note, note)
        assertTrue("## Key details\n\n- **8** · Issue · Ben," in note, note)
        assertTrue("[[#^m-${msg.id}|↩]]" in note, note)

        // The linked node lists the message as a mention, pointing back at its block.
        val haasNote = folder.resolve("Haas VF-2 3.md").readText()
        assertTrue("## Mentioned in" in haasNote, haasNote)
        assertTrue("[[Shop Floor/Spindle warm-up#^m-${msg.id}|↩]]" in haasNote, haasNote)

        assertTrue(folder.resolve("Shop Floor.md").readText().contains("## Open work"))
        assertTrue(vaultDir.resolve("Boards.md").readText().contains("[[Shop Floor/Shop Floor\\|Shop Floor]]"))

        // Nothing changed → nothing rewritten (no churn for Obsidian Sync).
        val notePath = folder.resolve("Spindle warm-up.md")
        val stamp = Files.getLastModifiedTime(notePath)
        Thread.sleep(20)
        rebuild()
        assertEquals(stamp, Files.getLastModifiedTime(notePath))

        // A hand-written note in the same folder must survive everything below.
        val mine = folder.resolve("My notes.md").apply { writeText("# Mine\n") }

        // Renaming the node writes the new note and removes the old generated one.
        patch(board.id, 2, BoardPatch(upsertNodes = listOf(warmup.copy(title = "Warm-up cycle"))))
        rebuild()
        assertFalse(folder.resolve("Spindle warm-up.md").exists())
        assertTrue(folder.resolve("Warm-up cycle.md").readText().contains("^m-${msg.id}"))
        assertTrue(mine.exists())

        // Renaming the board keeps its folder, so links into it from elsewhere in the vault keep working.
        patch(board.id, 3, BoardPatch(name = "Machining"))
        rebuild()
        assertFalse(vaultDir.resolve("Machining").exists())
        assertTrue(folder.resolve("Shop Floor.md").readText().contains("# Machining"))
        assertTrue(vaultDir.resolve("Boards.md").readText().contains("[[Shop Floor/Shop Floor\\|Machining]]"))
        assertTrue(mine.exists())
    }

    @Test
    fun effortPrivacyAndWinsReachTheVault() = serverTest {
        val board = createBoard("Wins")
        val solved = node("a", "Decrease QC time", type = "Improvement")
            .copy(status = NodeStatus.Solved, solvedAt = 1_791_000_000_000, importance = 8, effort = 3)
        val secret = node("b", "Pushback", createdAt = 2).copy(isPrivate = true)
        val quick = node("c", "Label the bins", type = "Idea", createdAt = 3).copy(importance = 7, effort = 2)
        patch(board.id, 1, BoardPatch(upsertNodes = listOf(solved, secret, quick)))
        rebuild()

        val folder = vaultDir.resolve("Wins")
        val note = folder.resolve("Decrease QC time.md").readText()
        assertTrue("importance: 8\neffort: 3\nsolved: 2026-" in note, note)
        val secretNote = folder.resolve("Pushback.md").readText()
        assertTrue("private: true" in secretNote, secretNote)
        assertTrue("tags: [nodenote, topic, private]" in secretNote, secretNote)
        val index = folder.resolve("Wins.md").readText()
        assertTrue("## Wins\n\n- 2026-" in index, index)
        assertTrue("[[Wins/Pushback|Pushback]] · private" in index, index)
        // The map picture: embedded in the board note, the public nodes on it, the private one not.
        assertTrue("![[Wins/Wins map.svg]]" in index, index)
        val map = folder.resolve("Wins map.svg").readText()
        assertTrue(VaultMap.MARKER in map.lines().take(3), map.take(200))
        assertTrue("Label the bins" in map)
        assertFalse("Pushback" in map)
        assertTrue("## Quick wins\n\n- [[Wins/Label the bins|Label the bins]] · importance 7, effort 2" in index, index)
    }

    @Test
    fun changesAreMirroredAfterTheDebounce() = serverTest(vaultDebounceMillis = 100) {
        val board = createBoard("Debounced")
        patch(board.id, 1, BoardPatch(upsertNodes = listOf(node("a", "First node"))))
        val file = vaultDir.resolve("Debounced").resolve("First node.md")
        assertTrue(waitFor { file.exists() }, "node note was never written")

        post(board.id, message("m1", board.id, "", "Unfiled idea"))
        val inbox = vaultDir.resolve("Debounced").resolve("Inbox.md")
        assertTrue(waitFor { inbox.exists() && "^m-m1" in inbox.readText() }, "inbox was never written")
    }

    @Test
    fun namesAreSanitizedAndDeduplicated() {
        assertEquals("Haas VF-2 3", VaultNames.sanitize("Haas VF-2 #3"))
        assertEquals("a b c", VaultNames.sanitize("  a/b:\tc...  "))
        assertEquals("Untitled", VaultNames.sanitize("[[]]"))
        assertEquals("CON_", VaultNames.sanitize("CON"))
        assertEquals(80, VaultNames.sanitize("x".repeat(200)).length)

        val project = Project(
            id = "p", name = "Board",
            nodes = listOf(node("1", "Pump"), node("2", "pump", createdAt = 2), node("3", "Inbox"), node("4", "Board")),
        )
        assertEquals(
            mapOf("1" to "Pump", "2" to "pump (2)", "3" to "Inbox (2)", "4" to "Board (2)"),
            VaultNames.nodeFiles(project, "Board"),
        )

        fun summary(id: String, name: String, createdAt: Long) = BoardSummary(id, name, 1, createdAt, createdAt, 0, 0, 0)
        assertEquals(
            mapOf("old" to "Ops", "new" to "OPS (2)"),
            VaultNames.boardFolders(listOf(summary("new", "OPS", 2), summary("old", "Ops", 1))),
        )
        // A board keeps the folder it has, whatever it's called now; a newcomer can't take it.
        assertEquals(
            mapOf("a" to "Old name", "b" to "Old name (2)"),
            VaultNames.boardFolders(listOf(summary("a", "New name", 1), summary("b", "Old name", 2)), mapOf("a" to "Old name")),
        )
    }

    private suspend fun ServerFixture.rebuild() {
        assertEquals(HttpStatusCode.NoContent, client.post("/api/vault/rebuild").status)
    }

    private suspend fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(50)
        }
        return condition()
    }
}
