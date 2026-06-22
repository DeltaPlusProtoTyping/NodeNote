package com.nodenote.state

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.nodenote.model.Attachment
import com.nodenote.model.BuiltinTypes
import com.nodenote.model.CanvasElement
import com.nodenote.model.Edge
import com.nodenote.model.EdgeTypeDef
import com.nodenote.model.ElementKind
import com.nodenote.model.ExportPreset
import com.nodenote.model.ExportSettings
import com.nodenote.model.Node
import com.nodenote.model.NodeTypeDef
import com.nodenote.model.Project
import com.nodenote.model.ProjectDiff
import com.nodenote.model.edgeType
import com.nodenote.model.newId
import com.nodenote.model.nodeType
import com.nodenote.storage.PresetStore
import com.nodenote.storage.RecentStore
import com.nodenote.storage.RecentTypeStore
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Ambient access to the app state for deeply shared widgets (e.g. text fields
 * reporting focus). Everything else takes AppState as a normal parameter.
 */
val LocalAppState = compositionLocalOf<AppState?> { null }

/**
 * The single source of truth for the whole app.
 *
 * Deliberately simple: a handful of Compose [mutableStateOf] fields and plain
 * functions that replace the immutable [Project] with an updated copy. That
 * immutability also gives us undo/redo for free: the undo stack is just a list
 * of previous Project snapshots.
 */
class AppState {

    /** Stable id for this open document, used by the [com.nodenote.state.Workspace] tab bar. */
    val tabId: String = newId()

    /** Short title for the tab: the file name (or project name), with a • when there are unsaved changes. */
    val tabTitle: String
        get() {
            val base = currentFilePath?.substringAfterLast('\\')?.substringAfterLast('/')
                ?: project.name.ifBlank { "Untitled" }
            return if (dirty) "$base •" else base
        }

    // ---- Document ----
    var project by mutableStateOf(Project.empty())
        private set

    /** File the project was last saved to / loaded from; plain Save (Ctrl+S) reuses it without a dialog. */
    var currentFilePath by mutableStateOf<String?>(null)

    /** Project as of the last save/load — anything different means unsaved changes. */
    private var lastSavedProject by mutableStateOf<Project?>(null)

    val dirty: Boolean get() = project != lastSavedProject

    fun markSaved() {
        lastSavedProject = project
    }

    // ---- Undo / redo ----
    // Project is immutable, so a history entry is just a reference to an old copy.
    // Structural operations call pushUndo() first; drags push once per gesture
    // (from onDragStart). Per-keystroke text edits are deliberately not snapshotted.

    private var undoStack by mutableStateOf(listOf<Project>())
    private var redoStack by mutableStateOf(listOf<Project>())

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun pushUndo() {
        undoStack = (undoStack + project).takeLast(UNDO_LIMIT)
        redoStack = emptyList()
    }

    fun undo() {
        val previous = undoStack.lastOrNull() ?: return
        undoStack = undoStack.dropLast(1)
        redoStack = redoStack + project
        project = previous
        pruneStaleIds()
        status = "Undo"
    }

    fun redo() {
        val next = redoStack.lastOrNull() ?: return
        redoStack = redoStack.dropLast(1)
        undoStack = undoStack + project
        project = next
        pruneStaleIds()
        status = "Redo"
    }

    /** After undo/redo or load, drop references to items that no longer exist. */
    private fun pruneStaleIds() {
        val valid = buildSet {
            project.nodes.forEach { add(it.id) }
            project.elements.forEach { add(it.id) }
        }
        selectedNodeIds = selectedNodeIds.filterTo(mutableSetOf()) { it in valid }
        if (editingNodeId != null && editingNodeId !in valid) editingNodeId = null
        if (editingElementId != null && editingElementId !in valid) editingElementId = null
        if (connectFromId != null && connectFromId !in valid) connectFromId = null
    }

    // ---- Recent files ----
    var recentFiles by mutableStateOf(listOf<String>())
        private set
    private var recentsLoaded = false

