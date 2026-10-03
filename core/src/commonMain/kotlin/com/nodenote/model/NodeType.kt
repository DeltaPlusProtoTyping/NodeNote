package com.nodenote.model

import kotlinx.serialization.Serializable

/**
 * A node type definition — built-in or user-created.
 *
 * Types are data, not an enum, so users can add their own. [id] is the stable
 * key stored on each [Node.type] and serialized to disk. [colorArgb] is an
 * 0xAARRGGBB value.
 */
/** Grouping used to lay out the Add Node menu in columns. Enum order = column order. */
@Serializable
enum class NodeCategory(val label: String) {
    Work("Work"),
    Shop("Shop floor"),
    Business("Business"),
    // Legacy NodeNote categories: kept so old files and custom types still deserialize.
    General("General"),
    Hardware("Hardware"),
    Software("Software"),
    Data("Data"),
    Input("Input"),
    Video("Video"),
    Infrastructure("Infrastructure"),
    External("External"),
    Custom("Custom"),
}

@Serializable
data class NodeTypeDef(
    val id: String,
    val label: String,
    val colorArgb: Long,
    val category: NodeCategory = NodeCategory.Custom,
)

/** A connection type definition — built-in or user-created. */
/** Grouping used to lay out the connection-type menu in columns. Enum order = column order. */
@Serializable
enum class EdgeCategory(val label: String) {
    Work("Work"),
    Flow("Flow"),
    // Legacy NodeNote categories.
    General("General"),
    Hardware("Hardware"),
    Software("Software"),
    Input("Input"),
    Video("Video"),
    Custom("Custom"),
}

@Serializable
data class EdgeTypeDef(
    val id: String,
    val label: String,
    val colorArgb: Long,
    val category: EdgeCategory = EdgeCategory.Custom,
)

/**
 * The built-in node and connection types, aimed at process improvement in a
 * machine shop. Custom types live on the [Project] and are merged with these by
 * the resolver in AppState.
 *
 * [legacyNodes]/[legacyEdges] are the original NodeNote catalog (hardware,
 * software, video…). They are no longer offered in menus but still resolve,
 * so imported NodeNote diagrams keep their labels and colors.
 */
object BuiltinTypes {

    val nodes: List<NodeTypeDef> = listOf(
        // Work — the things two efficiency engineers talk about
        NodeTypeDef("Topic", "Topic", 0xFF8FA0B3, NodeCategory.Work),
        NodeTypeDef("Issue", "Issue", 0xFFE2606B, NodeCategory.Work),
        NodeTypeDef("Improvement", "Improvement", 0xFF53C27C, NodeCategory.Work),
        NodeTypeDef("Idea", "Idea", 0xFFF0C04A, NodeCategory.Work),
        NodeTypeDef("Decision", "Decision", 0xFF9B7BFF, NodeCategory.Work),
        NodeTypeDef("ActionItem", "Action", 0xFF5B9CFF, NodeCategory.Work),
        NodeTypeDef("Question", "Question", 0xFF45C4D6, NodeCategory.Work),
        NodeTypeDef("Metric", "KPI", 0xFF3FBFB2, NodeCategory.Work),
        NodeTypeDef("Goal", "Goal", 0xFF7C8CFF, NodeCategory.Work),
        NodeTypeDef("Project", "Project", 0xFFD97BC8, NodeCategory.Work),
        // Shop floor
        NodeTypeDef("Machine", "Machine", 0xFF5B8DEF, NodeCategory.Shop),
        NodeTypeDef("WorkCell", "Work Cell", 0xFF4CC2A8, NodeCategory.Shop),
        NodeTypeDef("Process", "Process", 0xFFE58E5B, NodeCategory.Shop),
        NodeTypeDef("Setup", "Setup", 0xFFE5B25B, NodeCategory.Shop),
        NodeTypeDef("Tooling", "Tooling", 0xFFD97BC8, NodeCategory.Shop),
        NodeTypeDef("Fixture", "Fixture", 0xFFB58BE0, NodeCategory.Shop),
        NodeTypeDef("Program", "CNC Program", 0xFF58B0F0, NodeCategory.Shop),
        NodeTypeDef("Material", "Material", 0xFFA0A8B0, NodeCategory.Shop),
        NodeTypeDef("Quality", "Quality", 0xFF46B98A, NodeCategory.Shop),
        NodeTypeDef("Part", "Part / Job", 0xFF6FB1FC, NodeCategory.Shop),
        // Business
        NodeTypeDef("Customer", "Customer", 0xFF5BC0EB, NodeCategory.Business),
        NodeTypeDef("Estimate", "Quote", 0xFFE0C04B, NodeCategory.Business),
        NodeTypeDef("Supplier", "Supplier", 0xFFC99BE0, NodeCategory.Business),
        NodeTypeDef("Department", "Department", 0xFF8B98A8, NodeCategory.Business),
        NodeTypeDef("Person", "Person", 0xFF6FB1FC, NodeCategory.Business),
        NodeTypeDef("Cost", "Cost", 0xFF7FBF5A, NodeCategory.Business),
        NodeTypeDef("System", "Software", 0xFF8E7BFF, NodeCategory.Business),
        NodeTypeDef("Document", "SOP / Doc", 0xFF9AA5B1, NodeCategory.Business),
    )

