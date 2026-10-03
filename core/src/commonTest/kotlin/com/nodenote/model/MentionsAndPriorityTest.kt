package com.nodenote.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MentionsAndPriorityTest {

    @Test
    fun mentionsAreFoundAndEmailsAreNot() {
        assertEquals(listOf("Kurt", "Rivva"), mentionsIn("ask @Kurt, then @Rivva."))
        assertEquals(emptyList(), mentionsIn("mail ben@example.com about it"))
        assertEquals(listOf("Sam"), mentionsIn("@Sam"))
        assertEquals(emptyList(), mentionsIn("@ alone, @1st"))
    }

    @Test
    fun aNameCountsWithOrWithoutTheAt() {
        assertTrue(mentionsWord("Pushback from Kurt", "kurt"))
        assertTrue(mentionsWord("ask @Kurt first", "Kurt"))
        assertFalse(mentionsWord("Kurtis said", "Kurt"))
        assertFalse(mentionsWord("", "Kurt"))
    }

    @Test
    fun pickChartBuckets() {
        fun n(importance: Int, effort: Int) = Node("n", "Idea", "x", x = 0f, y = 0f, importance = importance, effort = effort)
        assertNull(n(7, 0).pick)
        assertEquals(Pick.QuickWin, n(6, 4).pick)
        assertEquals(Pick.BigBet, n(9, 8).pick)
        assertEquals(Pick.FillIn, n(3, 2).pick)
        assertEquals(Pick.Rethink, n(5, 5).pick)
        assertFalse(n(7, 2).copy(status = NodeStatus.Solved).isOpenWork)
        assertFalse(n(7, 2).copy(type = "Goal").isOpenWork)
    }
}
