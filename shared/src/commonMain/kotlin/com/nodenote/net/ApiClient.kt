package com.nodenote.net

import com.nodenote.api.AccountInfo
import com.nodenote.api.ApiJson
import com.nodenote.api.ChangePasswordRequest
import com.nodenote.api.ChangeUsernameRequest
import com.nodenote.api.LoginRequest
import com.nodenote.api.LoginResponse
import com.nodenote.api.BoardMetaUpdate
import com.nodenote.api.BoardSnapshot
import com.nodenote.api.BoardSummary
import com.nodenote.api.CreateBoardRequest
import com.nodenote.api.MessageUpdate
import com.nodenote.api.PatchRequest
import com.nodenote.api.PatchResponse
import com.nodenote.api.ServerInfo
import com.nodenote.api.SyncResponse
import com.nodenote.model.Message
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json

/**
 * Typed wrapper over the server's REST API (see com.nodenote.api.Api for the
 * endpoint list). One instance per server address; the HTTP engine comes from
 * the platform (OkHttp on desktop, fetch in the browser).
 */
class ApiClient(serverUrl: String) {

    val baseUrl: String = serverUrl.trim().trimEnd('/')

    /** Session token from [login]; sent as a bearer token on every call. */
    var token: String? = null

    private val http = HttpClient {
        expectSuccess = true
        install(ContentNegotiation) { json(ApiJson) }
        // Only the request timeout: the browser engine rejects connect/socket timeouts.
        install(HttpTimeout) { requestTimeoutMillis = 20_000 }
        // Evaluated per request, so it always carries the current token.
        defaultRequest { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }
    }

    suspend fun info(): ServerInfo = http.get("$baseUrl/api/info").body()

    /** The spell checker's word list (one lowercase word per line). The version busts the week-long cache when it changes. */
    suspend fun dictionary(): String = http.get("$baseUrl/api/dictionary") { parameter("v", DICTIONARY_VERSION) }.bodyAsText()

    // ---- account ----

    suspend fun login(username: String, password: String): LoginResponse =
        http.post("$baseUrl/api/auth/login") { json(LoginRequest(username, password)) }.body()

    /** A fresh server's first account (refused once any account exists). */
    suspend fun setup(username: String, password: String): LoginResponse =
        http.post("$baseUrl/api/auth/setup") { json(LoginRequest(username, password)) }.body()

    suspend fun logout() {
        http.post("$baseUrl/api/auth/logout")
    }

    suspend fun me(): AccountInfo = http.get("$baseUrl/api/auth/me").body()

    suspend fun changePassword(current: String, new: String) {
        http.post("$baseUrl/api/auth/password") { json(ChangePasswordRequest(current, new)) }
    }

    suspend fun changeUsername(password: String, newUsername: String): LoginResponse =
        http.post("$baseUrl/api/auth/username") { json(ChangeUsernameRequest(password, newUsername)) }.body()

    suspend fun boards(includeArchived: Boolean): List<BoardSummary> =
        http.get("$baseUrl/api/boards") { parameter("archived", includeArchived) }.body()

    suspend fun createBoard(request: CreateBoardRequest): BoardSummary =
        http.post("$baseUrl/api/boards") { json(request) }.body()

    suspend fun board(id: String): BoardSnapshot = http.get("$baseUrl/api/boards/$id").body()

    suspend fun setArchived(id: String, archived: Boolean): BoardSummary =
        http.patch("$baseUrl/api/boards/$id/meta") { json(BoardMetaUpdate(archived = archived)) }.body()

    suspend fun patch(id: String, request: PatchRequest): PatchResponse =
        http.post("$baseUrl/api/boards/$id/patch") { json(request) }.body()

    suspend fun messages(boardId: String, since: Long): List<Message> =
        http.get("$baseUrl/api/boards/$boardId/messages") { parameter("since", since) }.body()

    suspend fun postMessage(message: Message): Message =
        http.post("$baseUrl/api/boards/${message.boardId}/messages") { json(message) }.body()

    suspend fun updateMessage(id: String, update: MessageUpdate): Message =
        http.patch("$baseUrl/api/messages/$id") { json(update) }.body()

    /** Long-poll: returns as soon as the board or its chat changes, or after [waitSeconds]. */
    suspend fun sync(boardId: String, rev: Long, seq: Long, waitSeconds: Int = 25): SyncResponse =
        http.get("$baseUrl/api/boards/$boardId/sync") {
            parameter("rev", rev)
            parameter("seq", seq)
            parameter("wait", waitSeconds)
            timeout { requestTimeoutMillis = (waitSeconds + 15) * 1000L }
        }.body()

    fun close() = http.close()

    private inline fun <reified T> io.ktor.client.request.HttpRequestBuilder.json(body: T) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }
}

/** Bump when server/src/main/resources/dict changes. */
const val DICTIONARY_VERSION = 1