    val edges: List<EdgeTypeDef> = listOf(
        EdgeTypeDef("Relates", "Relates to", 0xFF8FA0B3, EdgeCategory.Work),
        EdgeTypeDef("Causes", "Causes", 0xFFE2606B, EdgeCategory.Work),
        EdgeTypeDef("Blocks", "Blocks", 0xFFE58E5B, EdgeCategory.Work),
        EdgeTypeDef("Solves", "Solves", 0xFF53C27C, EdgeCategory.Work),
        EdgeTypeDef("PartOf", "Part of", 0xFF9B7BFF, EdgeCategory.Work),
        EdgeTypeDef("Duplicates", "Duplicates", 0xFF9AA5B1, EdgeCategory.Work),
        EdgeTypeDef("Feeds", "Feeds (flow)", 0xFF5B9CFF, EdgeCategory.Flow),
        EdgeTypeDef("Next", "Next step", 0xFF45C4D6, EdgeCategory.Flow),
        EdgeTypeDef("DependsOn", "Depends on", 0xFFE5B25B, EdgeCategory.Flow),
    )

    val legacyNodes: List<NodeTypeDef> = listOf(
        NodeTypeDef("Device", "Device", 0xFF45C4D6, NodeCategory.Hardware),
        NodeTypeDef("PCB", "PCB", 0xFF53C27C, NodeCategory.Hardware),
        NodeTypeDef("RFPath", "RF Path", 0xFF9B7BFF, NodeCategory.Hardware),
        NodeTypeDef("PowerRail", "Power Rail", 0xFFE5B25B, NodeCategory.Hardware),
        NodeTypeDef("Connector", "Connector", 0xFFE58E5B, NodeCategory.Hardware),
        NodeTypeDef("SoftwareModule", "Software Module", 0xFFD97BC8, NodeCategory.Software),
        NodeTypeDef("Service", "Service", 0xFF5BC0EB, NodeCategory.Software),
        NodeTypeDef("ApiGateway", "API / Gateway", 0xFF8E7BFF, NodeCategory.Software),
        NodeTypeDef("Function", "Function", 0xFFF0C04A, NodeCategory.Software),
        NodeTypeDef("Worker", "Worker", 0xFFB58BE0, NodeCategory.Software),
        NodeTypeDef("Auth", "Auth", 0xFFE25B8F, NodeCategory.Software),
        NodeTypeDef("WebApp", "Web App", 0xFF58B0F0, NodeCategory.Software),
        NodeTypeDef("MobileApp", "Mobile App", 0xFF46B98A, NodeCategory.Software),
        NodeTypeDef("Database", "Database", 0xFFE5A23B, NodeCategory.Data),
        NodeTypeDef("Cache", "Cache", 0xFFE5725B, NodeCategory.Data),
        NodeTypeDef("Queue", "Queue / Event Bus", 0xFFC77BD9, NodeCategory.Data),
        NodeTypeDef("Storage", "Storage", 0xFF8FA0B3, NodeCategory.Data),
        NodeTypeDef("LoadBalancer", "Load Balancer", 0xFF4CC2A8, NodeCategory.Infrastructure),
        NodeTypeDef("CDN", "CDN", 0xFF7FBF5A, NodeCategory.Infrastructure),
        NodeTypeDef("Container", "Container / Host", 0xFF5B8DEF, NodeCategory.Infrastructure),
        NodeTypeDef("CICD", "CI/CD Pipeline", 0xFFE08A4B, NodeCategory.Infrastructure),
        NodeTypeDef("Monitoring", "Monitoring", 0xFFE0C04B, NodeCategory.Infrastructure),
        NodeTypeDef("ExternalService", "External Service", 0xFFA0A8B0, NodeCategory.External),
        NodeTypeDef("User", "User / Actor", 0xFF6FB1FC, NodeCategory.External),
        NodeTypeDef("Webhook", "Webhook", 0xFFC99BE0, NodeCategory.External),
        NodeTypeDef("Environment", "Environment", 0xFF8B98A8, NodeCategory.External),
        NodeTypeDef("Questionnaire", "Questionnaire", 0xFF4FB0C6, NodeCategory.Input),
        NodeTypeDef("Survey", "Survey", 0xFF5B9E8F, NodeCategory.Input),
        NodeTypeDef("Form", "Form", 0xFF7AA9D0, NodeCategory.Input),
        NodeTypeDef("Poll", "Poll", 0xFFD0A85B, NodeCategory.Input),
        NodeTypeDef("Comment", "Comment", 0xFF9AA5B1, NodeCategory.Input),
        NodeTypeDef("TextInput", "Text Input", 0xFF6FB1FC, NodeCategory.Input),
        NodeTypeDef("VoiceInput", "Voice Input", 0xFFD97BC8, NodeCategory.Input),
        NodeTypeDef("Rating", "Rating", 0xFFE5B25B, NodeCategory.Input),
        NodeTypeDef("Interview", "Interview", 0xFFB58BE0, NodeCategory.Input),
        NodeTypeDef("Clip", "Clip", 0xFFE2606B, NodeCategory.Video),
        NodeTypeDef("Scene", "Scene", 0xFFE58E5B, NodeCategory.Video),
        NodeTypeDef("Script", "Script", 0xFFE5B25B, NodeCategory.Video),
        NodeTypeDef("Storyboard", "Storyboard", 0xFF8E7BFF, NodeCategory.Video),
        NodeTypeDef("BRoll", "B-Roll", 0xFF45C4D6, NodeCategory.Video),
        NodeTypeDef("AudioTrack", "Audio Track", 0xFF53C27C, NodeCategory.Video),
        NodeTypeDef("Voiceover", "Voiceover", 0xFFD97BC8, NodeCategory.Video),
        NodeTypeDef("TitleCaption", "Title / Caption", 0xFF5B9CFF, NodeCategory.Video),
        NodeTypeDef("Transition", "Transition", 0xFF9B7BFF, NodeCategory.Video),
        NodeTypeDef("Effect", "Effect", 0xFFF0C04A, NodeCategory.Video),
        NodeTypeDef("Note", "Note", 0xFF9AA5B1, NodeCategory.General),
        NodeTypeDef("TestResult", "Test Result", 0xFF3FBFB2, NodeCategory.General),
        NodeTypeDef("Requirement", "Requirement", 0xFF7C8CFF, NodeCategory.General),
        NodeTypeDef("Risk", "Risk", 0xFFE2606B, NodeCategory.General),
    )

