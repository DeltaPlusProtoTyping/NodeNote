package com.nodenote.sort

import com.nodenote.model.Edge
import com.nodenote.model.Message
import com.nodenote.model.Node
import com.nodenote.model.Project
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NodeMatcherTest {

    private fun node(id: String, title: String, type: String = "Topic", keywords: String = "", description: String = "") =
        Node(id, type, title, description = description, x = 0f, y = 0f, keywords = keywords)

    private val board = Project(
        id = "b",
        name = "Shop",
        nodes = listOf(
            node("goal", "20M in two years", type = "Goal"),
            node("ineff", "Inefficiencies"),
            node("server", "Server is slow"),
            node("vf2", "Machine state monitoring", keywords = "VF-2, spindle, warm-up"),
            node("people", "Employees aren't motivated"),
        ),
        edges = listOf(
            Edge("e1", "ineff", "goal"),
            Edge("e2", "server", "ineff"),
            Edge("e3", "vf2", "ineff"),
            Edge("e4", "people", "goal"),
        ),
    )

    private fun top(matcher: NodeMatcher, text: String) = matcher.suggest(text).firstOrNull()?.nodeId

    @Test
    fun matchesTitlesAndKeywords() {
        val m = NodeMatcher(board, emptyList())
        assertEquals("server", top(m, "the server was really slow this morning"))
        assertEquals("vf2", top(m, "vf2 spindle warmup took forever"))
        assertEquals("people", top(m, "motivated employees would fix half of this"))
    }

    @Test
    fun learnsFromFiledMessages() {
        val before = NodeMatcher(board, emptyList())
        assertTrue(before.suggest("network drive lagging when opening drawings").isEmpty())
        val filed = listOf(
            Message("m1", "b", nodeId = "server", author = "Ben", text = "network drive lagging again"),
            Message("m2", "b", nodeId = "server", author = "Nick", text = "opening drawings takes a minute"),
        )
        val after = NodeMatcher(board, filed)
        assertEquals("server", top(after, "network drive lagging when opening drawings"))
    }

    @Test
    fun linkedNodesComeFirstAndNoiseGivesNothing() {
        val m = NodeMatcher(board, emptyList())
        assertEquals("people", m.suggest("see [[Employees aren't motivated]] about the server", linkedNodeIds = listOf("people")).first().nodeId)
        assertTrue(m.suggest("lol ok yeah").isEmpty())
    }

    @Test
    fun hierarchyGrowsFromTheGoal() {
        val h = BoardHierarchy(board)
        assertEquals("ineff", h.parents["server"])
        assertEquals("goal", h.parents["ineff"])
        assertEquals(null, h.parents["goal"])
    }

    @Test
    fun tokenizerJoinsCompoundsAndStems() {
        val tokens = NodeMatcher.tokenize("VF-2 warm-up isn't warming the machines")
        assertTrue("vf2" in tokens && "warmup" in tokens && "warm" in tokens && "machine" in tokens, tokens.toString())
        assertTrue("isnt" !in tokens && "the" !in tokens, tokens.toString())
    }
}
