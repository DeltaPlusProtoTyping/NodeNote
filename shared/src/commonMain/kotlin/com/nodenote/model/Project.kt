package com.nodenote.model

import kotlinx.serialization.Serializable
import kotlin.random.Random

/** The root document: everything that gets saved to / loaded from a .json file. */
@Serializable
data class Project(
    val id: String,
    val name: String,
    val nodes: List<Node> = emptyList(),
    val edges: List<Edge> = emptyList(),
    val elements: List<CanvasElement> = emptyList(),
    // User-defined types travel with the project so they survive sharing/reopening.
    val customNodeTypes: List<NodeTypeDef> = emptyList(),
    val customEdgeTypes: List<EdgeTypeDef> = emptyList(),
) {
    companion object {
        fun empty(): Project = Project(id = newId(), name = "Untitled Project")

        /** A small demo project so a first-time user sees something real immediately. */
        fun sample(): Project {
            val tracker = Node(newId(), "System", "Asset Tracker", "Battery powered GPS asset tracker, rev B.", -340f, -180f)
            val pcb = Node(newId(), "PCB", "Main PCB", "4-layer, 1.0 mm FR4.", -340f, 20f,
                properties = mapOf("Layers" to "4", "Rev" to "B2"))
            val rf = Node(newId(), "RFPath", "GPS RF Path", "LNA + SAW filter front end.", -40f, -180f,
                properties = mapOf("Band" to "L1 1575.42 MHz", "Gain" to "+16 dB"))
            val rail = Node(newId(), "PowerRail", "3V3 Rail", "Buck converter, 600 mA max.", -40f, 20f,
                properties = mapOf("Voltage" to "3.3 V", "Ripple" to "<20 mV"))
            val fw = Node(newId(), "SoftwareModule", "Firmware", "Zephyr-based application firmware.", 260f, -80f,
                properties = mapOf("RTOS" to "Zephyr 3.6"))
            val test = Node(newId(), "TestResult", "Range Test 04", "Open-field range test, pass.", 260f, 120f,
                properties = mapOf("Result" to "PASS", "Date" to "2026-05-30"),
                attachments = listOf(
                    Attachment(newId(), AttachmentKind.Text, "Test conditions",
                        "Open field, 25 °C, TX at full power.\nLogged with field laptop #2."),
                ))
            return Project(
                id = newId(),
                name = "Sample Tracker Project",
                nodes = listOf(tracker, pcb, rf, rail, fw, test),
                edges = listOf(
                    Edge(newId(), tracker.id, pcb.id, "contains", "Mechanical"),
                    Edge(newId(), pcb.id, rf.id, "feeds", "RF"),
                    Edge(newId(), pcb.id, rail.id, "hosts", "Power"),
                    Edge(newId(), fw.id, pcb.id, "runs on", "Data"),
                    Edge(newId(), test.id, rf.id, "verifies", "Signal"),
                ),
                elements = listOf(
                    CanvasElement(newId(), ElementKind.Text, -340f, -270f, 320f, 60f,
                        text = "Rev B system overview — double-click any text block to edit it.", fontSize = 13f),
                ),
            )
        }
    }
}

/** Random hex id. No java.util.UUID in common code, and this is plenty unique for documents. */
fun newId(): String {
    val chars = "0123456789abcdef"
    return buildString(16) { repeat(16) { append(chars[Random.nextInt(16)]) } }
}
