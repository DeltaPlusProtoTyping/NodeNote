package com.nodenote.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nodenote.model.Attachment
import com.nodenote.model.AttachmentKind
import com.nodenote.model.Node
import com.nodenote.model.attachmentKindForFileName
import com.nodenote.model.formatByteSize
import com.nodenote.model.newId
import com.nodenote.state.AppState
import com.nodenote.storage.ProjectStorage
import com.nodenote.storage.decodeImageOrNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import com.nodenote.theme.Accent
import com.nodenote.theme.DangerRed
import com.nodenote.theme.PanelBackground
import com.nodenote.theme.PanelBorder
import com.nodenote.theme.TextFaint
import com.nodenote.theme.TextPrimary
import com.nodenote.theme.TextSecondary
import kotlin.math.roundToInt

/**
 * Right panel: edits the selected node. Every change writes straight into
 * AppState (controlled fields), so the canvas updates as you type.
 */
@Composable
fun InspectorPanel(state: AppState, modifier: Modifier = Modifier) {
    val node = state.selectedNode

    Column(
        modifier
            .background(PanelBackground)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PanelCaption("Inspector", modifier = Modifier.weight(1f))
            PanelToggleButton("▸", onClick = { state.inspectorVisible = false })
        }

        val selectionCount = state.selectedNodeIds.size
        val element = state.selectedElement
        when {
            node != null -> NodeEditor(state, node)
            element != null -> ElementEditor(state, element)
            selectionCount > 1 -> {
                Spacer(Modifier.height(16.dp))
                Text(
                    "$selectionCount nodes selected.\n\nDrag any of them to move the group, " +
                        "use Align to snap them to the grid, or Delete to remove them all.",
                    fontSize = 12.sp,
                    color = TextFaint,
                    lineHeight = 17.sp,
                )
            }
            else -> {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Select a node to edit its title, type, description, properties and connections.\n\n" +
                        "Ctrl+click or drag a box on the canvas to select several nodes at once.",
                    fontSize = 12.sp,
                    color = TextFaint,
                    lineHeight = 17.sp,
                )
            }
        }
    }
}

@Composable
private fun NodeEditor(state: AppState, node: Node) {
    FieldLabel("Title")
    CompactTextField(
        value = node.title,
        onValueChange = { v -> state.updateNode(node.id) { it.copy(title = v) } },
        modifier = Modifier.fillMaxWidth(),
        placeholder = "Node title",
    )

    FieldLabel("Type")
    TypeSelector(
        state = state,
        selectedId = node.type,
        allowAll = false,
        onSelect = { t -> if (t != null) state.setNodeType(node.id, t) },
        modifier = Modifier.fillMaxWidth(),
    )

    FieldLabel("Description")
    CompactTextField(
        value = node.description,
        onValueChange = { v -> state.updateNode(node.id) { it.copy(description = v) } },
        modifier = Modifier.fillMaxWidth(),
        placeholder = "Short description",
        singleLine = false,
        minHeight = 64.dp,
    )

    Text(
        "X ${node.x.roundToInt()}    Y ${node.y.roundToInt()}    ·    ${node.width.roundToInt()} × ${node.height.roundToInt()}",
        fontSize = 11.sp,
        color = TextFaint,
    )

    HorizontalDivider(color = PanelBorder)
    PropertiesSection(state, node)

    HorizontalDivider(color = PanelBorder)
    AttachmentsSection(state, node)

    HorizontalDivider(color = PanelBorder)
    ConnectionsSection(state, node)

    HorizontalDivider(color = PanelBorder)
    OutlinedButton(
        onClick = { state.deleteNode(node.id) },
        modifier = Modifier.fillMaxWidth().height(32.dp),
        shape = RoundedCornerShape(6.dp),
        contentPadding = PaddingValues(0.dp),
        border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.45f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
    ) {
        Text("Delete node", fontSize = 12.sp)
    }
}

