package com.nodenote.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nodenote.api.ServerInfo
import com.nodenote.local.DeviceBoards
import com.nodenote.net.ApiClient
import com.nodenote.net.BoardApi
import com.nodenote.net.DeviceApi
import com.nodenote.spell.SpellChecker
import com.nodenote.storage.ClientPrefs
import com.nodenote.storage.defaultServerUrl
import com.nodenote.storage.deviceStore
import com.nodenote.storage.isWebPlatform
import com.nodenote.storage.startServerOnThisComputer
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where this app keeps its boards: on this device (no server, no account) or on a NodeNote server. */
enum class StorageMode(val pref: String) { Device("device"), Server("server") }

enum class ConnectionState(val label: String) {
    Connecting("Connecting…"),
    Online("Online"),
    Offline("Offline"),
}

/**
 * App-wide connection + identity: which server, whether it answers, and who is
 * signed in. One per app; the [Workspace] exists only while signed in, and every
 * open board tab syncs through this session.
 *
 * The session token is remembered on this device (desktop ~/.nodenote, browser
 * localStorage) so people stay signed in; the server can revoke it at any time,
 * which signs this client out on the next request.
 *
 * [scope] runs on the UI thread (Dispatchers.Main on desktop, the JS event loop
 * in the browser), so sync code can touch Compose state directly.
 */
class Session {

    val scope: CoroutineScope = MainScope()

    /**
     * Where the boards live; chosen in Settings and remembered. The first time: an app
     * already signed in to a server stays on it, the browser version starts at its server's
     * sign-in (with "use on this device" one tap away), and anything else starts on this device.
     */
    var mode by mutableStateOf(initialMode())
        private set

    /** Who writes the messages and cards: the signed-in account, or the name set for this device. Blank when signed out. */
    var userName by mutableStateOf(if (mode == StorageMode.Device) deviceName() else ClientPrefs.userName)
        private set

    var serverUrl by mutableStateOf(ClientPrefs.serverUrl.ifBlank { defaultServerUrl() })
        private set

    private val device: DeviceApi by lazy { DeviceApi(DeviceBoards(deviceStore())) { userName } }

    var api: BoardApi by mutableStateOf(if (mode == StorageMode.Device) device else serverClient())
        private set

    var signedIn by mutableStateOf(
        mode == StorageMode.Device || (ClientPrefs.sessionToken.isNotBlank() && ClientPrefs.userName.isNotBlank()),
    )
        private set

    val onThisDevice: Boolean get() = mode == StorageMode.Device

    private fun serverClient() = ApiClient(serverUrl).also { it.token = ClientPrefs.sessionToken.ifBlank { null } }

    private fun deviceName() = ClientPrefs.deviceUserName.ifBlank { "Me" }

    private fun initialMode(): StorageMode {
        StorageMode.entries.firstOrNull { it.pref == ClientPrefs.storageMode }?.let { return it }
        val mode = if (ClientPrefs.sessionToken.isNotBlank() || isWebPlatform) StorageMode.Server else StorageMode.Device
        ClientPrefs.storageMode = mode.pref
        return mode
    }

    /** From now on, boards are kept on this device: no server, no sign-in. The server's boards stay where they are. */
    fun useThisDevice() {
        if (mode == StorageMode.Device) return
        ClientPrefs.storageMode = StorageMode.Device.pref
        mode = StorageMode.Device
        userName = deviceName()
        api = device
        serverInfo = null
        firstWebBuild = null
        updateAvailable = false
        signedIn = true
        markOnline()
    }

    /** From now on, boards are on the server at [url] (sign in there next). This device's boards stay on it. */
    fun useServer(url: String) {
        ClientPrefs.storageMode = StorageMode.Server.pref
        mode = StorageMode.Server
        userName = ClientPrefs.userName
        val sameServer = url.trim().trimEnd('/').ifBlank { defaultServerUrl() } == serverUrl
        if (sameServer && ClientPrefs.sessionToken.isNotBlank() && userName.isNotBlank()) {
            // Back to the server this device was signed in to: that sign-in still holds.
            startServerOnThisComputer(serverUrl)
            api = serverClient()
            connection = ConnectionState.Connecting
            signedIn = true
        } else {
            updateServerUrl(url)
        }
    }

    /** Renames the one user of this device; their cards and messages follow. Null on success, else what went wrong. */
    suspend fun renameOnThisDevice(newName: String): String? {
        val name = newName.trim()
        if (name.isEmpty()) return "Type a name"
        return try {
            api.changeUsername("", name)
            ClientPrefs.deviceUserName = name
            userName = name
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: "Couldn't rename"
        }
    }

    var connection by mutableStateOf(ConnectionState.Connecting)
        private set

    /** Human-readable reason for the last failure, shown on hover/in settings. */
    var lastError by mutableStateOf<String?>(null)
        private set

    /** Offline spell checker; null until its word list has loaded (see [loadSpellChecker]). */
    var spellChecker by mutableStateOf<SpellChecker?>(null)
        private set
    private var spellLoading = false

