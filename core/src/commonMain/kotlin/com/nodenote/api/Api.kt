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
 *   GET    /api/boards/{id}/history          -> List<HistoryEntry>  (newest first)
 *   GET    /api/boards/{id}/history/{entry}  -> BoardSnapshot       (the board as it was then)
 *   GET    /api/activity                     -> Activity            (who's here, who changed what)
 *   GET    /api/replies?since=S              -> List<Reply>         (others' chats on my cards, seq > S)
 *   POST   /api/vault/rebuild                -> 204
 *   GET    /download/NodeNote.apk            (no sign-in; when ServerInfo.androidApp)
 *   (accounts: see the end of this file)
 */

@Serializable
data class ServerInfo(
    val name: String,
    val version: String,
    val vaultPath: String,
    /** The caller's Tailscale display name when the request came through `tailscale serve`, else null. */
    val identity: String? = null,
    /**
     * Fingerprint of the web app the server is serving. When it changes under an
     * open page, that page is out of date and reloads (an old page could otherwise
     * drop fields it doesn't know about when it edits a node).
     */
    val webBuild: String? = null,
    /** No accounts yet (a fresh install): the sign-in screen offers to create the first one instead. */
    val needsSetup: Boolean = false,
    /** A group password is set: anyone who knows it joins with just their name (see [JoinRequest]). */
    val groupSignIn: Boolean = false,
    /** The Android app can be downloaded from `/download/NodeNote.apk` (next to the web app). */
    val androidApp: Boolean = false,
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
data class PatchRequest(
    val baseRev: Long,
    val author: String,
    val patch: BoardPatch,
    /**
     * Set when the patch puts the board back the way it was at a [HistoryEntry] (its id): the
     * server then keeps a copy of the board as it is now first, so the restore can be undone.
     */
    val restoredFrom: Long = 0,
)

/** One saved copy of a board (the server keeps one from before the first change in every 30 minutes). */
@Serializable
data class HistoryEntry(val id: Long, val rev: Long, val savedAt: Long, val cards: Int, val links: Int)

/** Who is on the server and what they did lately. */
@Serializable
data class Activity(val people: List<PersonActivity>, val recent: List<ActivityItem>)

/** [lastSeen]: when one of their devices last talked to the server (to the hour); 0 = never. */
@Serializable
data class PersonActivity(val name: String, val joinedAt: Long, val lastSeen: Long, val lastChange: Long = 0)

/** [what] is a short description, e.g. "added 2 cards, moved 3" or "chatted on Parts Take Too Long". */
@Serializable
data class ActivityItem(val boardId: String, val boardName: String, val author: String, val at: Long, val what: String)

/** Someone else's chat on a card I made, own, or chatted on. */
@Serializable
data class Reply(
    val boardId: String,
    val boardName: String,
    val nodeId: String,
    val nodeTitle: String,
    val author: String,
    val text: String,
    val seq: Long,
    val createdAt: Long,
)

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
//   POST /api/auth/setup             LoginRequest -> LoginResponse   (no token; only while there are no accounts)
//   POST /api/auth/join              JoinRequest -> LoginResponse    (no token; only with a group password set)
//   POST /api/auth/members           MembersRequest -> List<String>  (no token; the group's member names)
//   POST /api/auth/logout            -> 204
//   GET  /api/auth/me                -> AccountInfo
//   POST /api/auth/password          ChangePasswordRequest -> 204
//   POST /api/auth/username          ChangeUsernameRequest -> LoginResponse
//   GET  /api/related/status                                   -> RelatedStatus
//   GET  /api/related/boards/{id}/nodes/{node}                 -> RelatedResults
//   POST /api/related/boards/{id}/nodes/{node}/mark  RelatedMark
//   GET  /api/related/docs/{doc}                               -> RelatedDoc
//
// Every other /api call except /api/info needs `Authorization: Bearer <token>`.
// The signed-in username is the author of everything that user posts.

/**
 * Whether this account can see related notes. All false = don't mention them. [needsOwnSignIn]:
 * it's the right account, but signed in with a shared password.
 */
@Serializable
data class RelatedStatus(val available: Boolean = false, val needsOwnSignIn: Boolean = false)

@Serializable
data class RelatedItem(
    val id: Long,
    val source: String,
    val title: String,
    val who: String = "",
    val date: String = "",
    val snippet: String = "",
    val pinned: Boolean = false,
)

@Serializable
data class RelatedResults(val items: List<RelatedItem> = emptyList())

@Serializable
data class RelatedDoc(
    val id: Long,
    val source: String,
    val title: String,
    val who: String = "",
    val date: String = "",
    val text: String = "",
    val trimmed: Boolean = false,
)

/** [state]: "pin", "dismiss", or anything else to clear. */
@Serializable
data class RelatedMark(val docId: Long, val state: String)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class LoginResponse(val token: String, val username: String)

/**
 * Group sign-in, for a server shared with a group of testers: the group's one password and the
 * name to work under. A new name becomes a member on the spot; a name that belongs to a real
 * account (one with its own password) is refused, so nobody can join as someone else.
 */
@Serializable
data class JoinRequest(val name: String, val password: String)

/** The group password; answered with the names already joined, so people can pick theirs. */
@Serializable
data class MembersRequest(val password: String)

@Serializable
data class AccountInfo(val username: String)

@Serializable
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

/** Renames the account; the user's past messages and node owner/creator fields follow the new name. */
@Serializable
data class ChangeUsernameRequest(val password: String, val newUsername: String)