/** Editor for canvas elements (text blocks and images): content, font size, and dimensions. */
@Composable
private fun ElementEditor(state: AppState, element: com.nodenote.model.CanvasElement) {
    val isText = element.kind == com.nodenote.model.ElementKind.Text
    PanelCaption(if (isText) "Text block" else "Image")

    if (isText) {
        FieldLabel("Text")
        CompactTextField(
            value = element.text,
            onValueChange = { v -> state.updateElement(element.id) { it.copy(text = v) } },
            modifier = Modifier.fillMaxWidth(),
            placeholder = "Write something…",
            singleLine = false,
            minHeight = 90.dp,
        )
        FieldLabel("Font size — ${element.fontSize.roundToInt()} sp")
        Slider(
            value = element.fontSize,
            onValueChange = { v -> state.updateElement(element.id) { it.copy(fontSize = v) } },
            valueRange = 10f..48f,
            modifier = Modifier.fillMaxWidth().height(24.dp),
        )
    } else {
        Text(element.text.ifBlank { "(image)" }, fontSize = 12.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }

    FieldLabel("Width — ${element.width.roundToInt()}")
    Slider(
        value = element.width,
        onValueChange = { v -> state.updateElement(element.id) { it.copy(width = v) } },
        valueRange = 80f..800f,
        modifier = Modifier.fillMaxWidth().height(24.dp),
    )
    FieldLabel("Height — ${element.height.roundToInt()}")
    Slider(
        value = element.height,
        onValueChange = { v -> state.updateElement(element.id) { it.copy(height = v) } },
        valueRange = 40f..800f,
        modifier = Modifier.fillMaxWidth().height(24.dp),
    )

    Text(
        "X ${element.x.roundToInt()}    Y ${element.y.roundToInt()}",
        fontSize = 11.sp,
        color = TextFaint,
    )

    HorizontalDivider(color = PanelBorder)
    OutlinedButton(
        onClick = { state.deleteNode(element.id) },
        modifier = Modifier.fillMaxWidth().height(32.dp),
        shape = RoundedCornerShape(6.dp),
        contentPadding = PaddingValues(0.dp),
        border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.45f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
    ) {
        Text("Delete element", fontSize = 12.sp)
    }
}

/** Free-form key/value properties. Keys are fixed once added (delete + re-add to rename). */
@Composable
private fun PropertiesSection(state: AppState, node: Node) {
    PanelCaption("Properties (${node.properties.size})")

    node.properties.forEach { (key, value) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                key,
                fontSize = 11.sp,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(78.dp),
            )
            CompactTextField(
                value = value,
                onValueChange = { v -> state.updateNode(node.id) { it.copy(properties = it.properties + (key to v)) } },
                modifier = Modifier.weight(1f),
                minHeight = 26.dp,
                textStyle = TextStyle(color = TextPrimary, fontSize = 11.sp),
            )
            Spacer(Modifier.width(4.dp))
            RemoveButton { state.updateNode(node.id) { it.copy(properties = it.properties - key) } }
        }
    }

    // New-property row. Keyed on node.id so the draft clears when selection changes.
    var newKey by remember(node.id) { mutableStateOf("") }
    var newValue by remember(node.id) { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        CompactTextField(
            value = newKey,
            onValueChange = { newKey = it },
            modifier = Modifier.width(78.dp),
            placeholder = "key",
            minHeight = 26.dp,
            textStyle = TextStyle(color = TextPrimary, fontSize = 11.sp),
        )
        Spacer(Modifier.width(4.dp))
        CompactTextField(
            value = newValue,
            onValueChange = { newValue = it },
            modifier = Modifier.weight(1f),
            placeholder = "value",
            minHeight = 26.dp,
            textStyle = TextStyle(color = TextPrimary, fontSize = 11.sp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "+",
            fontSize = 14.sp,
            color = if (newKey.isBlank()) TextFaint else Accent,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(enabled = newKey.isBlank().not()) {
                    val k = newKey.trim()
                    state.updateNode(node.id) { it.copy(properties = it.properties + (k to newValue)) }
                    newKey = ""
                    newValue = ""
                }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * Attachments: images (thumbnail), files (row with export), and text snippets
 * (edited inline). Picked files are embedded into the project as Base64 — see
 * [Attachment] — so the .json stays self-contained. "Save" writes a file/image
 * attachment back out to disk via the platform save dialog.
 */
@OptIn(ExperimentalEncodingApi::class)
@Composable
private fun AttachmentsSection(state: AppState, node: Node) {
    PanelCaption("Attachments (${node.attachments.size})")

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CompactOutlinedButton("+ File / image", onClick = { pickAndAttach(state, node) }, modifier = Modifier.weight(1f))
        CompactOutlinedButton(
            "+ Text",
            onClick = { state.addAttachment(node.id, Attachment(newId(), AttachmentKind.Text, "Text note")) },
            modifier = Modifier.weight(1f),
        )
    }

    node.attachments.forEach { attachment ->
        when (attachment.kind) {
            AttachmentKind.Image -> ImageAttachmentRow(state, node, attachment)
            AttachmentKind.File -> FileAttachmentRow(state, node, attachment)
            AttachmentKind.Text -> TextAttachmentRow(state, node, attachment)
        }
    }
}

@OptIn(ExperimentalEncodingApi::class)
private fun pickAndAttach(state: AppState, node: Node) {
    val picked = ProjectStorage.pickAttachmentFile()
    if (picked == null) {
        state.status = "Attach cancelled"
        return
    }
    if (picked.bytes.size > Attachment.MAX_EMBED_BYTES) {
        state.status = "File too large to embed (max ${formatByteSize(Attachment.MAX_EMBED_BYTES)})"
        return
    }
    state.addAttachment(
        node.id,
        Attachment(
            id = newId(),
            kind = attachmentKindForFileName(picked.name),
            name = picked.name,
            content = Base64.encode(picked.bytes),
        ),
    )
}

@OptIn(ExperimentalEncodingApi::class)
private fun exportAttachment(state: AppState, attachment: Attachment) {
    val bytes = try {
        Base64.decode(attachment.content)
    } catch (e: Exception) {
        state.status = "Attachment data is corrupted"
        return
    }
    val path = ProjectStorage.saveAttachmentAs(attachment.name, bytes)
    state.status = if (path != null) "Saved $path" else "Save cancelled"
}

@OptIn(ExperimentalEncodingApi::class)
@Composable
private fun ImageAttachmentRow(state: AppState, node: Node, attachment: Attachment) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Decoding is comparatively expensive; cache per attachment content.
        val bitmap = remember(attachment.id, attachment.content.length) {
            runCatching { decodeImageOrNull(Base64.decode(attachment.content)) }.getOrNull()
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = attachment.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, PanelBorder, RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text("(image could not be decoded)", fontSize = 11.sp, color = TextFaint)
        }
        AttachmentNameRow(state, node, attachment)
    }
}

