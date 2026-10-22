package com.nodenote.storage

import com.nodenote.local.DeviceStore

/**
 * Platform seam for the few things that differ between the desktop app and
 * the browser app: small preference files, and letting the user pick or save a
 * file.
 *
 * Picking and saving take callbacks because the browser can only do them
 * asynchronously (file input / download); desktop simply calls back before
 * returning.
 */
/** A file the user picked, fully read into memory. */
class PickedFile(val name: String, val bytes: ByteArray)

expect object ProjectStorage {

    /** Reads an app-level preferences file (desktop: ~/.nodenote/, web: localStorage), or null if missing. */
    fun readPrefs(fileName: String): String?

    /** Writes an app-level preferences file. */
    fun writePrefs(fileName: String, content: String): Boolean

    /**
     * Lets the user pick one file and reads it. [accept] is an HTML-style filter
     * (".json", an image MIME wildcard, or "" for anything). [onResult] gets null on cancel or
     * failure (the browser may never call back on cancel).
     */
    fun pickFile(accept: String, onResult: (PickedFile?) -> Unit)

    /**
     * Saves [bytes] under a user-visible name: a save dialog on desktop, a
     * download in the browser. [onResult] gets where it went, or null if cancelled.
     */
    fun saveFile(suggestedFileName: String, bytes: ByteArray, onResult: (String?) -> Unit)
}

/** The server this client talks to unless the user configured another (desktop: localhost; web: the page's own origin). */
expect fun defaultServerUrl(): String

/** True in the browser build — hides desktop-only settings such as the server address. */
expect val isWebPlatform: Boolean

/**
 * Reads plain text from the system clipboard; [onResult] gets null when empty or
 * not allowed. In the browser this asks permission and is async — the reason for
 * the callback (used by the Paste button on password fields, since phones can't
 * paste into the canvas-drawn text fields).
 */
expect fun readClipboardText(onResult: (String?) -> Unit)

/** Browser: sets the tab title (e.g. "(3) NodeNote" when there are unread messages). No-op elsewhere. */
expect fun setPageTitle(title: String)

/** Browser: reloads the page (to pick up a new version). Returns false where that isn't possible (desktop). */
expect fun reloadPage(): Boolean

/**
 * Browser: reload the page the next time it is hidden (tab switched away, phone
 * locked) — if [canReload] still says it's safe — so an update lands without
 * interrupting anyone. No-op elsewhere.
 */
expect fun reloadWhenHidden(canReload: () -> Boolean)

/** Where boards kept "on this device" are saved (see com.nodenote.local.DeviceBoards). */
expect fun deviceStore(): DeviceStore

/** The spell checker's word list shipped with the app, for boards on this device; null if this build has none. */
expect suspend fun bundledDictionary(): String?

/** Desktop: starts the built-in server when [serverUrl] is this computer and nothing runs there yet. Elsewhere: nothing. */
expect fun startServerOnThisComputer(serverUrl: String)
