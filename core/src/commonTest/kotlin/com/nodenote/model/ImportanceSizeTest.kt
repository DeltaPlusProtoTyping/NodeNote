package com.nodenote.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ImportanceSizeTest {

    private fun card(importance: Int = 0, scale: Float = 1f) = Node(
        id = "n", type = "Issue", title = "Spindle warm-up", x = 100f, y = 50f,
        width = Node.DEFAULT_WIDTH * scale, height = Node.DEFAULT_HEIGHT * scale,
        importance = importance, scale = scale,
    )

    @Test
    fun theOldSizePresetsAreImportanceLevels() {
        assertEquals(1, importanceForScale(0.8f)) // Small
        assertEquals(3, importanceForScale(1f)) // Normal
        assertEquals(7, importanceForScale(1.5f)) // Large
        assertEquals(10, importanceForScale(2.2f)) // Huge
        assertEquals(1f, scaleForImportance(0)) // not rated: normal size
        assertEquals(2.2f, scaleForImportance(10))
    }

    @Test
    fun moreImportantIsAlwaysBiggerAndUrgentIsBiggest() {
        val sizes = (1..MAX_IMPORTANCE).map(::scaleForImportance)
        assertTrue(sizes.zipWithNext().all { (a, b) -> b > a }, "$sizes")
        assertTrue(scaleForImportance(11) > scaleForImportance(10))
        for (level in 1..MAX_IMPORTANCE) assertEquals(level, importanceForScale(scaleForImportance(level)))
    }

    @Test
    fun aRatedCardTakesItsLevelsSizeAroundItsCentre() {
        val rated = card(importance = 8)
        val sized = rated.sizedByImportance()
        assertEquals(scaleForImportance(8), sized.scale)
        assertEquals(Node.DEFAULT_WIDTH * sized.scale, sized.width, 0.01f)
        assertEquals(rated.centerX, sized.centerX, 0.01f)
        assertEquals(rated.centerY, sized.centerY, 0.01f)
        assertEquals(8, sized.importance)
    }

    @Test
    fun aCardSizedByHandGetsTheImportanceOfItsSize() {
        val hub = card(scale = 1.5f) // "Large" before sizes meant importance
        val sized = hub.sizedByImportance()
        assertEquals(7, sized.importance)
        assertEquals(1.5f, sized.scale)
        assertEquals(hub.width, sized.width)
        assertEquals(hub.x, sized.x)
    }

    @Test
    fun cardsAlreadyInStepAreLeftAlone() {
        val board = Project(
            id = "b", name = "Board",
            nodes = listOf(card(), card(importance = 10, scale = 2.2f).copy(id = "goal"), card(importance = 3).copy(id = "c")),
        )
        assertSame(board, board.withSizesByImportance())
    }

    @Test
    fun puttingABoardInStepTwiceChangesNothingMore() {
        val board = Project(
            id = "b", name = "Board",
            nodes = listOf(card(importance = 5), card(scale = 2.2f).copy(id = "hub"), card(importance = 12).copy(id = "u")),
        )
        val once = board.withSizesByImportance()
        assertSame(once, once.withSizesByImportance())
        assertEquals(listOf(5, 10, 12), once.nodes.map { it.importance })
    }
}
