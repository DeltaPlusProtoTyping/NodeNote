package com.nodenote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.nodenote.state.Workspace
import com.nodenote.theme.AppTheme
import com.nodenote.ui.MainScreen

/**
 * Shared entry point for every platform: one [Workspace] (multiple open tabs),
 * one theme, one screen. Desktop passes its own workspace so the window-level
 * keyboard shortcuts (Ctrl+S, Delete, …) can reach the focused tab.
 *
 * MainScreen provides LocalAppState per pane, so it isn't set here.
 */
@Composable
fun App(workspace: Workspace = remember { Workspace() }) {
    AppTheme {
        MainScreen(workspace)
    }
}
