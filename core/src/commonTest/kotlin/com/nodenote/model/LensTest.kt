package com.nodenote.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LensTest {

    private val noFix = Node("i1", "Issue", "Quoting takes too long", x = 0f, y = 0f, status = NodeStatus.Open)
    private val fixed = Node("i2", "Issue", "First part waits for QC", x = 0f, y = 0f, status = NodeStatus.Open)
    private val backwards = Node("i3", "Issue", "Server is slow", x = 0f, y = 0f, status = NodeStatus.Open)
    private val solved = Node("i4", "Issue", "Clutter", x = 0f, y = 0f, status = NodeStatus.Solved)
    private val fix = Node("f1", "Improvement", "Allocate a QC member to first parts", x = 0f, y = 0f, owner = "Nick")
    private val idea = Node("f2", "Idea", "New server, ask @Kurt", x = 0f, y = 0f, importance = 8, effort = 3)
    private val board = Project(
        id = "b", name = "Wagner",
        nodes = listOf(noFix, fixed, backwards, solved, fix, idea),
        edges = listOf(
            Edge("e1", "f1", "i2", type = "Solves"), // fix → problem, the way it's drawn
            Edge("e2", "i3", "f2", type = "Solves"), // drawn the other way round still counts
            Edge("e3", "f1", "i1"), // a plain link isn't a fix
        ),
    )

    @Test
    fun noFixYetFindsTheIssuesWithNothingSolvingThem() {
        assertEquals(setOf("i2", "i3"), board.fixedIssueIds())
        val lens = Lens(board, ColorBy.Gaps)
        assertEquals(0xFFE2606B, lens.colorOf(noFix)) // red: no fix yet
        assertEquals(0xFFE5B25B, lens.colorOf(fixed)) // amber: a fix is on the map
        assertEquals(0xFFE5B25B, lens.colorOf(backwards))
        assertEquals(0xFF53C27C, lens.colorOf(solved)) // green
        assertNull(lens.colorOf(fix)) // not an issue: left plain
        assertEquals(listOf("No fix yet", "Fix on the map", "Solved", "Not an open issue"), lens.legend(board.nodes).map { it.label })
    }

    @Test
    fun personIsTheOwnerElseTheFirstAtName() {
        assertEquals("Nick", fix.person())
        assertEquals("Kurt", idea.person())
        assertEquals("", noFix.person())
        val lens = Lens(board, ColorBy.Person)
        assertEquals(personColor("Nick"), lens.colorOf(fix))
        assertNull(lens.colorOf(noFix))
        // Most-named first (ties in board order), then the plain cards.
        assertEquals(listOf("Nick", "Kurt", "Nobody named"), lens.legend(board.nodes).map { it.label })
    }

    @Test
    fun importanceAndEffortUseTheBadgeScales() {
        assertEquals(importanceArgb(8), Lens(board, ColorBy.Importance).colorOf(idea))
        assertNull(Lens(board, ColorBy.Importance).colorOf(noFix))
        assertEquals(effortArgb(3), Lens(board, ColorBy.Effort).colorOf(idea))
        val legend = Lens(board, ColorBy.Importance).legend(board.nodes).map { it.label }
        assertEquals(listOf("7–8", "Not rated"), legend)
        // The type lens leaves every card as it is.
        assertTrue(board.nodes.all { Lens(board, ColorBy.Type).colorOf(it) == null })
    }

    @Test
    fun statusUsesTheStatusColors() {
        val lens = Lens(board, ColorBy.Status)
        assertEquals(NodeStatus.Open.colorArgb, lens.colorOf(noFix))
        assertNull(lens.colorOf(fix))
        assertEquals(listOf("Open", "Solved", "No status"), lens.legend(board.nodes).map { it.label })
    }
}
