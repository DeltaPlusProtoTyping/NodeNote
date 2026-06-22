package com.nodenote.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nodenote.model.Project
import com.nodenote.storage.ProjectSerializer
import com.nodenote.storage.ProjectStorage

/** How the canvas area is divided between two open tabs. */
enum class SplitMode { None, SideBySide, Stacked }

/**
 * Top-level multi-document state: the open tabs (each a full [AppState]), the
 * file-explorer state, and the split layout. Per-document logic stays in
 * [AppState]; this just manages which documents are open and shown.
 *
 * Panes: [pane0] is always shown. When [split] is not [SplitMode.None], [pane1]
 * is shown alongside it. [focusedPane] decides which tab drives the toolbar,
 * inspector, notes, keyboard shortcuts and dialogs.
 */
class Workspace {

    val tabs = mutableStateListOf<AppState>()

    var pane0 by mutableStateOf<AppState?>(null)
        private set
    var pane1 by mutableStateOf<AppState?>(null)
        private set
    var split by mutableStateOf(SplitMode.None)
        private set
    var focusedPane by mutableStateOf(0)
        private set

    // ---- File explorer ----
    var rootDir by mutableStateOf(ProjectStorage.homeDirectory())
    var fileExplorerVisible by mutableStateOf(true)
    var fileExplorerWidth by mutableStateOf(250f)
        private set

    fun resizeFileExplorer(deltaDp: Float) {
        fileExplorerWidth = (fileExplorerWidth + deltaDp).coerceIn(170f, 460f)
    }

    init {
        newTab() // start with one empty document
    }

    /** The tab whose state drives the toolbar/inspector/keyboard right now. */
    val focused: AppState
        get() = (if (focusedPane == 1 && split != SplitMode.None) pane1 else pane0) ?: pane0 ?: tabs.first()

    /** The tab currently shown in a given pane (0 or 1). */
    fun paneTab(index: Int): AppState? = if (index == 1) pane1 else pane0

    fun isFocused(state: AppState): Boolean = focused === state

    // ---- Tab management ----

    fun newTab(): AppState {
        val s = AppState()
        tabs.add(s)
        showInFocusedPane(s)
        return s
    }

    /** Brings an existing tab into the focused pane (clicking a tab in the bar). */
    fun selectTab(state: AppState) {
        showInFocusedPane(state)
    }

    private fun showInFocusedPane(state: AppState) {
        if (focusedPane == 1 && split != SplitMode.None) pane1 = state else { pane0 = state; focusedPane = 0 }
    }

    /** Shows [state] in a specific pane (used by per-pane tab strips when split) and focuses that pane. */
    fun showTabInPane(index: Int, state: AppState) {
        focusPane(index)
        if (index == 1 && split != SplitMode.None) pane1 = state else pane0 = state
    }

    /** Opens a new blank tab directly in a specific pane. */
    fun newTabInPane(index: Int): AppState {
        val s = AppState()
        tabs.add(s)
        showTabInPane(index, s)
        return s
    }

    fun focusPane(index: Int) {
        focusedPane = if (index == 1 && split != SplitMode.None) 1 else 0
    }

    fun closeTab(state: AppState) {
        val idx = tabs.indexOf(state)
        if (idx < 0) return
        tabs.removeAt(idx)
        if (tabs.isEmpty()) {
            // Never leave zero tabs open.
            val fresh = AppState()
            tabs.add(fresh)
            pane0 = fresh
            pane1 = null
            split = SplitMode.None
            focusedPane = 0
            return
        }
        val replacement = tabs[idx.coerceAtMost(tabs.size - 1)]
        if (pane0 === state) pane0 = replacement
        if (pane1 === state) pane1 = tabs.firstOrNull { it !== pane0 } ?: pane0
        if (split != SplitMode.None && pane1 === pane0) split = SplitMode.None
        if (pane1 == null) split = SplitMode.None
    }

    // ---- Splits ----

    fun setSplitMode(mode: SplitMode) {
        if (mode == SplitMode.None) {
            split = SplitMode.None
            pane1 = null
            focusedPane = 0
            return
        }
        split = mode
        if (pane1 == null || pane1 === pane0) {
            // Put a different tab in the second pane, creating one if needed.
            pane1 = tabs.firstOrNull { it !== pane0 } ?: AppState().also { tabs.add(it) }
        }
    }

    // ---- Opening projects ----

    /** Opens a project file into a new tab (used by the file explorer). Returns the tab, or null on failure. */
    fun openFileInNewTab(path: String): AppState? {
        // If already open, just focus it.
        tabs.firstOrNull { it.currentFilePath == path }?.let {
            selectTab(it)
            navigateExplorerTo(path)
            return it
        }
        val content = ProjectStorage.readFromPath(path) ?: run {
            focused.status = "Could not read $path"
            return null
        }
        val project = try {
            ProjectSerializer.decode(content)
        } catch (e: Exception) {
            focused.status = "Not a valid project file: $path"
            return null
        }
        val s = AppState()
        s.loadProject(project, path)
        s.noteRecentFile(path)
        tabs.add(s)
        showInFocusedPane(s)
        navigateExplorerTo(path)
        return s
    }

    /** Points the file explorer at the folder containing [filePath]. */
    fun navigateExplorerTo(filePath: String) {
        ProjectStorage.parentDirectory(filePath)?.let { rootDir = it }
    }

    /** Shows the native open dialog and opens the chosen project in a new tab. */
    fun openViaDialog() {
        val result = ProjectStorage.loadProject() ?: return
        openFileInNewTab(result.first)
    }

    /** Replaces the focused tab's document with a blank project (the New action). */
    fun newProjectInFocusedTab() {
        focused.newProject()
    }

    /** Opens the built-in sample project in a new tab. */
    fun openSampleInNewTab() {
        val s = AppState()
        s.openSampleProject()
        tabs.add(s)
        showInFocusedPane(s)
    }
}
