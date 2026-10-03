package com.nodenote.state

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.nodenote.api.MessageUpdate
import com.nodenote.model.Area
import com.nodenote.model.Attachment
import com.nodenote.model.BuiltinTypes
import com.nodenote.model.CanvasElement
import com.nodenote.model.ColorBy
import com.nodenote.model.Edge
import com.nodenote.model.EdgeTypeDef
import com.nodenote.model.ElementKind
import com.nodenote.model.ExportPreset
import com.nodenote.model.ExportSettings
import com.nodenote.model.Lens
import com.nodenote.model.Message
import com.nodenote.model.MessageKind
import com.nodenote.model.Node
import com.nodenote.model.NodeLinkRegex
import com.nodenote.model.NodeStatus
import com.nodenote.model.NodeTypeDef
import com.nodenote.model.Project
import com.nodenote.model.areaOf
import com.nodenote.model.areasInside
import com.nodenote.model.cardsIn
import com.nodenote.model.clampImportance
import com.nodenote.model.edgeType
import com.nodenote.model.fittedTo
import com.nodenote.model.mentionsIn
import com.nodenote.model.mentionsWord
import com.nodenote.model.newId
import com.nodenote.model.nodeType
import com.nodenote.model.nowMillis
import com.nodenote.model.privateAreaIds
import com.nodenote.model.privateNodeIds
import com.nodenote.model.rebase
import com.nodenote.layout.RadialLayout
import com.nodenote.sort.BoardHierarchy
import com.nodenote.spell.Misspelling
import com.nodenote.spell.ShopWords
import com.nodenote.spell.SpellChecker
import com.nodenote.sort.NodeMatcher
import com.nodenote.model.resolveNodeLinks
import com.nodenote.storage.ClientPrefs
import com.nodenote.storage.PresetStore
import com.nodenote.storage.RecentTypeStore
import kotlin.math.abs
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
class AppState(val session: Session) {

    /** Stable id for this open document, used by the [com.nodenote.state.Workspace] tab bar. */
    val tabId: String = newId()

    /** Short title for the tab: the board name, with a • while edits are still being sent. */
    val tabTitle: String
        get() {
            if (boardId == null) return "Home"
            val base = project.name.ifBlank { "Untitled" }
            return if (dirty) "$base •" else base
        }

    // ---- Document ----
    var project by mutableStateOf(Project.empty())
        private set

    /** Server board this tab shows, or null for the Home tab (board picker). */
    var boardId by mutableStateOf<String?>(null)
        private set

    /** The live connection to the server for [boardId]. */
    var sync: BoardSync? = null
        private set

    var syncStatus by mutableStateOf(SyncStatus.Loading)

    /**
     * The board as the server last confirmed it (null until loaded). Anything
     * in [project] that differs is still on its way — see [BoardSync].
     */
    var syncedProject by mutableStateOf<Project?>(null)

    val dirty: Boolean get() = syncedProject != null && project != syncedProject

