package com.nodenote.export

import com.nodenote.model.Area
import com.nodenote.model.ColorBy
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
    fun areasAreDrawnWithTheirNamesAndAPrivateOneTakesItsCardsAlong() {
        val sales = Area("a1", "Sales pipeline", x = -60f, y = -80f, width = 600f, height = 220f)
        val push = Area("a2", "Pushback", x = -40f, y = 260f, width = 300f, height = 200f, isPrivate = true)
        val withAreas = board.copy(nodes = board.nodes.map { it.copy(isPrivate = false) }, areas = listOf(sales, push))
        val options = MapSceneOptions(showLegend = false, showTitleBlock = false)

        val scene = assertNotNull(MapSceneBuilder.build(withAreas, options)).scene
        assertTrue("Sales pipeline" in texts(scene))
        // The private area hides, and so does the card inside it (no longer private on its own).
        assertFalse("Pushback" in texts(scene))
        assertFalse(texts(scene).any { "Pushback from Kurt" in it })
        // Territories sit under the links and cards: the first thing drawn is the area.
        val first = scene.items.first() as SceneRect
        assertEquals(600f, first.width)
        assertEquals(18f, first.radius)

        val all = assertNotNull(MapSceneBuilder.build(withAreas, options.copy(includePrivate = true))).scene
        assertTrue("Pushback" in texts(all) && "PRIVATE" in texts(all))
        assertTrue(texts(all).any { "Pushback from Kurt" in it })
    }

    @Test
    fun colorByTintsTheCardsAndTheLegendFollows() {
        val plain = assertNotNull(MapSceneBuilder.build(board, MapSceneOptions(showTitleBlock = false))).scene
        val hot = assertNotNull(MapSceneBuilder.build(board, MapSceneOptions(showTitleBlock = false, colorBy = ColorBy.Importance))).scene
        fun cardFills(scene: Scene) = scene.items.filterIsInstance<SceneRect>().filter { it.radius == 10f }.map { it.fill }
        assertTrue(cardFills(plain).all { it == MapStyle.Light.card })
        assertTrue(cardFills(hot).any { it != MapStyle.Light.card }, "rated cards are tinted")
        assertTrue("LEGEND · IMPORTANCE" in texts(hot))
        assertTrue("9–10 critical" in texts(hot) && "7–8" in texts(hot))
        assertFalse("Goal" in texts(hot)) // the legend lists the scale, not the types
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
