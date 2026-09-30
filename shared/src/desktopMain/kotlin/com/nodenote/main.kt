package com.nodenote

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.nodenote.state.Session
import com.nodenote.state.Workspace
import com.nodenote.theme.ThemeState
import com.nodenote.ui.handleShortcut

/** Windows/desktop entry point. Run with: gradlew :shared:run (the server runs separately: gradlew :server:run). */
fun main() = application {
    // Restore the saved appearance theme once, before the window content composes.
    remember { ThemeState.load() }
    val session = remember { Session() }
    var workspace by remember { mutableStateOf<Workspace?>(null) }
    Window(
        onCloseRequest = ::exitApplication,
        // Title reflects the focused tab; "•" marks edits still being sent to the server.
        title = workspace?.let { ws ->
            val unread = ws.tabs.sumOf { it.totalUnread }
            (if (unread > 0) "($unread) " else "") + "NodeNote — ${ws.focused.tabTitle}"
        } ?: "NodeNote — sign in",
        state = rememberWindowState(width = 1500.dp, height = 920.dp),
        // Preview pass sees every key first (reliable even when no Compose node
        // has focus). All shortcuts act on the focused tab — see handleShortcut.
        onPreviewKeyEvent = { event -> workspace?.let { handleShortcut(it, event) } ?: false },
    ) {
        App(session, onWorkspace = { workspace = it })
    }
}
