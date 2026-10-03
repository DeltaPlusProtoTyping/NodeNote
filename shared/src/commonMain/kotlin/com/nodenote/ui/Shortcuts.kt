package com.nodenote.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.nodenote.state.AppState
import com.nodenote.state.Workspace

private val arrowKeys = setOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown)

/**
 * Keyboard shortcuts for the whole app, shared by the desktop window
 * (onPreviewKeyEvent on the Window) and the browser (on the root composable).
 * Everything acts on the focused tab; editing shortcuts pause while a text
 * field has focus (AppState.activeTextFields) or a dialog is open.
 */
fun handleShortcut(workspace: Workspace, event: KeyEvent): Boolean {
    val state = workspace.focused
    // Cmd on macOS browsers behaves like Ctrl.
    val ctrl = event.isCtrlPressed || event.isMetaPressed
    // Keep Ctrl state current on every tab so toggle-select works in any pane.
    workspace.tabs.forEach { it.ctrlDown = ctrl }
    if (event.type != KeyEventType.KeyDown) return false
    val inDialog = state.anyDialogOpen || workspace.settingsOpen || workspace.newBoardOpen
    val typing = state.activeTextFields > 0
    return when {
        event.key == Key.Escape && workspace.newBoardOpen -> {
            workspace.newBoardOpen = false
            true
        }
        event.key == Key.Escape && workspace.settingsOpen && workspace.session.userName.isNotBlank() -> {
            workspace.settingsOpen = false
            true
        }
        event.key == Key.Escape && state.newTypeKind != null -> {
            state.closeNewType()
            true
        }
        event.key == Key.Escape && state.shortcutsOpen -> {
            state.shortcutsOpen = false
            true
        }
        event.key == Key.Escape && state.exportDialogOpen -> {
            state.closeExportDialog()
            true
        }
        event.key == Key.Escape && state.rateBoardOpen -> {
            state.rateBoardOpen = false
            true
        }
        event.key == Key.Escape && state.drawingArea -> {
            state.cancelAreaDrawing()
            true
        }
        event.key == Key.F1 && !typing -> {
            state.shortcutsOpen = !state.shortcutsOpen
            true
        }
        // Everything saves automatically; swallow Ctrl+S so the browser's "Save page" never pops up.
        ctrl && event.key == Key.S -> {
            state.status = if (state.dirty) "Saving…" else "All changes are saved to the server"
            true
        }
        ctrl && event.key == Key.Z && !inDialog && !typing -> {
            if (event.isShiftPressed) state.redo() else state.undo()
            true
        }
        ctrl && event.key == Key.Y && !inDialog && !typing -> {
            state.redo()
            true
        }
        ctrl && event.key == Key.D && !inDialog && !typing -> {
            state.duplicateSelected()
            true
        }
        (event.key == Key.Delete || event.key == Key.Backspace && ctrl) && !typing && !inDialog &&
            (state.selectedNodeIds.isNotEmpty() || state.selectedAreaId != null || state.selectedEdgeId != null) -> {
            state.deleteSelected()
            true
        }
        // Ctrl+G: an area around the selected cards (nothing selected: drag a box for it).
        ctrl && event.key == Key.G && !typing && !inDialog -> {
            state.areaFromSelection()
            true
        }
        ctrl && event.key == Key.C && !typing && !inDialog && state.selectedNodeIds.isNotEmpty() -> {
            state.copySelection()
            true
        }
        ctrl && event.key == Key.X && !typing && !inDialog && state.selectedNodeIds.isNotEmpty() -> {
            state.cutSelection()
            true
        }
        ctrl && event.key == Key.V && !typing && !inDialog -> {
            state.paste()
            true
        }
        ctrl && event.key == Key.A && !typing && !inDialog -> {
            state.selectAll()
            true
        }
        ctrl && event.key == Key.Equals -> {
            state.zoomIn()
            true
        }
        ctrl && event.key == Key.Minus -> {
            state.zoomOut()
            true
        }
        ctrl && event.key == Key.Zero -> {
            state.resetView()
            true
        }
        // Mind-map keys on the selected node: Tab = new child, Enter = new sibling, F2 = rename.
        event.key == Key.Tab && !typing && !inDialog && state.selectedNode != null -> {
            state.addChildNode(state.selectedNode!!.id)
            true
        }
        (event.key == Key.Enter || event.key == Key.NumPadEnter) && !typing && !inDialog && state.selectedNode != null -> {
            state.addSiblingNode(state.selectedNode!!.id)
            true
        }
        event.key == Key.F2 && !typing && !inDialog && state.selectedNode != null -> {
            state.startRenaming(state.selectedNode!!.id)
            true
        }
        event.key == Key.F2 && !typing && !inDialog && state.selectedAreaId != null -> {
            state.startRenamingArea(state.selectedAreaId!!)
            true
        }
        event.key == Key.Escape && !inDialog && !typing && state.connectFromId != null -> {
            state.cancelConnection()
            true
        }
        event.key == Key.Escape && !inDialog && !typing &&
            (state.selectedNodeIds.isNotEmpty() || state.selectedAreaId != null || state.selectedEdgeId != null) -> {
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
}
