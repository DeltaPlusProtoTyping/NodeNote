package com.nodenote.export

import com.nodenote.model.Edge
import com.nodenote.model.Node
import com.nodenote.model.Project
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapSceneTest {

    private val goal = Node("g", "Goal", "20M in two years", x = 0f, y = 0f, importance = 10)
    private val long = Node(
        "l", "Improvement",
        "\"LTL\" jobs require manual conversion to mcMaster PN and manual totaling for purchasing",
        x = 300f, y = 0f, effort = 3, importance = 7,
    )
    private val secret = Node("s", "Topic", "Pushback from Kurt", x = 0f, y = 300f, isPrivate = true)
    private val board = Project(
        id = "b", name = "Wagner",
        nodes = listOf(goal, long, secret),
        edges = listOf(Edge("e1", "l", "g"), Edge("e2", "s", "g")),
    )

    private fun texts(scene: Scene) = scene.items.filterIsInstance<SceneText>().map { it.text }

    @Test
    fun privateNodesAreLeftOutUnlessAsked() {
        val without = assertNotNull(MapSceneBuilder.build(board, MapSceneOptions(showLegend = false, showTitleBlock = false))).scene
        assertFalse(texts(without).any { "Pushback" in it })
        // Only the goal ↔ LTL connection is drawn.
        assertEquals(1, without.items.count { it is SceneLine })

        val with = assertNotNull(MapSceneBuilder.build(board, MapSceneOptions(includePrivate = true, showLegend = false, showTitleBlock = false))).scene
        assertTrue(texts(with).any { "Pushback" in it })
        assertTrue("PRIVATE" in texts(with))
    }

    @Test
    fun theWholeTitleIsThereAndTheCardGrowsForIt() {
        val scene = assertNotNull(MapSceneBuilder.build(board, MapSceneOptions(showLegend = false, showTitleBlock = false))).scene
        val titleLines = texts(scene).filter { it.isNotBlank() && it in long.title }
        assertTrue(titleLines.size >= 3, "title should wrap over several lines: $titleLines")
        assertEquals(long.title, titleLines.joinToString(" "))
        // The LTL card is taller than the default 92 to fit them.
        val card = scene.items.filterIsInstance<SceneRect>().filter { it.radius == 10f }.maxBy { it.height }
        assertTrue(card.height > Node.DEFAULT_HEIGHT, "card height ${card.height}")
        // Ratings show up on the card.
        assertTrue("E3" in texts(scene))
        assertTrue("7" in texts(scene))
    }

    @Test
    fun scaledNodesDrawBigger() {
        val big = goal.copy(scale = 2f, width = Node.DEFAULT_WIDTH * 2f, height = Node.DEFAULT_HEIGHT * 2f)
        val scene = assertNotNull(
            MapSceneBuilder.build(board.copy(nodes = listOf(big)), MapSceneOptions(showLegend = false, showTitleBlock = false)),
        ).scene
        val title = scene.items.filterIsInstance<SceneText>().first { it.text == "20M in two years" }
        assertEquals(26f, title.size) // 13 × 2
    }

    @Test
    fun plainLinksHaveNoArrowsTypedOnesDo() {
        val typed = board.copy(edges = listOf(Edge("e1", "l", "g", type = "Causes")))
        val plain = assertNotNull(MapSceneBuilder.build(board, MapSceneOptions(showLegend = false, showTitleBlock = false))).scene
        val arrowed = assertNotNull(MapSceneBuilder.build(typed, MapSceneOptions(showLegend = false, showTitleBlock = false))).scene
        assertEquals(0, plain.items.count { it is ScenePolygon })
        assertEquals(1, arrowed.items.count { it is ScenePolygon })
    }

    @Test
    fun overlaysAndEmptyBoards() {
        val map = assertNotNull(MapSceneBuilder.build(board, MapSceneOptions(subtitle = "2 nodes · Oct 2, 2026")))
        assertNotNull(map.legendRect)
        assertNotNull(map.titleRect)
        assertTrue("Wagner" in texts(map.scene))
        assertTrue("2 nodes · Oct 2, 2026" in texts(map.scene))
        assertNull(MapSceneBuilder.build(Project("e", "Empty"), MapSceneOptions()))
    }
}
