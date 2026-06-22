package com.nodenote

import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.nodenote.state.AppState
import com.nodenote.state.Workspace
import com.nodenote.storage.ProjectActions
import com.nodenote.theme.ThemeState

private val arrowKeys = setOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown)

/** Windows/desktop entry point. Run with: gradlew :shared:run */
fun main() = application {
    // Restore the saved appearance theme once, before the window content composes.
    remember { ThemeState.load() }
    val workspace = remember { Workspace() }
    val focused = workspace.focused
    Window(
        onCloseRequest = ::exitApplication,
        // Title reflects the focused tab; "•" marks unsaved changes.
        title = "NodeNote — ${focused.project.name.ifBlank { "Untitled" }}${if (focused.dirty) " •" else ""}",
        state = rememberWindowState(width = 1400.dp, height = 900.dp),
        // Preview pass sees every key first (reliable even when no Compose node
        // has focus). All shortcuts act on the focused tab. Delete is guarded by
        // activeTextFields so it never fires while typing in a field.
        onPreviewKeyEvent = { event ->
            val state = workspace.focused
            // Keep Ctrl state current on every tab so toggle-select works in any pane.
            workspace.tabs.forEach { it.ctrlDown = event.isCtrlPressed }
            if (event.type != KeyEventType.KeyDown) return@Window false
            val inDialog = state.anyDialogOpen
            val typing = state.activeTextFields > 0
            when {
                event.key == Key.Escape && state.newTypeKind != null -> {
                    state.closeNewType()
                    true
                }
                event.key == Key.Escape && state.shortcutsOpen -> {
                    state.shortcutsOpen = false
                    true
                }
                event.key == Key.Escape && state.compareDiff != null -> {
                    state.closeCompareDiff()
                    true
                }
                event.key == Key.Escape && state.exportDialogOpen -> {
                    state.closeExportDialog()
                    true
                }
                event.key == Key.F1 && !typing -> {
                    state.shortcutsOpen = !state.shortcutsOpen
                    true
                }
                event.isCtrlPressed && event.key == Key.S -> {
                    ProjectActions.save(state)
                    true
                }
                event.isCtrlPressed && event.key == Key.Z && !inDialog && !typing -> {
                    if (event.isShiftPressed) state.redo() else state.undo()
                    true
                }
                event.isCtrlPressed && event.key == Key.Y && !inDialog && !typing -> {
                    state.redo()
                    true
                }
                event.isCtrlPressed && event.key == Key.D && !inDialog && !typing -> {
                    state.duplicateSelected()
                    true
                }
                event.key == Key.Delete && !typing && !inDialog && state.selectedNodeIds.isNotEmpty() -> {
                    state.deleteSelected()
                    true
                }
                event.isCtrlPressed && event.key == Key.C && !typing && !inDialog -> {
                    state.copySelection()
                    true
                }
                event.isCtrlPressed && event.key == Key.X && !typing && !inDialog -> {
                    state.cutSelection()
                    true
                }
                event.isCtrlPressed && event.key == Key.V && !typing && !inDialog -> {
                    state.paste()
                    true
                }
                event.isCtrlPressed && event.key == Key.A && !typing && !inDialog -> {
                    state.selectAll()
                    true
                }
                event.isCtrlPressed && event.key == Key.Equals -> {
                    state.zoomIn()
                    true
                }
                event.isCtrlPressed && event.key == Key.Minus -> {
                    state.zoomOut()
                    true
                }
                event.isCtrlPressed && event.key == Key.Zero -> {
                    state.resetView()
                    true
                }
                event.key == Key.Escape && !inDialog && !typing && state.connectFromId != null -> {
                    state.cancelConnection()
                    true
                }
                event.key == Key.Escape && !inDialog && !typing && state.selectedNodeIds.isNotEmpty() -> {
                    state.clearSelection()
                    true
                }
                !typing && !inDialog && state.selectedNodeIds.isNotEmpty() && event.key in arrowKeys -> {
                    val step = if (event.isShiftPressed) AppState.GRID_SPACING else 2f
                    val delta = when (event.key) {
                        Key.DirectionLeft -> Offset(-step, 0f)
                        Key.DirectionRight -> Offset(step, 0f)
                        Key.DirectionUp -> Offset(0f, -step)
                        else -> Offset(0f, step)
                    }
                    state.moveSelected(delta)
                    true
                }
                else -> false
            }
        },
    ) {
        App(workspace)
    }
}