    val legacyEdges: List<EdgeTypeDef> = listOf(
        EdgeTypeDef("Data", "Data", 0xFF5B9CFF, EdgeCategory.General),
        EdgeTypeDef("Dependency", "Dependency", 0xFF8FA0B3, EdgeCategory.General),
        EdgeTypeDef("Custom", "Custom", 0xFFD97BC8, EdgeCategory.General),
        EdgeTypeDef("Power", "Power", 0xFFE5B25B, EdgeCategory.Hardware),
        EdgeTypeDef("Signal", "Signal", 0xFF53C27C, EdgeCategory.Hardware),
        EdgeTypeDef("RF", "RF", 0xFF9B7BFF, EdgeCategory.Hardware),
        EdgeTypeDef("Mechanical", "Mechanical", 0xFF9AA5B1, EdgeCategory.Hardware),
        EdgeTypeDef("ApiCall", "API Call", 0xFF5BC0EB, EdgeCategory.Software),
        EdgeTypeDef("Event", "Event", 0xFFC77BD9, EdgeCategory.Software),
        EdgeTypeDef("Auth", "Auth / Trust", 0xFFE25B8F, EdgeCategory.Software),
        EdgeTypeDef("Network", "Network", 0xFF6FB1FC, EdgeCategory.Software),
        EdgeTypeDef("Response", "Response", 0xFF5BC0EB, EdgeCategory.Input),
        EdgeTypeDef("Submission", "Submission", 0xFF53C27C, EdgeCategory.Input),
        EdgeTypeDef("Annotation", "Annotation", 0xFF9AA5B1, EdgeCategory.Input),
        EdgeTypeDef("Sequence", "Sequence", 0xFFE58E5B, EdgeCategory.Video),
        EdgeTypeDef("Cut", "Cut", 0xFFE2606B, EdgeCategory.Video),
        EdgeTypeDef("Sync", "Sync", 0xFF6FB1FC, EdgeCategory.Video),
        EdgeTypeDef("Overlay", "Overlay", 0xFF9B7BFF, EdgeCategory.Video),
    )

