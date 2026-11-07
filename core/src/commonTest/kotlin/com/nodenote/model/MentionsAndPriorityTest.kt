package com.nodenote.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun openWorkLeavesOutFinishedCardsAndTheGoal() {
        val n = Node("n", "Idea", "x", x = 0f, y = 0f, importance = 7)
        assertTrue(n.isOpenWork)
        assertFalse(n.copy(status = NodeStatus.Solved).isOpenWork)
        assertFalse(n.copy(type = "Goal").isOpenWork)
    }
}
