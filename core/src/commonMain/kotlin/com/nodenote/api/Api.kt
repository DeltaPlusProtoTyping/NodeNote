package com.nodenote.api

import com.nodenote.model.BoardPatch
import com.nodenote.model.MessageKind
import com.nodenote.model.Project
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Wire format between the clients (desktop + browser) and the server.
 *
 *   GET    /api/info                         -> ServerInfo
 *   GET    /api/boards?archived=false        -> List<BoardSummary>
 *   POST   /api/boards        CreateBoardRequest -> BoardSummary
 *   GET    /api/boards/{id}                  -> BoardSnapshot
 *   PATCH  /api/boards/{id}/meta BoardMetaUpdate -> BoardSummary
 *   POST   /api/boards/{id}/patch PatchRequest -> PatchResponse
 *   GET    /api/boards/{id}/messages?since=S -> List<Message>   (seq > S, ascending seq)
 *   POST   /api/boards/{id}/messages Message -> Message         (server stamps createdAt + seq)
 *   PATCH  /api/messages/{id}  MessageUpdate -> Message
 *   GET    /api/boards/{id}/sync?rev=R&seq=S&wait=25 -> SyncResponse   (long-poll)
 *   POST   /api/vault/rebuild                -> 204
 *   (accounts: see the end of this file)
 */

@Serializable
data class ServerInfo(
    val name: String,
    val version: String,
    val vaultPath: String,
    /** The caller's Tailscale display name when the request came through `tailscale serve`, else null. */
    val identity: String? = null,
)

@Serializable
data class BoardSummary(
    val id: String,
    val name: String,
    val rev: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val nodeCount: Int,
    val messageCount: Int,
    /** Nodes whose status is Open / In progress / Blocked. */
    val openCount: Int,
    val archived: Boolean = false,
)

/** Creates an empty board named [name], or imports [project] (a NodeNote .json) as a new board. */
@Serializable
data class CreateBoardRequest(
    val name: String,
    val project: Project? = null,
    val author: String = "",
)

@Serializable
data class BoardSnapshot(val project: Project, val rev: Long)

@Serializable
data class BoardMetaUpdate(val archived: Boolean? = null)

/** [baseRev] is the server revision the patch was computed against. */
@Serializable
data class PatchRequest(val baseRev: Long, val author: String, val patch: BoardPatch)

/**
 * [project] is null when the new server board is exactly base + patch (the
 * client already has it); otherwise it carries the merged board to rebase onto.
 */
@Serializable
data class PatchResponse(val rev: Long, val project: Project? = null)

/**
 * Long-poll result. [project] is set only when the board changed past the
 * client's rev; [messages] are those with seq > the client's seq; [seq] is the
 * value to send next time.
 */
@Serializable
data class SyncResponse(
    val rev: Long,
    val project: Project? = null,
    val messages: List<com.nodenote.model.Message> = emptyList(),
    val seq: Long,
)

/** Partial message edit: null fields are left unchanged. */
@Serializable
data class MessageUpdate(
    val text: String? = null,
    val kind: MessageKind? = null,
    val importance: Int? = null,
    val nodeId: String? = null,
    val linkedNodeIds: List<String>? = null,
    val done: Boolean? = null,
    val deleted: Boolean? = null,
)

/** The one JSON configuration used on both ends of the wire. */
val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

// ---- Accounts ----
//
//   POST /api/auth/login             LoginRequest -> LoginResponse   (no token needed)
//   POST /api/auth/logout            -> 204
//   GET  /api/auth/me                -> AccountInfo
//   POST /api/auth/password          ChangePasswordRequest -> 204
//   POST /api/auth/username          ChangeUsernameRequest -> LoginResponse
//
// Every other /api call except /api/info needs `Authorization: Bearer <token>`.
// The signed-in username is the author of everything that user posts.

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class LoginResponse(val token: String, val username: String)

@Serializable
data class AccountInfo(val username: String)

@Serializable
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

/** Renames the account; the user's past messages and node owner/creator fields follow the new name. */
@Serializable
data class ChangeUsernameRequest(val password: String, val newUsername: String)