    fun loadRecentsIfNeeded() {
        if (!recentsLoaded) {
            recentFiles = RecentStore.load()
            recentsLoaded = true
        }
    }

    fun noteRecentFile(path: String) {
        loadRecentsIfNeeded()
        recentFiles = (listOf(path) + recentFiles.filterNot { it == path }).take(10)
        RecentStore.save(recentFiles)
    }

    fun dropRecentFile(path: String) {
        recentFiles = recentFiles.filterNot { it == path }
        RecentStore.save(recentFiles)
    }

    // ---- UI / interaction state (not saved) ----

    /** Selection is a set of node AND element ids: click selects one, Ctrl+click toggles, marquee selects many. */
    var selectedNodeIds by mutableStateOf(setOf<String>())
        private set

    /** Sidebar filter: a node-type id, or null for "all types". */
    var typeFilter by mutableStateOf<String?>(null)
    var status by mutableStateOf("Ready")

    /** Node whose title is being edited inline on the canvas (started by double-clicking the card). */
    var editingNodeId by mutableStateOf<String?>(null)

    /** Text element being edited inline on the canvas (started by double-clicking it). */
    var editingElementId by mutableStateOf<String?>(null)

    /** True while Ctrl is held — maintained by the window-level key handler, used for toggle-select clicks. */
    var ctrlDown by mutableStateOf(false)

    /**
     * Number of text fields that currently hold keyboard focus (0 or 1 in practice),
     * maintained by CompactTextField / the inline editors. The window-level
     * Delete shortcut checks this so it never fires while the user is typing.
     */
    var activeTextFields by mutableStateOf(0)

    /** Box-selection rectangle while the user is dragging on empty canvas, as (start, current) in screen px. */
    var marquee by mutableStateOf<Pair<Offset, Offset>?>(null)

    /** Id of the node a pending connection starts from, or null when not connecting. */
    var connectFromId by mutableStateOf<String?>(null)
        private set

    // ---- Export dialog ----

    var exportDialogOpen by mutableStateOf(false)
        private set
    var exportSettings by mutableStateOf(ExportSettings())
    var exportPresets by mutableStateOf(listOf<ExportPreset>())
        private set

    /** Preset the current settings came from (null = unsaved/custom tweaks). */
    var activeExportPresetId by mutableStateOf<String?>(null)
    private var presetsLoaded = false

    fun openExportDialog() {
        if (!presetsLoaded) {
            exportPresets = PresetStore.load()
            presetsLoaded = true
        }
        exportDialogOpen = true
    }

    fun closeExportDialog() {
        exportDialogOpen = false
    }

    fun applyExportPreset(preset: ExportPreset) {
        exportSettings = preset.settings
        activeExportPresetId = preset.id
    }

    fun saveExportPresetAsNew(name: String) {
        val preset = ExportPreset(newId(), name.trim().ifBlank { "Preset" }, exportSettings)
        exportPresets = exportPresets + preset
        activeExportPresetId = preset.id
        PresetStore.save(exportPresets)
        status = "Saved preset \"${preset.name}\""
    }