    val isLoaded: Boolean get() = boardId != null && syncedProject != null

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
        if (freshNodeId != null && freshNodeId !in valid) freshNodeId = null
        if (editingElementId != null && editingElementId !in valid) editingElementId = null
        if (connectFromId != null && connectFromId !in valid) connectFromId = null
        if (selectedAreaId != null && project.areas.none { it.id == selectedAreaId }) selectedAreaId = null
        if (editingAreaId != null && project.areas.none { it.id == editingAreaId }) editingAreaId = null
        if (selectedEdgeId != null && project.edges.none { it.id == selectedEdgeId }) selectedEdgeId = null
    }

    // ---- Server board lifecycle ----

    /** Binds this tab to a server board and starts syncing it. */
    fun openBoard(id: String) {
        sync?.stop()
        boardId = id
        syncedProject = null
        project = Project(id = id, name = "Loading…")
        replaceMessages(emptyList())
        lastRead = ClientPrefs.lastRead(id)
        sync = BoardSync(this, session, id).also { it.start() }
    }

    /** The server doesn't have this board (any more): become a Home tab and stop remembering it. */
    fun boardMissing() {
        val id = boardId ?: return
        sync = null
        boardId = null
        syncedProject = null
        replaceProject(Project.empty(), "That board isn't on the server any more")
        ClientPrefs.openBoardIds = ClientPrefs.openBoardIds - id
    }

    /** Stops syncing (after one last push of pending edits). Called when the tab closes. */
    fun closeBoard() {
        sync?.flushAndStop()
        sync = null
    }

    /** First load from the server: replaces everything and resets the view. */
    fun loadFromServer(board: Project) {
        syncedProject = board
        replaceProject(board, "Opened ${board.name}")
        zoomToFitWhenSized = true
        adoptBuiltinTypes()
        session.loadSpellChecker()
    }

    /**
     * A custom type made before a built-in of the same name existed (a board's own
     * "Project") hands its nodes over to the built-in, so everyone sees one type. The
     * custom entry stays on the board (types are never deleted) but is no longer offered.
     */
    private fun adoptBuiltinTypes() {
        val builtinByLabel = BuiltinTypes.nodes.associateBy { it.label.trim().lowercase() }
        val remap = project.customNodeTypes.mapNotNull { custom ->
            builtinByLabel[custom.label.trim().lowercase()]?.let { custom.id to it.id }
        }.toMap()
        if (remap.isEmpty() || project.nodes.none { it.type in remap }) return
        project = project.copy(nodes = project.nodes.map { n -> remap[n.type]?.let { n.copy(type = it) } ?: n })
    }

    /**
     * The other person's changes arrived: replay our unsent edits (and our
     * undo/redo history) on top of the newer server board, so nothing of
     * theirs is lost and nothing of ours is either.
     */
    fun applyRemote(oldBase: Project, newBase: Project) {
        project = rebase(project, oldBase, newBase)
        undoStack = undoStack.map { rebase(it, oldBase, newBase) }
        redoStack = redoStack.map { rebase(it, oldBase, newBase) }
        syncedProject = newBase
        pruneStaleIds()
    }

    /** Set when a board loads; GraphCanvas frames the content once it knows its size. */
    var zoomToFitWhenSized = false

    // ---- UI / interaction state (not saved) ----

    /** Selection is a set of node AND element ids: click selects one, Ctrl+click toggles, marquee selects many. */
    var selectedNodeIds by mutableStateOf(setOf<String>())
        private set

    var status by mutableStateOf("Ready")

    /** Node whose title is being edited inline on the canvas (started by double-clicking the card). */
    var editingNodeId by mutableStateOf<String?>(null)

    /** Text element being edited inline on the canvas (started by double-clicking it). */
    var editingElementId by mutableStateOf<String?>(null)

    /** True while Ctrl is held — maintained by the window-level key handler, used for toggle-select clicks. */
    var ctrlDown by mutableStateOf(false)

    /** True while Alt is held — read from pointer events on the canvas; Alt+click/drag on a node draws a connection. */
    var altDown by mutableStateOf(false)

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

    // ---- Keyboard shortcuts help ----

    var shortcutsOpen by mutableStateOf(false)

    /** True when any modal is open — used to gate canvas/editing shortcuts. */
    val anyDialogOpen: Boolean
        get() = exportDialogOpen || shortcutsOpen || newTypeKind != null || rateBoardOpen

    // ---- Right panel (dp). Resizable by dragging its inner border, collapsible. ----

    enum class RightTab(val label: String) { Chat("Chat"), Sort("Sort"), Priorities("Priorities"), Details("Details") }

    var rightTab by mutableStateOf(RightTab.Chat)

    /** The three ways the Priorities tab answers "what now?". */
    enum class PrioritySection(val label: String) { QuickWins("Quick wins"), Wins("Wins"), KeyDetails("Key details") }

    var prioritySection by mutableStateOf(PrioritySection.QuickWins)
    var inspectorVisible by mutableStateOf(true)
    var inspectorWidth by mutableStateOf(380f)
        private set

    fun resizeInspector(deltaDp: Float) {
        inspectorWidth = (inspectorWidth - deltaDp).coerceIn(280f, 1600f)
    }

    /** Chat takes the whole middle of the window (the canvas is hidden) — for long reading/writing sessions. */
    var chatExpanded by mutableStateOf(false)

    // ---- Presenting ----
    // Private nodes are true but not for everyone's eyes. While presenting (a per-device
    // switch that survives reloads), they, their connections and their chat disappear from
    // the map, the lists and the feed, so the screen can be shared. A private area takes
    // everything inside it along.

    var presenting by mutableStateOf(ClientPrefs.presenting)
        private set

    fun togglePresenting() {
        presenting = !presenting
        ClientPrefs.presenting = presenting
        if (presenting) {
            val hidden = hiddenNodeIds
            selectedNodeIds = selectedNodeIds.filterTo(mutableSetOf()) { it !in hidden }
            if (editingNodeId in hidden) editingNodeId = null
            dropHiddenSelection()
        }
        status = if (presenting) "Presenting: private nodes are hidden" else "Private nodes are visible again"
    }

    private class Shown(val source: Project, val presenting: Boolean, val project: Project, val hidden: Set<String>)
    private var shownCache: Shown? = null

    private val shown: Shown
        get() {
            val p = project
            shownCache?.let { if (it.source === p && it.presenting == presenting) return it }
            val hidden = if (presenting) p.privateNodeIds() else emptySet()
            val hiddenAreas = if (presenting) p.privateAreaIds() else emptySet()
            val visible = if (hidden.isEmpty() && hiddenAreas.isEmpty()) p else p.copy(
                nodes = p.nodes.filter { it.id !in hidden },
                edges = p.edges.filter { it.fromNodeId !in hidden && it.toNodeId !in hidden },
                areas = p.areas.filter { it.id !in hiddenAreas },
            )
            return Shown(p, presenting, visible, hidden).also { shownCache = it }
        }

    /** The board as it may be shown right now: [project] minus private nodes and areas while presenting. */
    val shownProject: Project get() = shown.project

    /** Nodes hidden right now (the private ones, while presenting). */
    val hiddenNodeIds: Set<String> get() = shown.hidden

    private var privateCache: Pair<Project, Set<String>>? = null

    /** Cards that are private in effect, presenting or not: marked private, or sitting in a private area. */
    val privateIds: Set<String>
        get() {
            val p = project
            privateCache?.let { (cp, ids) -> if (cp === p) return ids }
            return p.privateNodeIds().also { privateCache = p to it }
        }

    /** After something went private while presenting: let go of a selected area or link that's no longer shown. */
    private fun dropHiddenSelection() {
        val shownAreas = shownProject.areas
        if (selectedAreaId != null && shownAreas.none { it.id == selectedAreaId }) selectedAreaId = null
        if (editingAreaId != null && shownAreas.none { it.id == editingAreaId }) editingAreaId = null
        if (selectedEdgeId != null && shownProject.edges.none { it.id == selectedEdgeId }) selectedEdgeId = null
    }

    private var visibleMessagesCache: Triple<List<Message>, Set<String>, List<Message>>? = null

    /** [messages] minus those filed under a hidden node. */
    val visibleMessages: List<Message>
        get() {
            val all = messages
            val hidden = hiddenNodeIds
            if (hidden.isEmpty()) return all
            visibleMessagesCache?.let { (m, h, v) -> if (m === all && h == hidden) return v }
            return all.filter { it.nodeId !in hidden }.also { visibleMessagesCache = Triple(all, hidden, it) }
        }

    /** Bumped to hand keyboard focus back to the canvas (after an inline edit), so Tab/Enter keep working. */
    var canvasFocusRequests by mutableStateOf(0)
        private set

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

    /** Back to 100% around the current view center (after fitting a small board, which would otherwise zoom in). */
    fun resetZoomKeepingCenter() = zoomBy(1f / zoom, canvasCenter())

    private class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

    private fun contentBounds(): Bounds? {
        val xs1 = project.nodes.map { Triple(it.x, it.y, Pair(it.width, it.height)) }
        val xs2 = project.elements.map { Triple(it.x, it.y, Pair(it.width, it.height)) }
        val xs3 = project.areas.map { Triple(it.x, it.y, Pair(it.width, it.height)) }
        val all = xs1 + xs2 + xs3
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
        leaveAreaAndLink()
    }

    fun toggleSelected(id: String) {
        selectedNodeIds = if (id in selectedNodeIds) selectedNodeIds - id else selectedNodeIds + id
        leaveAreaAndLink()
    }

    fun clearSelection() {
        selectedNodeIds = emptySet()
        leaveAreaAndLink()
    }

    /**
     * Selecting something else lets go of a selected area or link, and of an area name
     * being typed (an editor that had focus has already kept its text when it lost it).
     */
    private fun leaveAreaAndLink() {
        selectedAreaId = null
        selectedEdgeId = null
        editingAreaId = null
    }

    /** The box-drag on empty canvas ended: select what it covers, or (while drawing an area) make the area. */
    fun finishMarquee() {
        if (drawingArea) finishAreaDrawing() else applyMarqueeSelection()
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
        leaveAreaAndLink()
        if (hit.isNotEmpty()) status = "${hit.size} item${if (hit.size == 1) "" else "s"} selected"
    }

    // ---- Board document ----

    private fun replaceProject(newProject: Project, message: String) {
        project = newProject
        undoStack = emptyList()
        redoStack = emptyList()
        selectedNodeIds = emptySet()
        selectedAreaId = null
        editingAreaId = null
        selectedEdgeId = null
        drawingArea = false
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

    /** All node types offered right now: built-ins followed by this project's custom ones (minus any a built-in replaced). */
    val allNodeTypes: List<NodeTypeDef>
        get() {
            val builtinLabels = BuiltinTypes.nodes.mapTo(HashSet()) { it.label.trim().lowercase() }
            return BuiltinTypes.nodes + project.customNodeTypes.filter { it.label.trim().lowercase() !in builtinLabels }
        }

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

    /** Adds a node near the center of the current viewport (cascading so new nodes don't stack) and starts typing its title. */
    fun addNode(typeId: String) {
        val center = screenToWorld(canvasCenter())
        val cascade = (project.nodes.size % 6) * 18f
        addNodeAt(center + Offset(cascade, cascade), typeId)
    }

    /** Adds a node centered on a world position (double-click on empty canvas) and starts typing its title. */
    fun addNodeAt(world: Offset, typeId: String = lastNodeType) {
        pushUndo()
        val node = placeNodeOfType(world, typeId, title = "")
        beginFreshTitle(node.id, parentId = null)
    }

    /** Shared body that creates a node without its own undo step (callers push first). */
    private fun placeNodeOfType(world: Offset, typeId: String, title: String? = null): Node {
        lastNodeType = typeId
        noteRecentNodeType(typeId)
        val def = nodeTypeOf(typeId)
        val node = Node(
            id = newId(),
            type = typeId,
            title = title ?: "New ${def.label}",
            x = world.x - Node.DEFAULT_WIDTH / 2f,
            y = world.y - Node.DEFAULT_HEIGHT / 2f,
            // Issues, improvements and action items start out as open work.
            status = if (typeId in OPEN_BY_DEFAULT) NodeStatus.Open else NodeStatus.None,
            createdAt = nowMillis(),
            createdBy = session.userName,
        )
        project = project.copy(nodes = project.nodes + node)
        selectOnly(node.id)
        status = "Added ${def.label}"
        return node
    }

    /**
     * Mind-map style adding (Tab on a selected node): a new node connected to [parentId],
     * placed beside it on the side facing away from the goal, ready to type. Its type follows
     * the parent's other children (siblings are usually alike), else the last type used.
     */
    fun addChildNode(parentId: String, typeId: String? = null) {
        val parent = project.nodes.find { it.id == parentId } ?: return
        if (parentId in hiddenNodeIds) return
        pushUndo()
        val node = placeNodeOfType(freeSpotBeside(parent), typeId ?: childTypeFor(parent), title = "")
        // Children point at their parent, like the rest of the map.
        project = project.copy(edges = project.edges + Edge(newId(), node.id, parent.id))
        beginFreshTitle(node.id, parentId)
    }

    /** Enter on a selected node: another child of its parent, of the same type. The root (the goal) gets a child instead. */
    fun addSiblingNode(nodeId: String) {
        val node = project.nodes.find { it.id == nodeId } ?: return
        val parent = parentOf(nodeId)
        if (parent == null) addChildNode(nodeId) else addChildNode(parent.id, node.type)
    }

    private fun childTypeFor(parent: Node): String {
        val childIds = hierarchy.parents.filterValues { it == parent.id }.keys
        val common = project.nodes.filter { it.id in childIds }.groupingBy { it.type }.eachCount().maxByOrNull { it.value }?.key
        return common ?: lastNodeType.takeIf { it != "Goal" } ?: BuiltinTypes.DEFAULT_NODE
    }

    /** A node created a moment ago whose title is being typed. Enter then adds the next sibling; left empty, it goes away again. */
    var freshNodeId by mutableStateOf<String?>(null)
        private set
    private var freshParentId: String? = null
    private var freshUndoDepth = -1

    private fun beginFreshTitle(nodeId: String, parentId: String?) {
        freshNodeId = nodeId
        freshParentId = parentId
        freshUndoDepth = undoStack.size
        editingNodeId = nodeId
    }

    /** Double-click or F2: edit an existing node's title in place. */
    fun startRenaming(nodeId: String) {
        if (project.nodes.none { it.id == nodeId }) return
        selectOnly(nodeId)
        freshNodeId = null
        editingNodeId = nodeId
    }

    /** How the inline title editor was left. */
    enum class TitleEditEnd { Enter, Tab, ShiftTab, Escape, Blur }

    /**
     * The inline title editor closed. A new node left empty disappears as if never added.
     * After typing a new child, Enter adds the next sibling and Tab a child of it, so a whole
     * branch can be typed out without the mouse; Shift+Tab steps back out a level. Escape on
     * an existing node restores the title it had.
     */
    fun endTitleEdit(nodeId: String, end: TitleEditEnd, originalTitle: String) {
        if (editingNodeId != nodeId) return
        val fresh = freshNodeId == nodeId
        val parentId = freshParentId
        editingNodeId = null
        freshNodeId = null
        finishTitleEdit(nodeId, end, originalTitle, fresh, parentId)
        // Unless another title is being typed now, keys go back to the canvas (Tab, Enter, Delete…).
        if (editingNodeId == null) canvasFocusRequests++
    }

    private fun finishTitleEdit(nodeId: String, end: TitleEditEnd, originalTitle: String, fresh: Boolean, parentId: String?) {
        val node = project.nodes.find { it.id == nodeId } ?: return
        val title = node.title.trim()
        if (fresh && title.isEmpty()) {
            if (end == TitleEditEnd.Tab) beginFreshTitle(nodeId, parentId) // nothing to hang a child on yet
            else discardFreshNode(nodeId, parentId)
            return
        }
        if (end == TitleEditEnd.Escape && !fresh) {
            updateNode(nodeId) { it.copy(title = originalTitle) }
            return
        }
        if (title != node.title) updateNode(nodeId) { it.copy(title = title) }
        when (end) {
            TitleEditEnd.Enter -> if (fresh && parentId != null) addChildNode(parentId, node.type)
            TitleEditEnd.Tab -> addChildNode(nodeId)
            TitleEditEnd.ShiftTab -> parentOf(nodeId)?.let { addSiblingNode(it.id) }
            else -> Unit
        }
    }

    /** Takes back a node that was added but never named — without leaving an undo or redo step behind. */
    private fun discardFreshNode(id: String, parentId: String?) {
        val before = undoStack.lastOrNull()
        if (undoStack.size == freshUndoDepth && before != null && before.nodes.none { it.id == id }) {
            undoStack = undoStack.dropLast(1)
            project = before
        } else {
            project = project.copy(
                nodes = project.nodes.filterNot { it.id == id },
                edges = project.edges.filterNot { it.fromNodeId == id || it.toNodeId == id },
            )
        }
        selectedNodeIds = parentId?.takeIf { pid -> project.nodes.any { it.id == pid } }?.let { setOf(it) } ?: emptySet()
        status = "Ready"
    }

    /** Sets a node's type and records it as recently used. */
    fun setNodeType(nodeId: String, typeId: String) {
        updateNode(nodeId) { it.copy(type = typeId) }
        noteRecentNodeType(typeId)
        lastNodeType = typeId
    }

    /** Status change, keeping [Node.solvedAt] in step — it dates the Wins list. */
    fun setNodeStatus(id: String, status: NodeStatus) {
        val node = project.nodes.find { it.id == id } ?: return
        if (node.status == status) return
        pushUndo()
        updateNode(id) {
            it.copy(
                status = status,
                solvedAt = when {
                    status != NodeStatus.Solved -> 0L
                    it.solvedAt > 0 -> it.solvedAt
                    else -> nowMillis()
                },
            )
        }
        if (status == NodeStatus.Solved) this.status = "Solved: \"${node.title}\" is on the Wins list"
    }

    fun setNodeImportance(id: String, value: Int) {
        pushUndo()
        updateNode(id) { it.copy(importance = clampImportance(value)) }
    }

    fun setNodeEffort(id: String, value: Int) {
        pushUndo()
        updateNode(id) { it.copy(effort = clampImportance(value)) }
    }

    fun setNodePrivate(id: String, private: Boolean) {
        pushUndo()
        updateNode(id) { it.copy(isPrivate = private) }
        status = when {
            !private -> "No longer private"
            presenting -> "Private: hidden while presenting"
            else -> "Private: hidden when presenting and left out of exports"
        }
        if (private && presenting) selectedNodeIds = selectedNodeIds - id
    }

    /**
     * Resizes a card around its top-left corner; everything on it (text included) scales.
     * [pushUndoStep] is false for the moves of a drag after its first.
     */
    fun setNodeScale(id: String, scale: Float, pushUndoStep: Boolean = true) {
        val node = project.nodes.find { it.id == id } ?: return
        val s = scale.coerceIn(Node.MIN_SCALE, Node.MAX_SCALE)
        if (s == node.scale) return
        if (pushUndoStep) pushUndo()
        updateNode(id) { n -> n.copy(scale = s, width = n.width / n.scale * s, height = n.height / n.scale * s) }
    }

    /** Nodes whose title this device just changed: their card may shrink back to fit, not only grow. */
    private val shrinkAllowed = HashSet<String>()

    fun titleEditedLocally(id: String) {
        shrinkAllowed.add(id)
    }

    /**
     * Keeps a card tall enough for its whole title ([neededBase] = content height at scale 1).
     * Cards only grow by themselves and shrink back only after this device edited the title,
     * so two screens measuring text a hair differently can't fight over the height.
     */
    fun fitNodeHeight(id: String, neededBase: Float) {
        val node = project.nodes.find { it.id == id } ?: return
        val target = max(Node.DEFAULT_HEIGHT, neededBase) * node.scale
        val shrink = shrinkAllowed.remove(id)
        if (target > node.height + 0.5f || (shrink && target < node.height - 0.5f)) {
            updateNode(id) { it.copy(height = target) }
        }
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

    /**
     * Tidy, careful with the places the user gave things: a selected area (or the area of
     * the one selected card) is laid out inside itself; several selected cards are laid out
     * around their hub; with nothing selected the whole map is laid out around its goal,
     * but only while the board has no areas (they'd be scrambled), and only on a second
     * click (it moves everything the user placed). Each branch keeps its
     * side, rings spread until no cards overlap (see [RadialLayout]). One undo step.
     */
    fun tidyLayout() {
        val single = selectedNodeIds.singleOrNull()?.let { id -> project.nodes.find { it.id == id } }
        val area = selectedArea?.takeIf { selectedNodeIds.isEmpty() } ?: single?.let { project.areaOf(it) }
        when {
            area != null -> tidyArea(area.id)
            selectedNodeIds.size >= 2 -> {
                val ids = project.nodes.filter { it.id in selectedNodeIds }.mapTo(HashSet()) { it.id }
                if (ids.size < 2) return
                pushUndo()
                placeTidied(ids)
                status = "Tidied the ${ids.size} selected cards around their hub (Ctrl+Z puts them back)"
            }
            single != null -> status = "Tidy works inside an area: put this card in one, or select a group of cards"
            project.areas.isNotEmpty() -> status = "Select an area (click its name) to tidy inside it; the rest of the map stays put"
            project.nodes.size < 2 -> status = "Nothing to tidy"
            // Where things sit is part of the thinking: moving every card takes a second click.
            nowMillis() - tidyAskedAt > TIDY_CONFIRM_MILLIS -> {
                tidyAskedAt = nowMillis()
                status = "Tidy moves every card on the map. Click Tidy again to go ahead (Ctrl+Z puts it back)"
            }
            else -> {
                tidyAskedAt = 0L
                pushUndo()
                placeTidied(project.nodes.mapTo(HashSet()) { it.id })
                zoomToFit()
                status = "Tidied the map around the goal (Ctrl+Z puts it back)"
            }
        }
    }

    private var tidyAskedAt = 0L

    /** Lays out just the cards in [ids] (and the links between them); their hub stays where it is. */
    private fun placeTidied(ids: Set<String>) {
        val sub = project.copy(
            nodes = project.nodes.filter { it.id in ids },
            edges = project.edges.filter { it.fromNodeId in ids && it.toNodeId in ids },
            areas = emptyList(),
        )
        val positions = RadialLayout.layout(sub)
        project = project.copy(nodes = project.nodes.map { n -> positions[n.id]?.let { (x, y) -> n.copy(x = x, y = y) } ?: n })
    }

    /**
     * Tidies the cards inside one area around their hub, compactly, centred where the area
     * is, so the territory stays in its place. The area grows if the cards need more room
     * but never shrinks by itself ("Fit to cards" does that on purpose).
     */
    fun tidyArea(areaId: String) {
        val area = project.areas.find { it.id == areaId } ?: return
        val cards = project.cardsIn(area)
        val ids = cards.mapTo(HashSet()) { it.id }
        if (ids.size < 2) {
            status = "Nothing to tidy in ${area.title.ifBlank { "this area" }}"
            return
        }
        pushUndo()
        val sub = project.copy(
            nodes = cards,
            edges = project.edges.filter { it.fromNodeId in ids && it.toNodeId in ids },
            areas = emptyList(),
        )
        val positions = RadialLayout.layout(sub, RadialLayout.Options(nodeGap = 22f, levelGap = 36f, islandGap = 60f))
        val laid = cards.map { n -> positions[n.id]?.let { (x, y) -> n.copy(x = x, y = y) } ?: n }
        // Centre the new arrangement on the area's room for cards (below its name).
        val dx = (area.x + area.right) / 2f - (laid.minOf { it.x } + laid.maxOf { it.x + it.width }) / 2f
        val dy = (area.y + Area.TITLE_ROOM + area.bottom) / 2f - (laid.minOf { it.y } + laid.maxOf { it.y + it.height }) / 2f
        val moved = laid.associate { it.id to it.copy(x = it.x + dx, y = it.y + dy) }
        project = project.copy(nodes = project.nodes.map { moved[it.id] ?: it })
        val fitted = area.fittedTo(moved.values.toList())
        val left = min(area.x, fitted.x)
        val top = min(area.y, fitted.y)
        updateArea(areaId) {
            it.copy(x = left, y = top, width = max(area.right, fitted.right) - left, height = max(area.bottom, fitted.bottom) - top)
        }
        status = "Tidied ${area.title.ifBlank { "the area" }}; the rest of the map stayed put (Ctrl+Z puts it back)"
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

    fun deleteSelected() {
        val area = selectedAreaId
        val edge = selectedEdgeId
        when {
            selectedNodeIds.isNotEmpty() -> deleteItems(selectedNodeIds)
            area != null -> removeArea(area)
            edge != null -> deleteEdge(edge)
        }
    }

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
        if (freshNodeId in ids) freshNodeId = null
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
        if (selectedEdgeId == edgeId) selectedEdgeId = null
    }

    // ---- Link selection (click a connection on the map) ----

    /** The connection clicked on the map; its bar sets what the link means. */
    var selectedEdgeId by mutableStateOf<String?>(null)
        private set

    fun selectEdge(id: String) {
        selectedNodeIds = emptySet()
        leaveAreaAndLink()
        selectedEdgeId = id
    }

    /** The connection drawn under a click at [screen] (within a few pixels of its line), if any. */
    fun edgeAt(screen: Offset): String? {
        val p = shownProject
        val byId = p.nodes.associateBy { it.id }
        var best: String? = null
        var bestDistance = 7f * viewDensity
        for (e in p.edges) {
            val a = byId[e.fromNodeId] ?: continue
            val b = byId[e.toNodeId] ?: continue
            val d = distanceToSegment(screen, worldToScreen(Offset(a.centerX, a.centerY)), worldToScreen(Offset(b.centerX, b.centerY)))
            if (d < bestDistance) {
                bestDistance = d
                best = e.id
            }
        }
        return best
    }

    private fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
        val ab = b - a
        val len2 = ab.x * ab.x + ab.y * ab.y
        val t = if (len2 < 1e-6f) 0f else (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / len2).coerceIn(0f, 1f)
        return (p - (a + ab * t)).getDistance()
    }

    /** What a link means (Relates, Causes, Solves…), one undo step. */
    fun setLinkType(edgeId: String, typeId: String) {
        val edge = project.edges.find { it.id == edgeId } ?: return
        if (edge.type == typeId) return
        pushUndo()
        setEdgeType(edgeId, typeId)
        status = "Link: ${edgeTypeOf(typeId).label}"
    }

    /** Turns a link round (a Solves arrow drawn from the problem to the fix, say). */
    fun flipEdge(edgeId: String) {
        val edge = project.edges.find { it.id == edgeId } ?: return
        if (project.edges.any { it.fromNodeId == edge.toNodeId && it.toNodeId == edge.fromNodeId }) {
            status = "There's already a link the other way"
            return
        }
        pushUndo()
        updateEdge(edgeId) { it.copy(fromNodeId = it.toNodeId, toNodeId = it.fromNodeId) }
        status = "Link turned round"
    }

    // ---- Areas (named territories behind groups of cards) ----

    /** The area whose name was clicked (an area is selected on its own, not with cards). */
    var selectedAreaId by mutableStateOf<String?>(null)
        private set

    /** Area whose name is being typed on the map. */
    var editingAreaId by mutableStateOf<String?>(null)

    /** True after "Area" with nothing selected: the next box-drag on the canvas draws the area. */
    var drawingArea by mutableStateOf(false)
        private set

    val selectedArea: Area? get() = selectedAreaId?.let { id -> project.areas.find { it.id == id } }

    fun selectArea(id: String) {
        selectedAreaId = id
        selectedNodeIds = emptySet()
        selectedEdgeId = null
        if (editingAreaId != id) editingAreaId = null
    }

    /**
     * The "Area" button: around the selected cards; around one selected card and the cards
     * that hang off it (named after it); with nothing selected, draw one by dragging a box.
     */
    fun areaFromSelection() {
        val selected = project.nodes.filter { it.id in selectedNodeIds && it.id !in hiddenNodeIds }
        when {
            selected.size == 1 -> addAreaAroundBranch(selected.single().id)
            selected.isNotEmpty() -> addArea(selected, title = "")
            drawingArea -> cancelAreaDrawing()
            else -> {
                drawingArea = true
                clearSelection()
                status = "Drag a box on the map to draw the area (Esc cancels)"
            }
        }
    }

    /** An area around a card and its direct children on the map, named after the card. */
    fun addAreaAroundBranch(nodeId: String) {
        val node = project.nodes.find { it.id == nodeId } ?: return
        val children = hierarchy.parents.filterValues { it == nodeId }.keys
        val cards = project.nodes.filter { (it.id == nodeId || it.id in children) && it.id !in hiddenNodeIds }
        addArea(cards, title = node.title.trim())
    }

    private fun addArea(cards: List<Node>, title: String) {
        if (cards.isEmpty()) return
        pushUndo()
        val area = Area(
            id = newId(), title = title, x = 0f, y = 0f, width = 0f, height = 0f,
            color = nextAreaColor(), createdAt = nowMillis(), createdBy = session.userName,
        ).fittedTo(cards)
        placeNewArea(area)
    }

    private fun placeNewArea(area: Area) {
        project = project.copy(areas = project.areas + area)
        selectArea(area.id)
        editingAreaId = area.id
        status = "Name the area, then Enter. Cards dragged into it join it; drag its name to move it all"
    }

    /** The palette color the fewest areas use, so neighbors tell apart. */
    private fun nextAreaColor(): Long {
        val used = project.areas.groupingBy { it.tint }.eachCount()
        return Area.Palette.minBy { used[it] ?: 0 }
    }

    fun cancelAreaDrawing() {
        if (!drawingArea) return
        drawingArea = false
        marquee = null
        status = "Ready"
    }

    private fun finishAreaDrawing() {
        val (a, b) = marquee ?: return
        marquee = null
        drawingArea = false
        val w1 = screenToWorld(a)
        val w2 = screenToWorld(b)
        val left = min(w1.x, w2.x)
        val top = min(w1.y, w2.y)
        val width = max(abs(w2.x - w1.x), Area.MIN_WIDTH)
        val height = max(abs(w2.y - w1.y), Area.MIN_HEIGHT)
        pushUndo()
        placeNewArea(
            Area(
                id = newId(), x = left, y = top, width = width, height = height,
                color = nextAreaColor(), createdAt = nowMillis(), createdBy = session.userName,
            ),
        )
    }

    fun startRenamingArea(id: String) {
        if (project.areas.none { it.id == id }) return
        selectArea(id)
        editingAreaId = id
    }

    /** The name editor closed; [title] is what was typed (null = Escape: keep the old name). */
    fun finishAreaTitle(id: String, title: String?) {
        if (editingAreaId != id) return
        editingAreaId = null
        val area = project.areas.find { it.id == id } ?: return
        val name = title?.trim() ?: return
        if (name != area.title) {
            pushUndo()
            updateArea(id) { it.copy(title = name) }
        }
        canvasFocusRequests++
    }

    fun updateArea(id: String, transform: (Area) -> Area) {
        project = project.copy(areas = project.areas.map { if (it.id == id) transform(it) else it })
    }

    fun setAreaColor(id: String, color: Long) {
        val area = project.areas.find { it.id == id } ?: return
        if (area.tint == color) return
        pushUndo()
        updateArea(id) { it.copy(color = color) }
    }

    fun setAreaPrivate(id: String, private: Boolean) {
        val area = project.areas.find { it.id == id } ?: return
        pushUndo()
        updateArea(id) { it.copy(isPrivate = private) }
        val name = area.title.ifBlank { "This area" }
        status = when {
            !private -> "$name is no longer private"
            presenting -> "$name and everything in it: hidden while presenting"
            else -> "$name and everything in it: hidden when presenting, left out of exports"
        }
        if (private && presenting) {
            selectedAreaId = null
            dropHiddenSelection()
        }
    }

    /** Shrinks or grows the area to hug the cards inside it. */
    fun fitArea(id: String) {
        val area = project.areas.find { it.id == id } ?: return
        val cards = project.cardsIn(area)
        if (cards.isEmpty()) {
            status = "No cards in ${area.title.ifBlank { "this area" }} yet: drag some in"
            return
        }
        pushUndo()
        updateArea(id) { it.fittedTo(cards) }
    }

    /** Takes the area away; its cards stay where they are. */
    fun removeArea(id: String) {
        val area = project.areas.find { it.id == id } ?: return
        pushUndo()
        project = project.copy(areas = project.areas.filterNot { it.id == id })
        if (selectedAreaId == id) selectedAreaId = null
        if (editingAreaId == id) editingAreaId = null
        status = "Removed the area ${area.title.ifBlank { "" }}".trim() + "; its cards stay put (Ctrl+Z brings it back)"
    }

    private class AreaDrag(val areaIds: Set<String>, val nodeIds: Set<String>, val elementIds: Set<String>)
    private var areaDrag: AreaDrag? = null

    /** Dragging an area's name: it moves with its cards, notes, pictures and any area inside it. One undo step. */
    fun beginAreaDrag(id: String) {
        val area = project.areas.find { it.id == id } ?: return
        pushUndo()
        selectArea(id)
        areaDrag = AreaDrag(
            areaIds = setOf(id) + project.areasInside(area).map { it.id },
            nodeIds = project.cardsIn(area).mapTo(HashSet()) { it.id },
            elementIds = project.elements.filter { area.contains(it.x + it.width / 2f, it.y + it.height / 2f) }.mapTo(HashSet()) { it.id },
        )
    }

    /** [delta] in world units. */
    fun dragArea(delta: Offset) {
        val d = areaDrag ?: return
        project = project.copy(
            areas = project.areas.map { if (it.id in d.areaIds) it.copy(x = it.x + delta.x, y = it.y + delta.y) else it },
            nodes = project.nodes.map { if (it.id in d.nodeIds) it.copy(x = it.x + delta.x, y = it.y + delta.y) else it },
            elements = project.elements.map { if (it.id in d.elementIds) it.copy(x = it.x + delta.x, y = it.y + delta.y) else it },
        )
    }

    fun endAreaDrag() {
        areaDrag = null
    }

    /** Moves an area's bottom-right corner by [delta] (world units); the cards stay put. Undo is pushed by the caller at drag start. */
    fun resizeArea(id: String, delta: Offset) {
        updateArea(id) {
            it.copy(width = max(Area.MIN_WIDTH, it.width + delta.x), height = max(Area.MIN_HEIGHT, it.height + delta.y))
        }
    }

    // ---- Color by (what the card colors mean; per device) ----

    var colorBy by mutableStateOf(ClientPrefs.colorBy)
        private set

    fun changeColorBy(by: ColorBy) {
        colorBy = by
        ClientPrefs.colorBy = by
        status = if (by == ColorBy.Type) "Cards colored by type again" else "Cards colored by ${by.label.lowercase()}: ${by.hint}"
    }

    private var lensCache: Lens? = null

    /** Colors for the cards under [colorBy], rebuilt when the board changes. */
    val lens: Lens
        get() {
            val p = project
            lensCache?.let { if (it.project === p && it.by == colorBy) return it }
            return Lens(p, colorBy).also { lensCache = it }
        }

    // ---- Rate by placing ----

    /** The importance × effort board where cards are rated by dragging them into place. */
    var rateBoardOpen by mutableStateOf(false)

    /** Both ratings at once (one undo step): what dropping a card on the rating board does. */
    fun rateNode(id: String, importance: Int, effort: Int) {
        val node = project.nodes.find { it.id == id } ?: return
        val i = clampImportance(importance)
        val e = clampImportance(effort)
        if (node.importance == i && node.effort == e) return
        pushUndo()
        updateNode(id) { it.copy(importance = i, effort = e) }
    }

    // ---- Chat ----
    // All live messages of this board, oldest first (deleted ones are dropped).
    // Threads are derived views: a node's thread is its own messages plus any
    // message elsewhere that links to it.

    var messages by mutableStateOf(listOf<Message>())
        private set
    private val messageIndex = HashMap<String, Message>()

    /** Posted here but not yet confirmed by the server. */
    var pendingMessageIds by mutableStateOf(setOf<String>())
        private set

    /** Posting failed; shown with a retry affordance. */
    var failedMessageIds by mutableStateOf(setOf<String>())
        private set

    /** Thread key ("" = inbox) → highest seq this user has read there. Per device, see [ClientPrefs]. */
    var lastRead by mutableStateOf(mapOf<String, Long>())
        private set

    /** Per-thread counters for node cards and lists, recomputed when messages or read marks change. */
    var threadStats by mutableStateOf(mapOf<String, ThreadStats>())
        private set

    // Chat view filters and composer state (per tab, not saved).
    var chatMinImportance by mutableStateOf(0)
    var chatKindFilter by mutableStateOf<MessageKind?>(null)
    var chatSearch by mutableStateOf("")

    /** Board feed shows only unfiled inbox messages. */
    var feedInboxOnly by mutableStateOf(false)
    var composerKind by mutableStateOf(MessageKind.Note)
    var composerImportance by mutableStateOf(0)
    var keyDetailThreshold by mutableStateOf(7)
    var keyDetailWholeBoard by mutableStateOf(false)

    /** Message whose text is being edited in the composer, or null when composing a new one. */
    var editingMessageId by mutableStateOf<String?>(null)

    /** Something is typed in a message box — an automatic reload must wait. */
    var draftActive by mutableStateOf(false)

    // ---- Sorting (the Unsorted pile) ----

    /** Thoughts dumped without a node, oldest first — the pile the Sort tab works through. */
    val unsorted: List<Message> get() = messages.filter { it.nodeId.isEmpty() }

    private var matcherCache: Triple<Project, List<Message>, NodeMatcher>? = null
    private var hierarchyCache: Pair<Project, BoardHierarchy>? = null

    /** Keyword matcher over the current board + its filed messages (rebuilt when either changes). */
    val matcher: NodeMatcher
        get() {
            val p = project
            val m = messages
            matcherCache?.let { (cp, cm, matcher) -> if (cp === p && cm === m) return matcher }
            return NodeMatcher(p, m).also { matcherCache = Triple(p, m, it) }
        }

    /** Each node's parent in the tree that grows out from the board's main goal. */
    val hierarchy: BoardHierarchy
        get() {
            val p = project
            hierarchyCache?.let { (cp, h) -> if (cp === p) return h }
            return BoardHierarchy(p).also { hierarchyCache = p to it }
        }

    fun parentOf(nodeId: String): Node? = hierarchy.parents[nodeId]?.let { id -> project.nodes.find { it.id == id } }

    /** Up to three nodes a thought probably belongs under. */
    fun suggestionsFor(text: String, linkedNodeIds: List<String> = emptyList()): List<Node> =
        matcher.suggest(text, 4, linkedNodeIds).mapNotNull { s -> project.nodes.find { it.id == s.nodeId && it.id !in hiddenNodeIds } }.take(3)

    // ---- People and search ----

    /** Someone named with an @ on this board, and how many nodes and messages mention them (with or without the @). */
    data class Person(val name: String, val mentions: Int)

    private var peopleCache: Triple<Project, List<Message>, List<Person>>? = null

    /** Everyone @-mentioned in titles, notes or chat, most mentioned first. Hidden nodes don't count while presenting. */
    val people: List<Person>
        get() {
            val p = shownProject
            val m = visibleMessages
            peopleCache?.let { (cp, cm, people) -> if (cp === p && cm === m) return people }
            return computePeople(p, m).also { peopleCache = Triple(p, m, it) }
        }

    private fun computePeople(p: Project, msgs: List<Message>): List<Person> {
        val names = LinkedHashMap<String, String>()
        fun collect(text: String) {
            for (name in mentionsIn(text)) names.getOrPut(name.lowercase()) { name }
        }
        p.nodes.forEach { collect(it.title); collect(it.description); collect(it.summary) }
        msgs.forEach { collect(it.text) }
        return names.values.map { name ->
            val inNodes = p.nodes.count { mentionsWord(it.title, name) || mentionsWord(it.description, name) || mentionsWord(it.summary, name) }
            Person(name, inNodes + msgs.count { mentionsWord(it.text, name) })
        }.sortedWith(compareByDescending<Person> { it.mentions }.thenBy { it.name.lowercase() })
    }

    /** Ids of nodes whose chat (own messages, or mentions of them) contains [query]. */
    fun nodesWithChatMatching(query: String): Set<String> {
        if (query.isBlank()) return emptySet()
        val hits = HashSet<String>()
        for (m in visibleMessages) {
            if (m.text.contains(query, ignoreCase = true)) {
                hits.add(m.nodeId)
                hits.addAll(m.linkedNodeIds)
            }
        }
        return hits
    }

    /** Text search over a node's own fields (title, notes, owner, keywords, properties). */
    fun nodeTextMatches(node: Node, query: String): Boolean =
        query.isBlank() || node.title.contains(query, ignoreCase = true) ||
            node.description.contains(query, ignoreCase = true) || node.summary.contains(query, ignoreCase = true) ||
            node.owner.contains(query, ignoreCase = true) || node.keywords.contains(query, ignoreCase = true) ||
            node.properties.values.any { it.contains(query, ignoreCase = true) }

    // ---- Spelling ----

    /** Words this person told the spell checker to accept (kept on this device). */
    var customWords by mutableStateOf(ClientPrefs.dictionaryWords)
        private set

    fun addToDictionary(word: String) {
        val w = word.trim().lowercase()
        if (w.isEmpty() || w in customWords) return
        customWords = customWords + w
        ClientPrefs.dictionaryWords = customWords
        status = "\"$word\" won't be marked as a typo any more"
    }

    private var spellCache: Triple<SpellChecker, List<String>, SpellChecker>? = null

    /** The spell checker plus this board's @people and this person's own words; null until the word list has loaded. */
    val spell: SpellChecker?
        get() {
            val base = session.spellChecker ?: return null
            val extra = ShopWords + people.map { it.name } + customWords
            spellCache?.let { (b, e, checker) -> if (b === base && e == extra) return checker }
            return base.withExtraWords(extra).also { spellCache = Triple(base, extra, it) }
        }

    /** Misspelled words in [text] (none until the word list has loaded). */
    fun typosIn(text: String): List<Misspelling> = spell?.misspellings(text).orEmpty()

    /** Replaces one misspelled word in a node's title (one undo step). */
    fun fixTitleTypo(nodeId: String, typo: Misspelling, replacement: String) {
        val node = project.nodes.find { it.id == nodeId } ?: return
        if (typo.end > node.title.length || node.title.substring(typo.start, typo.end) != typo.word) return
        pushUndo()
        titleEditedLocally(nodeId)
        updateNode(nodeId) { it.copy(title = it.title.replaceRange(typo.start, typo.end, replacement)) }
        status = "Fixed \"${typo.word}\" to \"$replacement\""
    }

    /** Filter the node list and the chat feed to one person ("" = clear). */
    var personFilter by mutableStateOf("")
        private set

    /** Everything about one person: the node list and the chat feed narrowed to their name ("" clears both). */
    fun showPerson(name: String) {
        personFilter = name
        chatSearch = name
        if (name.isNotEmpty()) {
            clearSelection()
            feedInboxOnly = false
            rightTab = RightTab.Chat
            inspectorVisible = true
        }
    }

    fun messageById(id: String): Message? = messageIndex[id]

    /** The node's thread: its own messages plus messages elsewhere that link to it (none from hidden nodes). */
    fun threadOf(nodeId: String): List<Message> =
        visibleMessages.filter { it.nodeId == nodeId || nodeId in it.linkedNodeIds }

    fun replaceMessages(all: List<Message>) {
        messageIndex.clear()
        pendingMessageIds = emptySet()
        failedMessageIds = emptySet()
        mergeMessages(all)
    }

    /** Merges server messages; newer seq wins, so a late poll never undoes an optimistic edit. */
    fun mergeMessages(incoming: List<Message>) {
        for (m in incoming) {
            val existing = messageIndex[m.id]
            if (existing == null || existing.seq == 0L || m.seq > existing.seq) messageIndex[m.id] = m
        }
        if (incoming.isNotEmpty()) {
            val confirmed = incoming.mapTo(HashSet()) { it.id }
            pendingMessageIds = pendingMessageIds - confirmed
            failedMessageIds = failedMessageIds - confirmed
        }
        rebuildMessageViews()
    }

    /** Local-only change (optimistic send/edit, or reverting one). */
    fun upsertLocalMessage(message: Message, pending: Boolean = false) {
        messageIndex[message.id] = message
        if (pending) {
            pendingMessageIds = pendingMessageIds + message.id
            failedMessageIds = failedMessageIds - message.id
        }
        rebuildMessageViews()
    }

    fun markMessageFailed(id: String) {
        pendingMessageIds = pendingMessageIds - id
        failedMessageIds = failedMessageIds + id
    }

    private fun rebuildMessageViews() {
        messages = messageIndex.values
            .filterNot { it.deleted }
            .sortedWith(compareBy<Message>({ it.createdAt }, { it.seq }))
        recomputeThreadStats()
    }

    private fun recomputeThreadStats() {
        val me = session.userName
        val stats = HashMap<String, ThreadStats>()
        fun add(key: String, m: Message, home: Boolean) {
            val s = stats[key] ?: ThreadStats()
            val unread = m.author != me && m.seq > (lastRead[key] ?: 0L)
            stats[key] = ThreadStats(
                count = s.count + if (home) 1 else 0,
                unread = s.unread + if (unread) 1 else 0,
                maxImportance = maxOf(s.maxImportance, m.importance),
                lastAt = maxOf(s.lastAt, m.createdAt),
                openActions = s.openActions + if (home && m.kind == MessageKind.Action && !m.done) 1 else 0,
            )
        }
        for (m in messages) {
            add(m.nodeId, m, home = true)
            // A mention lights up the node it points at, too.
            for (linked in m.linkedNodeIds) if (linked != m.nodeId) add(linked, m, home = false)
        }
        threadStats = stats
    }

    /** Marks everything currently in a thread ("" = inbox) as read by this user. */
    fun markThreadRead(nodeId: String) {
        val thread = if (nodeId.isEmpty()) messages.filter { it.nodeId.isEmpty() } else threadOf(nodeId)
        val top = thread.maxOfOrNull { it.seq } ?: return
        if ((lastRead[nodeId] ?: 0L) >= top) return
        lastRead = lastRead + (nodeId to top)
        boardId?.let { ClientPrefs.saveLastRead(it, lastRead) }
        recomputeThreadStats()
    }

    fun markAllRead() {
        val tops = HashMap<String, Long>()
        for (m in messages) {
            tops[m.nodeId] = maxOf(tops[m.nodeId] ?: 0L, m.seq)
            for (linked in m.linkedNodeIds) tops[linked] = maxOf(tops[linked] ?: 0L, m.seq)
        }
        lastRead = lastRead + tops
        boardId?.let { ClientPrefs.saveLastRead(it, lastRead) }
        recomputeThreadStats()
        status = "Marked everything read"
    }

    val totalUnread: Int get() = threadStats.values.sumOf { it.unread }

    // ---- Chat actions ----

    /** Posts [text] to a node's thread — by default the selected node, or the board inbox when nothing is selected. */
    fun sendMessage(text: String, nodeId: String = selectedNode?.id ?: "") {
        val board = boardId ?: return
        val body = text.trim()
        if (body.isEmpty()) return
        if (session.userName.isBlank()) {
            status = "Sign in first"
            return
        }
        val message = Message(
            id = newId(),
            boardId = board,
            nodeId = nodeId,
            author = session.userName,
            text = body,
            kind = composerKind,
            importance = clampImportance(composerImportance),
            linkedNodeIds = resolveNodeLinks(body, project).filter { it != nodeId },
            createdAt = nowMillis(), // shown until the server stamps its own time
        )
        sync?.send(message)
        composerImportance = 0
        composerKind = MessageKind.Note
    }

    fun retryMessage(id: String) {
        sync?.retry(id)
    }

    fun editMessageText(id: String, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        val original = messageById(id) ?: return
        val links = resolveNodeLinks(body, project).filter { it != original.nodeId }
        sync?.update(id, MessageUpdate(text = body, linkedNodeIds = links)) {
            it.copy(text = body, linkedNodeIds = links, editedAt = nowMillis())
        }
    }

    fun setMessageImportance(id: String, importance: Int) {
        val value = clampImportance(importance)
        sync?.update(id, MessageUpdate(importance = value)) { it.copy(importance = value) }
    }

    fun setMessageKind(id: String, kind: MessageKind) {
        sync?.update(id, MessageUpdate(kind = kind)) { it.copy(kind = kind) }
    }

    fun setMessageDone(id: String, done: Boolean) {
        sync?.update(id, MessageUpdate(done = done)) { it.copy(done = done) }
    }

    fun deleteMessage(id: String) {
        sync?.update(id, MessageUpdate(deleted = true)) { it.copy(deleted = true) }
        if (editingMessageId == id) editingMessageId = null
    }

    /** Files a message under another node ("" = back to the inbox). */
    fun moveMessage(id: String, toNodeId: String) {
        val m = messageById(id) ?: return
        val links = m.linkedNodeIds.filter { it != toNodeId }
        sync?.update(id, MessageUpdate(nodeId = toNodeId, linkedNodeIds = links)) {
            it.copy(nodeId = toNodeId, linkedNodeIds = links)
        }
        val where = project.nodes.find { it.id == toNodeId }?.title ?: "the inbox"
        status = "Moved message to $where"
    }

    /**
     * Braindump triage: turns a message into a new node (titled from its first
     * line, typed from its kind) placed in view, and files the message under it.
     */
    fun makeNodeFromMessage(id: String, parentId: String? = null) {
        val m = messageById(id) ?: return
        val firstLine = m.text.lineSequence().first().replace(NodeLinkRegex) { it.groupValues[1] }
        val title = firstLine.trim().take(60).ifBlank { "New topic" }
        val type = when (m.kind) {
            MessageKind.Issue -> "Issue"
            MessageKind.Idea -> "Idea"
            MessageKind.Decision -> "Decision"
            MessageKind.Action -> "ActionItem"
            MessageKind.Fix -> "Improvement"
            MessageKind.Note -> "Topic"
        }
        pushUndo()
        val parent = parentId?.let { pid -> project.nodes.find { it.id == pid } }
        val spot = parent?.let { freeSpotBeside(it) } ?: run {
            val cascade = (project.nodes.size % 6) * 18f
            screenToWorld(canvasCenter()) + Offset(cascade, cascade)
        }
        placeNodeOfType(spot, type)
        val node = selectedNode ?: return
        updateNode(node.id) { it.copy(title = title, importance = m.importance) }
        // Children point at their parent, like the rest of the map.
        if (parent != null) project = project.copy(edges = project.edges + Edge(newId(), node.id, parent.id))
        moveMessage(id, node.id)
        status = "Created node \"$title\"" + (parent?.let { " under ${it.title}" } ?: "")
    }

    /**
     * Center point for a new child of [parent]: the first free spot on a ring
     * around it, starting on the side facing away from the parent's own parent
     * (so the map keeps growing outward from the goal).
     */
    private fun freeSpotBeside(parent: Node): Offset {
        val grand = parentOf(parent.id)
        val outward = if (grand != null) {
            kotlin.math.atan2(parent.centerY - grand.centerY, parent.centerX - grand.centerX)
        } else {
            0.0f
        }
        fun free(cx: Float, cy: Float): Boolean {
            val l = cx - Node.DEFAULT_WIDTH / 2 - 24
            val t = cy - Node.DEFAULT_HEIGHT / 2 - 24
            val r = cx + Node.DEFAULT_WIDTH / 2 + 24
            val b = cy + Node.DEFAULT_HEIGHT / 2 + 24
            return project.nodes.none { n -> n.x < r && n.x + n.width > l && n.y < b && n.y + n.height > t }
        }
        for (radius in listOf(240f, 360f, 480f)) {
            for (step in 0 until 12) {
                // 0, +30°, -30°, +60°, … around the outward direction
                val offset = ((step + 1) / 2) * (if (step % 2 == 0) 1 else -1) * (kotlin.math.PI.toFloat() / 6f)
                val angle = outward + offset
                val cx = parent.centerX + radius * kotlin.math.cos(angle)
                val cy = parent.centerY + radius * kotlin.math.sin(angle)
                if (free(cx, cy)) return Offset(cx, cy)
            }
        }
        return Offset(parent.centerX + 240f, parent.centerY + 120f)
    }

    init {
        loadRecentTypesIfNeeded()
    }

    companion object {
        const val MIN_ZOOM = 0.1f
        const val MAX_ZOOM = 3f
        const val UNDO_LIMIT = 50
        const val RECENT_TYPE_LIMIT = 6

        /** How long the whole-map Tidy waits for its second click. */
        private const val TIDY_CONFIRM_MILLIS = 6_000L

        /** World-unit pitch of the canvas dot grid; Align snaps item corners to multiples of this. */
        const val GRID_SPACING = 26f

        /** Node types that start life with status Open. */
        private val OPEN_BY_DEFAULT = setOf("Issue", "Improvement", "ActionItem")
    }
}

/** Counters for one chat thread (a node, or "" for the board inbox). */
data class ThreadStats(
    /** Messages filed under this node (not counting mentions from elsewhere). */
    val count: Int = 0,
    /** Messages by someone else — here, or mentioning here — newer than my read mark. */
    val unread: Int = 0,
    val maxImportance: Int = 0,
    val lastAt: Long = 0,
    val openActions: Int = 0,
)