    /**
     * Fetches the word list once, in the background. Without it there's simply no spell check.
     * Waits a few seconds first, so it doesn't compete with the boards opening (a phone's
     * first visit is slow enough already).
     */
    fun loadSpellChecker() {
        if (spellChecker != null || spellLoading || !signedIn) return
        spellLoading = true
        scope.launch {
            try {
                delay(3_000)
                spellChecker = SpellChecker(api.dictionary())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Old server or offline: try again next time a board opens.
            } finally {
                spellLoading = false
            }
        }
    }

    var serverInfo by mutableStateOf<ServerInfo?>(null)
        private set

    /** The server is running a newer app than this page/window — see [noteServerInfo]. */
    var updateAvailable by mutableStateOf(false)
        private set
    private var firstWebBuild: String? = null

    /** Records /api/info; a changed web build under an open page means this page is stale. */
    fun noteServerInfo(info: ServerInfo) {
        serverInfo = info
        val build = info.webBuild ?: return
        if (firstWebBuild == null) firstWebBuild = build
        else if (build != firstWebBuild) updateAvailable = true
    }

    /** Why the user was sent back to the sign-in screen (e.g. an expired session). */
    var signInNotice by mutableStateOf<String?>(null)

    // ---- sign-in ----

    /**
     * Asks the server about itself for the sign-in screen (is it a fresh install with no
     * accounts yet?). Retries for a while: the desktop app's own server may still be starting.
     */
    suspend fun checkServer() {
        repeat(20) {
            try {
                noteServerInfo(api.info())
                markOnline()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                markOffline(e)
                delay(1_500)
            }
        }
    }

    /** Signs in; returns null on success or a message to show. */
    suspend fun signIn(username: String, password: String): String? = account { api.login(username.trim(), password) }

    /** Creates a fresh server's first account and signs in with it; null on success, else a message to show. */
    suspend fun setUp(username: String, password: String): String? = account { api.setup(username.trim(), password) }

    /** Joins with the group's password under [name] (servers set up for a group); null on success, else a message to show. */
    suspend fun join(name: String, password: String): String? = account { api.join(name.trim(), password) }

    private suspend fun account(call: suspend () -> com.nodenote.api.LoginResponse): String? = try {
        val result = call()
        applyAccount(result.username, result.token)
        signInNotice = null
        serverInfo = serverInfo?.copy(needsSetup = false)
        markOnline()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: ResponseException) {
        errorMessage(e)
    } catch (e: Exception) {
        markOffline(e)
        "Can't reach the server at $serverUrl"
    }

    fun signOut(notice: String? = null) {
        val client = api
        if (client.token != null) scope.launch { runCatching { client.logout() } }
        client.token = null
        ClientPrefs.sessionToken = ""
        signedIn = false
        signInNotice = notice
    }

    /** Returns null on success or an error message. */
    suspend fun changePassword(current: String, new: String): String? = accountCall { api.changePassword(current, new) }

    /** Returns null on success or an error message. The new name applies to past messages too (server-side). */
    suspend fun changeUsername(password: String, newName: String): String? = accountCall {
        val result = api.changeUsername(password, newName)
        applyAccount(result.username, result.token)
    }

    private suspend fun accountCall(block: suspend () -> Unit): String? = try {
        block()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: ResponseException) {
        errorMessage(e)
    } catch (e: Exception) {
        markOffline(e)
        "Can't reach the server"
    }

    private fun applyAccount(name: String, token: String) {
        userName = name
        api.token = token
        ClientPrefs.userName = name
        ClientPrefs.sessionToken = token
        signedIn = true
    }

    private suspend fun errorMessage(e: ResponseException): String {
        // The server answers errors as {"error": "..."}; show that text.
        val body = runCatching { e.response.bodyAsText() }.getOrNull().orEmpty()
        return Regex(""""error"\s*:\s*"([^"]*)"""").find(body)?.groupValues?.get(1)
            ?: "Request failed (${e.response.status.value})"
    }

    // ---- server ----

    /** Points the app at another server (signs out: the session belongs to the old one). */
    fun updateServerUrl(url: String) {
        val cleaned = url.trim().trimEnd('/')
        ClientPrefs.serverUrl = if (cleaned == defaultServerUrl()) "" else cleaned
        serverUrl = cleaned.ifBlank { defaultServerUrl() }
        api.close()
        startServerOnThisComputer(serverUrl) // desktop: the built-in one, when that's this computer
        api = ApiClient(serverUrl)
        ClientPrefs.sessionToken = ""
        signedIn = false
        serverInfo = null
        firstWebBuild = null
        connection = ConnectionState.Connecting
    }

    fun markOnline() {
        connection = ConnectionState.Online
        lastError = null
    }

    /** Records a failed call; a 401 means the session is gone, so go back to sign-in. */
    fun markOffline(error: Throwable) {
        if (error is ResponseException && error.response.status == HttpStatusCode.Unauthorized) {
            if (signedIn) signOut("Your session ended — please sign in again.")
            return
        }
        connection = ConnectionState.Offline
        lastError = error.message ?: error::class.simpleName
    }
}