    /** Overwrites the active preset with the current settings (and possibly a new name = rename). */
    fun updateActiveExportPreset(name: String) {
        val id = activeExportPresetId ?: return
        exportPresets = exportPresets.map {
            if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }, settings = exportSettings) else it
        }
        PresetStore.save(exportPresets)
        status = "Preset updated"
    }

    fun deleteActiveExportPreset() {
        val id = activeExportPresetId ?: return
        exportPresets = exportPresets.filterNot { it.id == id }
        activeExportPresetId = null
        PresetStore.save(exportPresets)
        status = "Preset deleted"
    }

    // ---- Compare dialog ----

    /** Result of the last Compare… (open project vs. a chosen file), or null when the dialog is closed. */
    var compareDiff by mutableStateOf<ProjectDiff?>(null)
        private set

    fun showCompareDiff(diff: ProjectDiff) {
        compareDiff = diff
    }

    fun closeCompareDiff() {
        compareDiff = null
    }

    // ---- Keyboard shortcuts help ----

    var shortcutsOpen by mutableStateOf(false)

    /** True when any modal is open — used to gate canvas/editing shortcuts. */
    val anyDialogOpen: Boolean
        get() = exportDialogOpen || compareDiff != null || shortcutsOpen || newTypeKind != null

    // ---- Panel layout (dp). Panels can be resized by dragging their inner border and collapsed entirely. ----
    var leftSidebarVisible by mutableStateOf(true)
    var inspectorVisible by mutableStateOf(true)
    var notesPanelVisible by mutableStateOf(true)
    var leftSidebarWidth by mutableStateOf(230f)
        private set
    var inspectorWidth by mutableStateOf(280f)
        private set
    var notesPanelHeight by mutableStateOf(140f)
        private set

    /** [deltaDp] is how far the divider was dragged to the right. */
    fun resizeLeftSidebar(deltaDp: Float) {
        leftSidebarWidth = (leftSidebarWidth + deltaDp).coerceIn(160f, 420f)
    }

    fun resizeInspector(deltaDp: Float) {
        inspectorWidth = (inspectorWidth - deltaDp).coerceIn(220f, 500f)
    }

    /** [deltaDp] is how far the divider was dragged downward. */
    fun resizeNotesPanel(deltaDp: Float) {
        notesPanelHeight = (notesPanelHeight - deltaDp).coerceIn(70f, 340f)
    }

    // ---- Camera ----
    // The canvas transform is: screen = world * (zoom * density) + pan.
    // World units are density-independent pixels (dp), pan is in raw screen pixels.
    var pan by mutableStateOf(Offset.Zero)
    var zoom by mutableStateOf(1f)
        private set

    /** Size of the canvas viewport in screen pixels; kept current by GraphCanvas. */
    var canvasSize by mutableStateOf(IntSize.Zero)

    /** Screen density of the canvas; kept current by GraphCanvas. */
    var viewDensity by mutableStateOf(1f)

    /** Last known pointer position over the canvas (screen px) — used to draw the pending-connection line. */
    var pointerPos by mutableStateOf(Offset.Zero)

    /** The selected node when exactly one node is selected (what the inspector edits). */
    val selectedNode: Node?
        get() = selectedNodeIds.singleOrNull()?.let { id -> project.nodes.find { it.id == id } }

    /** The selected canvas element when exactly one element is selected. */
    val selectedElement: CanvasElement?
        get() = selectedNodeIds.singleOrNull()?.let { id -> project.elements.find { it.id == id } }

    // ---- Coordinate transforms ----

    fun worldToScreen(world: Offset): Offset = world * (zoom * viewDensity) + pan
    fun screenToWorld(screen: Offset): Offset = (screen - pan) / (zoom * viewDensity)

    /** Zoom by [factor], keeping the screen point [pivot] fixed (e.g. the mouse cursor). */
    fun zoomBy(factor: Float, pivot: Offset) {
        val newZoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (newZoom == zoom) return
        pan = pivot - (pivot - pan) * (newZoom / zoom)
        zoom = newZoom
    }

    fun zoomIn() = zoomBy(1.2f, canvasCenter())
    fun zoomOut() = zoomBy(1f / 1.2f, canvasCenter())

    fun resetView() {
        pan = Offset.Zero
        zoom = 1f
    }

    /** Frames all content (nodes + elements) in the viewport. */
    fun zoomToFit() {
        val bounds = contentBounds() ?: run {
            status = "Nothing to fit"
            return
        }
        if (canvasSize.width <= 0 || canvasSize.height <= 0) return
        val pad = 60f
        val worldW = (bounds.right - bounds.left + pad * 2) * viewDensity
        val worldH = (bounds.bottom - bounds.top + pad * 2) * viewDensity
        zoom = min(canvasSize.width / worldW, canvasSize.height / worldH).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val center = Offset((bounds.left + bounds.right) / 2f, (bounds.top + bounds.bottom) / 2f)
        pan = canvasCenter() - center * (zoom * viewDensity)
    }

    private class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

    private fun contentBounds(): Bounds? {
        val xs1 = project.nodes.map { Triple(it.x, it.y, Pair(it.width, it.height)) }
        val xs2 = project.elements.map { Triple(it.x, it.y, Pair(it.width, it.height)) }
        val all = xs1 + xs2
        if (all.isEmpty()) return null
        return Bounds(
            left = all.minOf { it.first },
            top = all.minOf { it.second },
            right = all.maxOf { it.first + it.third.first },
            bottom = all.maxOf { it.second + it.third.second },
        )
    }

    /** Center the view on a node (used when clicking a node in the sidebar list). */
    fun focusNode(id: String) {
        val node = project.nodes.find { it.id == id } ?: return
        selectOnly(id)
        pan = canvasCenter() - Offset(node.centerX, node.centerY) * (zoom * viewDensity)
    }

    private fun canvasCenter() = Offset(canvasSize.width / 2f, canvasSize.height / 2f)

    // ---- Selection ----

    fun selectOnly(id: String) {
        selectedNodeIds = setOf(id)
    }

    fun toggleSelected(id: String) {
        selectedNodeIds = if (id in selectedNodeIds) selectedNodeIds - id else selectedNodeIds + id
    }

    fun clearSelection() {
        selectedNodeIds = emptySet()
    }

    /** Selects every node/element intersecting the current marquee rectangle, then clears the marquee. */
    fun applyMarqueeSelection() {
        val (a, b) = marquee ?: return
        marquee = null
        val w1 = screenToWorld(a)
        val w2 = screenToWorld(b)
        val left = min(w1.x, w2.x)
        val right = max(w1.x, w2.x)
        val top = min(w1.y, w2.y)
        val bottom = max(w1.y, w2.y)
        fun hits(x: Float, y: Float, w: Float, h: Float) = x < right && x + w > left && y < bottom && y + h > top
        val hit = buildSet {
            project.nodes.forEach { if (hits(it.x, it.y, it.width, it.height)) add(it.id) }
            project.elements.forEach { if (hits(it.x, it.y, it.width, it.height)) add(it.id) }
        }
        selectedNodeIds = hit
        if (hit.isNotEmpty()) status = "${hit.size} item${if (hit.size == 1) "" else "s"} selected"
    }

    // ---- Project lifecycle ----

    fun newProject() {
        currentFilePath = null
        replaceProject(Project.empty(), "New project created")
    }

    fun openSampleProject() {
        currentFilePath = null
        replaceProject(Project.sample(), "Sample project loaded")
    }

    fun loadProject(loaded: Project, sourcePath: String) {
        currentFilePath = sourcePath
        replaceProject(loaded, "Loaded $sourcePath")
    }

    private fun replaceProject(newProject: Project, message: String) {
        project = newProject
        lastSavedProject = newProject
        undoStack = emptyList()
        redoStack = emptyList()
        selectedNodeIds = emptySet()
        connectFromId = null
        editingNodeId = null
        editingElementId = null
        marquee = null
        resetView()
        status = message
    }

    fun renameProject(name: String) {
        project = project.copy(name = name)
    }

    // ---- Type catalog (built-in + project custom types) ----

    /** All node types available right now: built-ins followed by this project's custom ones. */
    val allNodeTypes: List<NodeTypeDef>
        get() = BuiltinTypes.nodes + project.customNodeTypes

    val allEdgeTypes: List<EdgeTypeDef>
        get() = BuiltinTypes.edges + project.customEdgeTypes

    /** Resolves a node/connection-type id to its definition (built-in or this project's custom). */
    fun nodeTypeOf(id: String): NodeTypeDef = project.nodeType(id)

    fun edgeTypeOf(id: String): EdgeTypeDef = project.edgeType(id)

    // ---- Recently used types (app-global, persisted; newest first) ----

    var recentNodeTypeIds by mutableStateOf(listOf<String>())
        private set
    var recentEdgeTypeIds by mutableStateOf(listOf<String>())
        private set
    private var recentsTypesLoaded = false

    fun loadRecentTypesIfNeeded() {
        if (!recentsTypesLoaded) {
            val (n, e) = RecentTypeStore.load()
            recentNodeTypeIds = n
            recentEdgeTypeIds = e
            recentsTypesLoaded = true
        }
    }

    private fun noteRecentNodeType(id: String) {
        loadRecentTypesIfNeeded()
        recentNodeTypeIds = (listOf(id) + recentNodeTypeIds.filterNot { it == id }).take(RECENT_TYPE_LIMIT)
        RecentTypeStore.save(recentNodeTypeIds, recentEdgeTypeIds)
    }

    private fun noteRecentEdgeType(id: String) {
        loadRecentTypesIfNeeded()
        recentEdgeTypeIds = (listOf(id) + recentEdgeTypeIds.filterNot { it == id }).take(RECENT_TYPE_LIMIT)
        RecentTypeStore.save(recentNodeTypeIds, recentEdgeTypeIds)
    }

    // ---- Custom type creation dialog ----

    enum class NewTypeKind { Node, Edge }

    var newTypeKind by mutableStateOf<NewTypeKind?>(null)
        private set
    private var newTypeApply: ((String) -> Unit)? = null

    /** Opens the "new custom type" dialog; [onCreated] applies the new type id where the user invoked it. */
    fun openNewType(kind: NewTypeKind, onCreated: (String) -> Unit) {
        newTypeKind = kind
        newTypeApply = onCreated
    }

    fun closeNewType() {
        newTypeKind = null
        newTypeApply = null
    }

    fun confirmNewType(label: String, colorArgb: Long) {
        val kind = newTypeKind ?: return
        // No pushUndo here: the apply callback (e.g. add node) handles its own
        // undo step, and keeping the created type around after an undo is desirable.
        val id = newId()
        val name = label.trim().ifBlank { "Custom" }
        when (kind) {
            NewTypeKind.Node -> {
                project = project.copy(customNodeTypes = project.customNodeTypes + NodeTypeDef(id, name, colorArgb))
                noteRecentNodeType(id)
            }
            NewTypeKind.Edge -> {
                project = project.copy(customEdgeTypes = project.customEdgeTypes + EdgeTypeDef(id, name, colorArgb))
                noteRecentEdgeType(id)
            }
        }
        newTypeApply?.invoke(id)
        status = "Created custom type \"$name\""
        closeNewType()
    }

    // ---- Node operations ----

    /** Most recently added node type id — reused for quick-add (double-click on empty canvas). */
    var lastNodeType by mutableStateOf(BuiltinTypes.DEFAULT_NODE)
        private set

    /** Adds a node near the center of the current viewport, cascading slightly so new nodes don't stack exactly. */
    fun addNode(typeId: String) {
        val center = screenToWorld(canvasCenter())
        val cascade = (project.nodes.size % 6) * 18f
        addNodeAt(center + Offset(cascade, cascade), typeId)
    }

    /** Adds a node centered on a world position (double-click on empty canvas). */
    fun addNodeAt(world: Offset, typeId: String = lastNodeType) {
        pushUndo()
        placeNodeOfType(world, typeId)
    }

    /** Shared body that creates a node without its own undo step (callers push first). */
    private fun placeNodeOfType(world: Offset, typeId: String) {
        lastNodeType = typeId
        noteRecentNodeType(typeId)
        val def = nodeTypeOf(typeId)
        val node = Node(
            id = newId(),
            type = typeId,
            title = "New ${def.label}",
            x = world.x - Node.DEFAULT_WIDTH / 2f,
            y = world.y - Node.DEFAULT_HEIGHT / 2f,
        )
        project = project.copy(nodes = project.nodes + node)
        selectOnly(node.id)
        status = "Added ${def.label}"
    }

    /** Sets a node's type and records it as recently used (called from the inspector type selector). */
    fun setNodeType(nodeId: String, typeId: String) {
        updateNode(nodeId) { it.copy(type = typeId) }
        noteRecentNodeType(typeId)
    }

    /** Sets an edge's type and records it as recently used. */
    fun setEdgeType(edgeId: String, typeId: String) {
        updateEdge(edgeId) { it.copy(type = typeId) }
        noteRecentEdgeType(typeId)
    }

    /** Applies [transform] to the node with [id]. All field edits in the inspector go through here. */
    fun updateNode(id: String, transform: (Node) -> Node) {
        project = project.copy(nodes = project.nodes.map { if (it.id == id) transform(it) else it })
    }

    /** Moves every selected node/element by [delta] (world units) — dragging one card drags the whole selection. */
    fun moveSelected(delta: Offset) {
        if (selectedNodeIds.isEmpty()) return
        project = project.copy(
            nodes = project.nodes.map {
                if (it.id in selectedNodeIds) it.copy(x = it.x + delta.x, y = it.y + delta.y) else it
            },
            elements = project.elements.map {
                if (it.id in selectedNodeIds) it.copy(x = it.x + delta.x, y = it.y + delta.y) else it
            },
        )
    }

    /** Snaps every selected item's top-left corner to the nearest grid point, lining them up with each other. */
    fun alignSelected() {
        if (selectedNodeIds.isEmpty()) {
            status = "Select nodes to align"
            return
        }
        pushUndo()
        fun snap(v: Float) = (v / GRID_SPACING).roundToInt() * GRID_SPACING
        project = project.copy(
            nodes = project.nodes.map { if (it.id in selectedNodeIds) it.copy(x = snap(it.x), y = snap(it.y)) else it },
            elements = project.elements.map { if (it.id in selectedNodeIds) it.copy(x = snap(it.x), y = snap(it.y)) else it },
        )
        status = "Aligned ${selectedNodeIds.size} item${if (selectedNodeIds.size == 1) "" else "s"} to grid"
    }

    /** Copies the selected nodes/elements (and the edges between selected nodes), offset slightly. */
    fun duplicateSelected() {
        if (selectedNodeIds.isEmpty()) return
        pushUndo()
        val offset = 24f
        val idMap = mutableMapOf<String, String>()
        val newNodes = project.nodes.filter { it.id in selectedNodeIds }.map { n ->
            val nid = newId()
            idMap[n.id] = nid
            n.copy(id = nid, x = n.x + offset, y = n.y + offset)
        }
        val newElements = project.elements.filter { it.id in selectedNodeIds }.map { e ->
            val eid = newId()
            idMap[e.id] = eid
            e.copy(id = eid, x = e.x + offset, y = e.y + offset)
        }
        val newEdges = project.edges
            .filter { it.fromNodeId in idMap && it.toNodeId in idMap }
            .map { it.copy(id = newId(), fromNodeId = idMap.getValue(it.fromNodeId), toNodeId = idMap.getValue(it.toNodeId)) }
        project = project.copy(
            nodes = project.nodes + newNodes,
            edges = project.edges + newEdges,
            elements = project.elements + newElements,
        )
        selectedNodeIds = idMap.values.toSet()
        status = "Duplicated ${idMap.size} item${if (idMap.size == 1) "" else "s"}"
    }

    // ---- Clipboard (internal, not the OS clipboard) ----

    private var clipboardNodes: List<Node> = emptyList()
    private var clipboardElements: List<CanvasElement> = emptyList()
    private var clipboardEdges: List<Edge> = emptyList()

    fun copySelection() {
        if (selectedNodeIds.isEmpty()) return
        clipboardNodes = project.nodes.filter { it.id in selectedNodeIds }
        clipboardElements = project.elements.filter { it.id in selectedNodeIds }
        clipboardEdges = project.edges.filter { it.fromNodeId in selectedNodeIds && it.toNodeId in selectedNodeIds }
        val count = clipboardNodes.size + clipboardElements.size
        status = "Copied $count item${if (count == 1) "" else "s"}"
    }

    fun cutSelection() {
        copySelection()
        deleteSelected()
    }

    /** Pastes the clipboard with a small offset; pasting again offsets again. */
    fun paste() {
        if (clipboardNodes.isEmpty() && clipboardElements.isEmpty()) return
        pushUndo()
        val offset = 28f
        val idMap = mutableMapOf<String, String>()
        val newNodes = clipboardNodes.map { n ->
            val nid = newId()
            idMap[n.id] = nid
            n.copy(id = nid, x = n.x + offset, y = n.y + offset)
        }
        val newElements = clipboardElements.map { e ->
            val eid = newId()
            idMap[e.id] = eid
            e.copy(id = eid, x = e.x + offset, y = e.y + offset)
        }
        val newEdges = clipboardEdges.map {
            it.copy(id = newId(), fromNodeId = idMap.getValue(it.fromNodeId), toNodeId = idMap.getValue(it.toNodeId))
        }
        project = project.copy(
            nodes = project.nodes + newNodes,
            edges = project.edges + newEdges,
            elements = project.elements + newElements,
        )
        // Re-offset the clipboard so repeated pastes cascade.
        clipboardNodes = newNodes
        clipboardElements = newElements
        clipboardEdges = newEdges
        selectedNodeIds = idMap.values.toSet()
        status = "Pasted ${idMap.size} item${if (idMap.size == 1) "" else "s"}"
    }

    fun selectAll() {
        selectedNodeIds = buildSet {
            project.nodes.forEach { add(it.id) }
            project.elements.forEach { add(it.id) }
        }
        if (selectedNodeIds.isNotEmpty()) status = "Selected all (${selectedNodeIds.size})"
    }

    fun deleteNode(id: String) = deleteItems(setOf(id))

    fun deleteSelected() = deleteItems(selectedNodeIds)

    private fun deleteItems(ids: Set<String>) {
        if (ids.isEmpty()) return
        val count = project.nodes.count { it.id in ids } + project.elements.count { it.id in ids }
        if (count == 0) return
        pushUndo()
        project = project.copy(
            nodes = project.nodes.filterNot { it.id in ids },
            elements = project.elements.filterNot { it.id in ids },
            // Edges referencing a deleted node would dangle, so remove them too.
            edges = project.edges.filterNot { it.fromNodeId in ids || it.toNodeId in ids },
        )
        selectedNodeIds = selectedNodeIds - ids
        if (connectFromId in ids) connectFromId = null
        if (editingNodeId in ids) editingNodeId = null
        if (editingElementId in ids) editingElementId = null
        status = if (count == 1) "Deleted 1 item" else "Deleted $count items"
    }

    // ---- Canvas element operations (text blocks, images) ----

    fun addTextElement() {
        pushUndo()
        val center = screenToWorld(canvasCenter())
        val element = CanvasElement(
            id = newId(),
            kind = ElementKind.Text,
            x = center.x - 110f,
            y = center.y - 45f,
            width = 220f,
            height = 90f,
        )
        project = project.copy(elements = project.elements + element)
        selectOnly(element.id)
        editingElementId = element.id
        status = "Added text block"
    }

    /** [imageWidth]/[imageHeight] are the decoded pixel dimensions, used to keep the aspect ratio. */
    fun addImageElement(name: String, base64: String, imageWidth: Int, imageHeight: Int) {
        if (imageWidth <= 0 || imageHeight <= 0) return
        pushUndo()
        val width = min(300f, imageWidth.toFloat())
        val height = width * imageHeight / imageWidth
        val center = screenToWorld(canvasCenter())
        val element = CanvasElement(
            id = newId(),
            kind = ElementKind.Image,
            x = center.x - width / 2f,
            y = center.y - height / 2f,
            width = width,
            height = height,
            text = name,
            content = base64,
        )
        project = project.copy(elements = project.elements + element)
        selectOnly(element.id)
        status = "Added image $name"
    }

    fun updateElement(id: String, transform: (CanvasElement) -> CanvasElement) {
        project = project.copy(elements = project.elements.map { if (it.id == id) transform(it) else it })
    }

    // ---- Attachment operations ----

    fun addAttachment(nodeId: String, attachment: Attachment) {
        pushUndo()
        updateNode(nodeId) { it.copy(attachments = it.attachments + attachment) }
        status = "Attached ${attachment.name}"
    }

    fun updateAttachment(nodeId: String, attachmentId: String, transform: (Attachment) -> Attachment) {
        updateNode(nodeId) { node ->
            node.copy(attachments = node.attachments.map { if (it.id == attachmentId) transform(it) else it })
        }
    }

    fun removeAttachment(nodeId: String, attachmentId: String) {
        pushUndo()
        updateNode(nodeId) { node ->
            node.copy(attachments = node.attachments.filterNot { it.id == attachmentId })
        }
    }

    // ---- Edge operations ----

    fun startConnection(fromId: String) {
        connectFromId = fromId
        status = "Click another node to connect"
    }

    fun cancelConnection() {
        if (connectFromId != null) {
            connectFromId = null
            status = "Connection cancelled"
        }
    }

    /**
     * Finishes a port-drag connection: connects to the node currently under the
     * pointer, or cancels if the drag ended on empty canvas.
     */
    fun completeConnectionAtPointer() {
        val from = connectFromId ?: return
        connectFromId = null
        val world = screenToWorld(pointerPos)
        val target = project.nodes.lastOrNull { n ->
            n.id != from && world.x >= n.x && world.x <= n.x + n.width && world.y >= n.y && world.y <= n.y + n.height
        }
        if (target != null) {
            addEdge(from, target.id)
        } else {
            status = "Connection cancelled"
        }
    }

    /**
     * Canvas click handler for nodes and elements: completes a pending
     * connection if one is in flight (nodes only), Ctrl-toggles membership in
     * the selection, or plain-selects.
     */
    fun nodeTapped(id: String) {
        val from = connectFromId
        when {
            from != null && from != id && project.nodes.any { it.id == id } -> {
                addEdge(from, id)
                connectFromId = null
            }
            ctrlDown -> toggleSelected(id)
            else -> selectOnly(id)
        }
    }

    fun addEdge(fromId: String, toId: String, label: String = "", type: String = BuiltinTypes.DEFAULT_EDGE) {
        if (fromId == toId) return
        if (project.nodes.none { it.id == fromId } || project.nodes.none { it.id == toId }) return
        if (project.edges.any { it.fromNodeId == fromId && it.toNodeId == toId }) {
            status = "Those nodes are already connected"
            return
        }
        pushUndo()
        project = project.copy(edges = project.edges + Edge(newId(), fromId, toId, label, type))
        noteRecentEdgeType(type)
        status = "Connected"
    }

    fun updateEdge(edgeId: String, transform: (Edge) -> Edge) {
        project = project.copy(edges = project.edges.map { if (it.id == edgeId) transform(it) else it })
    }

    fun deleteEdge(edgeId: String) {
        pushUndo()
        project = project.copy(edges = project.edges.filterNot { it.id == edgeId })
    }

    init {
        // A brand-new empty project has nothing worth saving yet.
        lastSavedProject = project
        loadRecentTypesIfNeeded()
    }

    companion object {
        const val MIN_ZOOM = 0.25f
        const val MAX_ZOOM = 3f
        const val UNDO_LIMIT = 50
        const val RECENT_TYPE_LIMIT = 6

        /** World-unit pitch of the canvas dot grid; Align snaps item corners to multiples of this. */
        const val GRID_SPACING = 26f
    }
}