    val nodeById: Map<String, NodeTypeDef> = (legacyNodes + nodes).associateBy { it.id }
    val edgeById: Map<String, EdgeTypeDef> = (legacyEdges + edges).associateBy { it.id }

    private const val UNKNOWN_COLOR = 0xFF9AA5B1

    /** Default type id for a brand-new node / edge. */
    const val DEFAULT_NODE = "Topic"
    const val DEFAULT_EDGE = "Relates"

    /** A swatch palette offered when creating a custom type. */
    val SWATCHES: List<Long> = listOf(
        0xFF5B9CFF, 0xFF45C4D6, 0xFF53C27C, 0xFF9B7BFF, 0xFFE5B25B, 0xFFE58E5B,
        0xFFD97BC8, 0xFFE2606B, 0xFF3FBFB2, 0xFFF0C04A, 0xFF8FA0B3, 0xFF6FB1FC,
    )

    fun unknownNode(id: String) = NodeTypeDef(id, id.ifBlank { "Type" }, UNKNOWN_COLOR)
    fun unknownEdge(id: String) = EdgeTypeDef(id, id.ifBlank { "Type" }, UNKNOWN_COLOR)
}

/** Resolves a node-type id against the built-ins and this project's custom types. */
fun Project.nodeType(id: String): NodeTypeDef =
    BuiltinTypes.nodeById[id] ?: customNodeTypes.find { it.id == id } ?: BuiltinTypes.unknownNode(id)

/** Resolves a connection-type id against the built-ins and this project's custom types. */
fun Project.edgeType(id: String): EdgeTypeDef =
    BuiltinTypes.edgeById[id] ?: customEdgeTypes.find { it.id == id } ?: BuiltinTypes.unknownEdge(id)
