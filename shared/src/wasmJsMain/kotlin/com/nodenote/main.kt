package com.nodenote

import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.nodenote.state.Session
import com.nodenote.theme.ThemeState
import kotlinx.browser.document

/**
 * Browser entry point. The server serves this app, so the page's own origin is
 * the API address (see defaultServerUrl). Build: gradlew :shared:wasmJsBrowserDistribution
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ThemeState.load()
    document.getElementById("loading")?.remove()
    ComposeViewport(document.body!!) {
        App(remember { Session() }, handleKeysInside = true)
    }
}
