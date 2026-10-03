package com.nodenote.model

import com.nodenote.api.ApiJson
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Golden samples of the JSON the server stores today (boards) and exchanges
 * (messages). If a model change makes these fail to decode — a renamed field or
 * enum value, a new field without a default — then data people already entered
 * would stop loading after an update. Never edit these samples to make a test
 * pass: add the new shape as a new sample and keep the old one decoding.
 *
 * Rules for evolving the models: add fields only with defaults; never rename or
 * remove a serialized field or enum value (add the new one, keep reading the old).
 */
class SavedFormatTest {

    private val boardV2 = """
        {"id":"b1","name":"Shop floor","nodes":[
          {"id":"n1","type":"Issue","title":"Spindle warm-up","description":"20 min/shift","x":10.0,"y":20.0,
           "width":190.0,"height":92.0,"properties":{"Machine":"VF-2"},"attachments":[
             {"id":"a1","kind":"Text","name":"note","content":"hi"}],
           "status":"InProgress","importance":7,"owner":"Nick","summary":"Try scheduled warm-up",
           "createdAt":1790000000000,"createdBy":"Ben"}],
         "edges":[{"id":"e1","fromNodeId":"n1","toNodeId":"n1","label":"","type":"Relates"}],
         "elements":[{"id":"t1","kind":"Text","x":0.0,"y":0.0,"width":200.0,"height":60.0,"text":"hello","fontSize":14.0,"content":""}],
         "customNodeTypes":[{"id":"c1","label":"Robot","colorArgb":4283215103,"category":"Custom"}],
         "customEdgeTypes":[]}
    """.trimIndent()

    /** A NodeNote 1.x project file (before statuses/chat existed) — imports must keep working. */
    private val nodeNoteV1 = """
        {"id":"p1","name":"Old diagram","nodes":[
          {"id":"n1","type":"PCB","title":"Main PCB","description":"","x":0.0,"y":0.0,"width":190.0,"height":92.0,
           "properties":{},"attachments":[]}],
         "edges":[{"id":"e1","fromNodeId":"n1","toNodeId":"n1","label":"feeds","type":"RF"}],
         "elements":[],"customNodeTypes":[],"customEdgeTypes":[]}
    """.trimIndent()

    /** October 2026: effort, private, solvedAt, scale (and keywords) on nodes. */
    private val boardV3 = """
        {"id":"b1","name":"Shop floor","nodes":[
          {"id":"n1","type":"Improvement","title":"Decrease QC time on first part checks","description":"","x":10.0,"y":20.0,
           "width":285.0,"height":138.0,"properties":{},"attachments":[],"status":"Solved","importance":8,"owner":"",
           "summary":"","createdAt":1790000000000,"createdBy":"Ben","keywords":"QC, first part","effort":3,
           "private":true,"solvedAt":1791000000000,"scale":1.5}],
         "edges":[],"elements":[],"customNodeTypes":[],"customEdgeTypes":[]}
    """.trimIndent()

    /** October 2026: areas (named territories behind groups of cards). */
    private val boardV4 = """
        {"id":"b1","name":"Shop floor","nodes":[
          {"id":"n1","type":"Issue","title":"Quoting takes too long","description":"","x":10.0,"y":20.0,
           "width":190.0,"height":92.0,"properties":{},"attachments":[],"status":"Open","importance":0,"owner":"",
           "summary":"","createdAt":1790000000000,"createdBy":"Ben","keywords":"","effort":0,
           "private":false,"solvedAt":0,"scale":1.0}],
         "edges":[],"elements":[],"customNodeTypes":[],"customEdgeTypes":[],
         "areas":[{"id":"a1","title":"Sales pipeline","x":-40.0,"y":-60.0,"width":300.0,"height":240.0,
           "color":4284194047,"private":true,"createdAt":1791000000000,"createdBy":"Ben"}]}
    """.trimIndent()

    private val messageV2 = """
        {"id":"m1","boardId":"b1","nodeId":"n1","author":"Nick","text":"see [[Spindle warm-up]]","kind":"Decision",
         "importance":9,"linkedNodeIds":["n1"],"done":false,"createdAt":1790000000000,"editedAt":0,"deleted":false,"seq":42}
    """.trimIndent()

    @Test
    fun boardsSavedTodayStillLoad() {
        val p = ApiJson.decodeFromString(Project.serializer(), boardV2)
        val n = p.nodes.single()
        assertEquals(NodeStatus.InProgress, n.status)
        assertEquals(7, n.importance)
        assertEquals("Nick", n.owner)
        assertEquals("Try scheduled warm-up", n.summary)
        assertEquals("Robot", p.customNodeTypes.single().label)
    }

    @Test
    fun boardsFromBeforeEffortAndPrivateGetDefaults() {
        val n = ApiJson.decodeFromString(Project.serializer(), boardV2).nodes.single()
        assertEquals(0, n.effort)
        assertEquals(false, n.isPrivate)
        assertEquals(0L, n.solvedAt)
        assertEquals(1f, n.scale)
    }

    @Test
    fun boardsWithEffortPrivateAndScaleLoad() {
        val n = ApiJson.decodeFromString(Project.serializer(), boardV3).nodes.single()
        assertEquals(3, n.effort)
        assertEquals(true, n.isPrivate)
        assertEquals(1791000000000L, n.solvedAt)
        assertEquals(1.5f, n.scale)
        assertEquals("QC, first part", n.keywords)
        // The flag is stored as "private" (the Kotlin name can't be).
        val encoded = ApiJson.encodeToString(Node.serializer(), n)
        assertEquals(true, "\"private\":true" in encoded, encoded)
    }

    @Test
    fun boardsFromBeforeAreasHaveNone() {
        assertEquals(emptyList(), ApiJson.decodeFromString(Project.serializer(), boardV2).areas)
        assertEquals(emptyList(), ApiJson.decodeFromString(Project.serializer(), boardV3).areas)
    }

    @Test
    fun boardsWithAreasLoad() {
        val p = ApiJson.decodeFromString(Project.serializer(), boardV4)
        val a = p.areas.single()
        assertEquals("Sales pipeline", a.title)
        assertEquals(300f, a.width)
        assertEquals(0xFF5B9CFF, a.color)
        assertEquals(true, a.isPrivate)
        assertEquals("Ben", a.createdBy)
        // Stored as "private", like a node's flag; the card inside is private through its area.
        assertEquals(true, "\"private\":true" in ApiJson.encodeToString(Area.serializer(), a))
        assertEquals(setOf("n1"), p.privateNodeIds())
    }

    @Test
    fun nodeNoteV1FilesStillImport() {
        val p = ApiJson.decodeFromString(Project.serializer(), nodeNoteV1)
        assertEquals(NodeStatus.None, p.nodes.single().status)
        assertEquals("PCB", p.nodeType("PCB").label)
    }

    @Test
    fun messagesSavedTodayStillLoad() {
        val m = ApiJson.decodeFromString(Message.serializer(), messageV2)
        assertEquals(MessageKind.Decision, m.kind)
        assertEquals(9, m.importance)
        assertEquals(42L, m.seq)
    }
}
