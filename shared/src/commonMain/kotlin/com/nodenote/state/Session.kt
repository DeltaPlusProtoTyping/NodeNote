package com.nodenote.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nodenote.api.ServerInfo
import com.nodenote.net.ApiClient
import com.nodenote.spell.SpellChecker
import com.nodenote.storage.ClientPrefs
import com.nodenote.storage.defaultServerUrl
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

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

    /** The signed-in account name — the author of everything posted from here. Blank when signed out. */
    var userName by mutableStateOf(ClientPrefs.userName)
        private set

    var serverUrl by mutableStateOf(ClientPrefs.serverUrl.ifBlank { defaultServerUrl() })
        private set

    var api by mutableStateOf(ApiClient(serverUrl).also { it.token = ClientPrefs.sessionToken.ifBlank { null } })
        private set

    var signedIn by mutableStateOf(ClientPrefs.sessionToken.isNotBlank() && ClientPrefs.userName.isNotBlank())
        private set

    var connection by mutableStateOf(ConnectionState.Connecting)
        private set

    /** Human-readable reason for the last failure, shown on hover/in settings. */
    var lastError by mutableStateOf<String?>(null)
        private set

    /** Offline spell checker; null until its word list has loaded (see [loadSpellChecker]). */
    var spellChecker by mutableStateOf<SpellChecker?>(null)
        private set
    private var spellLoading = false

    /** Fetches the word list once, in the background. Without it there's simply no spell check. */
    fun loadSpellChecker() {
        if (spellChecker != null || spellLoading || !signedIn) return
        spellLoading = true
        scope.launch {
            try {
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

    /** Signs in; returns null on success or a message to show. */
    suspend fun signIn(username: String, password: String): String? = try {
        val result = api.login(username.trim(), password)
        applyAccount(result.username, result.token)
        signInNotice = null
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