@Composable
private fun FileAttachmentRow(state: AppState, node: Node, attachment: Attachment) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Little file-type badge with the extension.
        Box(
            Modifier
                .width(34.dp)
                .height(26.dp)
                .border(1.dp, PanelBorder, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                attachment.name.substringAfterLast('.', "?").uppercase().take(4),
                fontSize = 8.sp,
                color = TextSecondary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) { AttachmentNameRow(state, node, attachment) }
    }
}

@Composable
private fun TextAttachmentRow(state: AppState, node: Node, attachment: Attachment) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CompactTextField(
                value = attachment.name,
                onValueChange = { v -> state.updateAttachment(node.id, attachment.id) { it.copy(name = v) } },
                modifier = Modifier.weight(1f),
                placeholder = "Title",
                minHeight = 24.dp,
                textStyle = TextStyle(color = TextSecondary, fontSize = 11.sp),
            )
            Spacer(Modifier.width(4.dp))
            RemoveButton { state.removeAttachment(node.id, attachment.id) }
        }
        CompactTextField(
            value = attachment.content,
            onValueChange = { v -> state.updateAttachment(node.id, attachment.id) { it.copy(content = v) } },
            modifier = Modifier.fillMaxWidth(),
            placeholder = "Write something…",
            singleLine = false,
            minHeight = 64.dp,
            textStyle = TextStyle(color = TextPrimary, fontSize = 11.sp),
        )
    }
}

/** Name + size, a Save (export) action, and remove — shared by image and file rows. */
@Composable
private fun AttachmentNameRow(state: AppState, node: Node, attachment: Attachment) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                attachment.name,
                fontSize = 11.sp,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(formatByteSize(attachment.approxByteSize), fontSize = 9.sp, color = TextFaint)
        }
        Text(
            "Save",
            fontSize = 11.sp,
            color = Accent,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable { exportAttachment(state, attachment) }
                .padding(4.dp),
        )
        Spacer(Modifier.width(2.dp))
        RemoveButton { state.removeAttachment(node.id, attachment.id) }
    }
}

/** Edges touching this node, plus the "start connection" flow (click a target node on the canvas to finish). */
@Composable
private fun ConnectionsSection(state: AppState, node: Node) {
    val related = state.project.edges.filter { it.fromNodeId == node.id || it.toNodeId == node.id }
    PanelCaption("Connections (${related.size})")

    if (state.connectFromId == node.id) {
        OutlinedButton(
            onClick = { state.cancelConnection() },
            modifier = Modifier.fillMaxWidth().height(30.dp),
            shape = RoundedCornerShape(6.dp),
            contentPadding = PaddingValues(0.dp),
            border = BorderStroke(1.dp, Accent.copy(alpha = 0.6f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
        ) {
            Text("Cancel connection", fontSize = 12.sp)
        }
    } else {
        FilledTonalButton(
            onClick = { state.startConnection(node.id) },
            modifier = Modifier.fillMaxWidth().height(30.dp),
            shape = RoundedCornerShape(6.dp),
            contentPadding = PaddingValues(0.dp),
        ) {
            Text("Start connection →", fontSize = 12.sp)
        }
    }

    related.forEach { edge ->
        val outgoing = edge.fromNodeId == node.id
        val otherId = if (outgoing) edge.toNodeId else edge.fromNodeId
        val other = state.project.nodes.find { it.id == otherId }
        Row(verticalAlignment = Alignment.Top) {
            Text(
                if (outgoing) "→" else "←",
                fontSize = 12.sp,
                color = TextSecondary,
                modifier = Modifier.width(18.dp).padding(top = 4.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    other?.title ?: "(missing node)",
                    fontSize = 12.sp,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EdgeTypeSelector(
                        state = state,
                        selectedId = edge.type,
                        onSelect = { t -> state.setEdgeType(edge.id, t) },
                        modifier = Modifier.width(92.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    CompactTextField(
                        value = edge.label,
                        onValueChange = { v -> state.updateEdge(edge.id) { it.copy(label = v) } },
                        modifier = Modifier.weight(1f),
                        placeholder = "label",
                        minHeight = 24.dp,
                        textStyle = TextStyle(color = TextSecondary, fontSize = 11.sp),
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            RemoveButton { state.deleteEdge(edge.id) }
        }
    }
}
