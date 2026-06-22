package com.nodenote.storage

import com.nodenote.state.AppState

/**
 * Save/open glue between the UI and platform storage. Lives here (not in the
 * toolbar) because the same actions are triggered from buttons and from
 * keyboard shortcuts (Ctrl+S in the desktop window handler).
 */
object ProjectActions {

    /** Saves to the project's known file without a dialog; falls back to Save As for never-saved projects. */
    fun save(state: AppState) {
        val path = state.currentFilePath
        if (path == null) {
            saveAs(state)
            return
        }
        val ok = ProjectStorage.writeToPath(path, ProjectSerializer.encode(state.project))
        if (ok) {
            state.markSaved()
            state.noteRecentFile(path)
            state.status = "Saved $path"
        } else {
            state.status = "Could not write $path — try Save As"
        }
    }

    /** Always shows the file dialog, then remembers the chosen path for future plain saves. */
    fun saveAs(state: AppState) {
        val fileName = state.project.name
            .replace(Regex("[^A-Za-z0-9 _-]"), "")
            .trim()
            .ifBlank { "project" } + ".json"
        val path = ProjectStorage.saveProject(ProjectSerializer.encode(state.project), fileName)
        if (path != null) {
            state.currentFilePath = path
            state.markSaved()
            state.noteRecentFile(path)
            state.status = "Saved $path"
        } else {
            state.status = "Save cancelled"
        }
    }

    /** Open via the platform file dialog. */
    fun open(state: AppState) {
        val result = ProjectStorage.loadProject()
        if (result == null) {
            state.status = "Open cancelled"
            return
        }
        loadContent(state, result.first, result.second)
    }

    /** Open a known path directly (Open Recent). */
    fun openPath(state: AppState, path: String) {
        val content = ProjectStorage.readFromPath(path)
        if (content == null) {
            state.status = "Could not read $path — file may have moved"
            state.dropRecentFile(path)
            return
        }
        loadContent(state, path, content)
    }

    private fun loadContent(state: AppState, path: String, content: String) {
        try {
            state.loadProject(ProjectSerializer.decode(content), path)
            state.noteRecentFile(path)
        } catch (e: Exception) {
            state.status = "Could not open $path — not a valid project file"
        }
    }

    /**
     * Compares the open project against a file chosen from the dialog and shows
     * the difference summary. Does not modify the open project.
     */
    fun compare(state: AppState) {
        val result = ProjectStorage.loadProject()
        if (result == null) {
            state.status = "Compare cancelled"
            return
        }
        val (path, content) = result
        val other = try {
            ProjectSerializer.decode(content)
        } catch (e: Exception) {
            state.status = "Could not compare $path — not a valid project file"
            return
        }
        val name = path.substringAfterLast('\\').substringAfterLast('/')
        state.showCompareDiff(ProjectDiffer.diff(state.project, other, name))
    }
}
