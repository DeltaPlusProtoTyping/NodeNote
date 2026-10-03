package com.nodenote.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AreaTest {

    private fun card(id: String, x: Float, y: Float, private: Boolean = false) =
        Node(id, "Issue", "Card $id", x = x, y = y, isPrivate = private)

    // Two territories side by side, a small one nested in the left one, and a loose card.
    private val leadTime = Area("lead", "Lead time", x = 0f, y = 0f, width = 600f, height = 400f)
    private val firstParts = Area("fp", "First parts", x = 20f, y = 40f, width = 250f, height = 200f)
    private val pushback = Area("push", "Pushback", x = 700f, y = 0f, width = 400f, height = 400f, isPrivate = true)
    private val board = Project(
        id = "b", name = "Wagner",
        nodes = listOf(
            card("a", 40f, 80f), // in First parts (and so in Lead time)
            card("b", 350f, 200f), // in Lead time only
            card("c", 800f, 100f), // in Pushback
            card("d", 1300f, 100f), // loose
            card("e", 1300f, 300f, private = true), // private on its own
        ),
        areas = listOf(leadTime, firstParts, pushback),
    )

    private fun node(id: String) = board.nodes.single { it.id == id }

    @Test
    fun aCardBelongsToTheSmallestAreaAroundItsCentre() {
        assertEquals("fp", board.areaOf(node("a"))?.id)
        assertEquals("lead", board.areaOf(node("b"))?.id)
        assertEquals("push", board.areaOf(node("c"))?.id)
        assertNull(board.areaOf(node("d")))
        // Moving the big area takes the nested one's cards along too.
        assertEquals(setOf("a", "b"), board.cardsIn(leadTime).map { it.id }.toSet())
        assertEquals(listOf("fp"), board.areasInside(leadTime).map { it.id })
    }

    @Test
    fun aPrivateAreaMakesEverythingInItPrivate() {
        assertEquals(setOf("c", "e"), board.privateNodeIds())
        assertEquals(setOf("push"), board.privateAreaIds())
        // An area drawn inside a private one hides with it.
        val inner = Area("in", "Josh", x = 720f, y = 60f, width = 200f, height = 150f)
        assertEquals(setOf("push", "in"), board.copy(areas = board.areas + inner).privateAreaIds())
        // No private areas: just the cards marked private.
        assertEquals(setOf("e"), board.copy(areas = listOf(leadTime)).privateNodeIds())
    }

    @Test
    fun fittingHugsTheCardsWithRoomForTheName() {
        val fitted = Area("x", x = 0f, y = 0f, width = 0f, height = 0f).fittedTo(listOf(node("a"), node("b")))
        assertEquals(40f - Area.PADDING, fitted.x)
        assertEquals(80f - Area.PADDING - Area.TITLE_ROOM, fitted.y)
        assertEquals(350f + Node.DEFAULT_WIDTH + Area.PADDING, fitted.right)
        assertEquals(200f + Node.DEFAULT_HEIGHT + Area.PADDING, fitted.bottom)
        assertTrue(listOf("a", "b").all { id -> node(id).let { fitted.contains(it.centerX, it.centerY) } })
    }

    @Test
    fun areasTravelInPatchesAndSurviveTheOtherPersonsEdits() {
        // Ben moves an area and adds one; Nick, meanwhile, adds a card. Both land.
        val base = board
        val ben = base.copy(areas = base.areas.map { if (it.id == "lead") it.copy(x = 50f) else it } + Area("new", "Sales", x = 0f, y = 600f, width = 300f, height = 200f))
        val nick = base.copy(nodes = base.nodes + card("f", 100f, 700f))
        val patch = BoardDiff.diff(base, ben)
        assertEquals(setOf("lead", "new"), patch.upsertAreas.map { it.id }.toSet())
        val server = nick.applyPatch(patch)
        assertEquals(50f, server.areas.single { it.id == "lead" }.x)
        assertTrue(server.nodes.any { it.id == "f" } && server.areas.any { it.id == "new" })

        // Removing an area is a delete in the patch; its cards stay.
        val removed = server.copy(areas = server.areas.filterNot { it.id == "push" })
        val del = BoardDiff.diff(server, removed)
        assertEquals(listOf("push"), del.deleteAreas)
        val after = server.applyPatch(del)
        assertTrue(after.areas.none { it.id == "push" } && after.nodes.any { it.id == "c" })

        // Unsent local area edits are replayed on a newer board.
        val local = base.copy(areas = base.areas.map { if (it.id == "fp") it.copy(title = "First-part checks") else it })
        val rebased = rebase(local, base, nick)
        assertEquals("First-part checks", rebased.areas.single { it.id == "fp" }.title)
        assertTrue(rebased.nodes.any { it.id == "f" })
    }

    @Test
    fun anEmptyAreaPatchIsEmpty() {
        assertTrue(BoardDiff.diff(board, board).isEmpty)
        assertTrue(!BoardPatch(deleteAreas = listOf("x")).isEmpty)
    }
}
