package com.nodenote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.nodenote.state.Session
import com.nodenote.state.Workspace
import com.nodenote.storage.setPageTitle
import com.nodenote.theme.AppTheme
import com.nodenote.ui.LoginScreen
import com.nodenote.ui.MainScreen
import com.nodenote.ui.handleShortcut

/**
 * Shared entry point for every platform: the sign-in screen, or — once signed in —
 * one [Workspace] (open board tabs) and the main screen. The workspace lives only
 * while signed in; signing out stops all syncing.
 *
 * Keyboard shortcuts: the desktop window routes keys itself (it gets the workspace
 * through [onWorkspace]); the browser sets [handleKeysInside] so the root composable
 * handles them.
 */
@Composable
fun App(
    session: Session = remember { Session() },
    modifier: Modifier = Modifier,
    handleKeysInside: Boolean = false,
    onWorkspace: (Workspace?) -> Unit = {},
) {
    AppTheme {
        if (!session.signedIn) {
            LoginScreen(session, modifier)
        } else {
            // A new place for the boards (this device ↔ a server) starts a fresh set of tabs.
            val workspace = remember(session.api) { Workspace(session) }
            DisposableEffect(workspace) {
                onWorkspace(workspace)
                onDispose {
                    onWorkspace(null)
                    workspace.dispose()
                }
            }
            // Unread messages across all open boards, in the browser tab title.
            val unread = workspace.tabs.sumOf { it.totalUnread }
            LaunchedEffect(unread) { setPageTitle(if (unread > 0) "($unread) NodeNote" else "NodeNote") }
            val keys = if (handleKeysInside) Modifier.onPreviewKeyEvent { handleShortcut(workspace, it) } else Modifier
            MainScreen(workspace, modifier.then(keys))
        }
    }
}
