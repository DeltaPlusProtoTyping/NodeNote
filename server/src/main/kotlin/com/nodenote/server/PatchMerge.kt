package com.nodenote.server

import com.nodenote.api.ApiJson
import com.nodenote.model.BoardPatch
import com.nodenote.model.CanvasElement
import com.nodenote.model.Edge
import com.nodenote.model.EdgeTypeDef
import com.nodenote.model.Node
import com.nodenote.model.NodeTypeDef
import com.nodenote.model.Project
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Protects fields an older client doesn't know about.
 *
 * A browser tab opened before an update decodes boards with the old model, so an item it
 * sends back (after a drag, say) lacks the fields that update added. Decoding fills them
 * with defaults, which would quietly reset them on the board: a Private flag, an effort
 * rating. So for every upserted item that already exists, any field missing from what the
 * client actually sent keeps its stored value.
 *
 * This relies on clients always sending every field they know ([ApiJson] encodes defaults)
 * and on board models having no nullable fields: with `explicitNulls = false` a null is
 * omitted, which would read as "not sent".
 */
object PatchMerge {

    /** [patch] with unsent fields of existing items filled in from [current]. [sent] is the patch JSON as received. */
    fun keepUnsentFields(patch: BoardPatch, sent: JsonObject?, current: Project): BoardPatch {
        if (sent == null) return patch
        return patch.copy(
            upsertNodes = complete(patch.upsertNodes, sent["upsertNodes"], current.nodes, Node.serializer()) { it.id },
            upsertEdges = complete(patch.upsertEdges, sent["upsertEdges"], current.edges, Edge.serializer()) { it.id },
            upsertElements = complete(patch.upsertElements, sent["upsertElements"], current.elements, CanvasElement.serializer()) { it.id },
            upsertNodeTypes = complete(patch.upsertNodeTypes, sent["upsertNodeTypes"], current.customNodeTypes, NodeTypeDef.serializer()) { it.id },
            upsertEdgeTypes = complete(patch.upsertEdgeTypes, sent["upsertEdgeTypes"], current.customEdgeTypes, EdgeTypeDef.serializer()) { it.id },
        )
    }

    private fun <T> complete(
        items: List<T>,
        sentItems: JsonElement?,
        stored: List<T>,
        serializer: KSerializer<T>,
        id: (T) -> String,
    ): List<T> {
        val raw = sentItems as? JsonArray ?: return items
        if (raw.size != items.size || stored.isEmpty()) return items
        val byId = stored.associateBy(id)
        return items.mapIndexed { i, item ->
            val existing = byId[id(item)] ?: return@mapIndexed item
            val sentFields = raw[i] as? JsonObject ?: return@mapIndexed item
            val storedFields = ApiJson.encodeToJsonElement(serializer, existing).jsonObject
            if (sentFields.keys.containsAll(storedFields.keys)) return@mapIndexed item
            // Stored values first, then everything the client did send on top.
            ApiJson.decodeFromJsonElement(serializer, JsonObject(storedFields + sentFields))
        }
    }
}
