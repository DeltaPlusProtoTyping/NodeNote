package com.nodenote.model

import kotlinx.serialization.Serializable

/** A directed connection between two nodes, drawn as an arrow from `fromNodeId` to `toNodeId`. */
@Serializable
data class Edge(
    val id: String,
    val fromNodeId: String,
    val toNodeId: String,
    val label: String = "",
    /** Connection type id — resolved to an [EdgeTypeDef] (built-in or project custom) via the catalog. */
    val type: String = BuiltinTypes.DEFAULT_EDGE,
)
