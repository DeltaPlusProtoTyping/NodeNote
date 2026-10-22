package com.nodenote.net

import com.nodenote.api.AccountInfo
import com.nodenote.api.BoardSnapshot
import com.nodenote.api.BoardSummary
import com.nodenote.api.CreateBoardRequest
import com.nodenote.api.LoginResponse
import com.nodenote.api.MessageUpdate
import com.nodenote.api.PatchRequest
import com.nodenote.api.PatchResponse
import com.nodenote.api.ServerInfo
import com.nodenote.api.SyncResponse
import com.nodenote.local.DeviceBoards
import com.nodenote.model.Message
import com.nodenote.storage.bundledDictionary

/**
 * Where the boards live, as the rest of the app sees it: a NodeNote server ([ApiClient]) or
 * this device ([DeviceApi]). Same calls and the same rules either way, so the map, chats,
 * sync and everything else work unchanged in both.
 */
interface BoardApi {
    /** What the status line names: the server's address, or "this device". */
    val baseUrl: String
    var token: String?

    suspend fun info(): ServerInfo
    suspend fun dictionary(): String
    suspend fun login(username: String, password: String): LoginResponse
    suspend fun setup(username: String, password: String): LoginResponse
    suspend fun logout()
    suspend fun me(): AccountInfo
    suspend fun changePassword(current: String, new: String)
    suspend fun changeUsername(password: String, newUsername: String): LoginResponse
    suspend fun boards(includeArchived: Boolean): List<BoardSummary>
    suspend fun createBoard(request: CreateBoardRequest): BoardSummary
    suspend fun board(id: String): BoardSnapshot
    suspend fun setArchived(id: String, archived: Boolean): BoardSummary
    suspend fun patch(id: String, request: PatchRequest): PatchResponse
    suspend fun messages(boardId: String, since: Long): List<Message>
    suspend fun postMessage(message: Message): Message
    suspend fun updateMessage(id: String, update: MessageUpdate): Message
    suspend fun sync(boardId: String, rev: Long, seq: Long, waitSeconds: Int = 25): SyncResponse
    fun close()
}

/** A board that isn't there (any more): the tab quietly turns back into a Home tab. */
class BoardMissingException(id: String) : RuntimeException("No board $id")

/**
 * Boards kept on this device, no server and no account: one person, everything saved
 * locally as it changes (see [DeviceBoards]). [userName] is who writes the messages.
 */
class DeviceApi(private val boards: DeviceBoards, private val userName: () -> String) : BoardApi {
    override val baseUrl: String = "this device"
    override var token: String? = null

    override suspend fun info() = ServerInfo(name = "NodeNote", version = "", vaultPath = "")
    override suspend fun dictionary(): String = bundledDictionary() ?: throw IllegalStateException("No word list on this device")
    override suspend fun login(username: String, password: String): LoginResponse = noAccounts()
    override suspend fun setup(username: String, password: String): LoginResponse = noAccounts()
    override suspend fun logout() = Unit
    override suspend fun me() = AccountInfo(userName())
    override suspend fun changePassword(current: String, new: String): Unit = noAccounts()

    /** Just a new name here: the cards and messages written under the old one follow. */
    override suspend fun changeUsername(password: String, newUsername: String): LoginResponse {
        boards.renameAuthor(userName(), newUsername.trim())
        return LoginResponse(token = "", username = newUsername.trim())
    }

    override suspend fun boards(includeArchived: Boolean) = boards.list(includeArchived)
    override suspend fun createBoard(request: CreateBoardRequest) = boards.create(request)
    override suspend fun board(id: String) = boards.snapshot(id) ?: throw BoardMissingException(id)
    override suspend fun setArchived(id: String, archived: Boolean) = boards.setArchived(id, archived) ?: throw BoardMissingException(id)
    override suspend fun patch(id: String, request: PatchRequest) = boards.patch(id, request) ?: throw BoardMissingException(id)
    override suspend fun messages(boardId: String, since: Long) = boards.messagesSince(boardId, since) ?: throw BoardMissingException(boardId)
    override suspend fun postMessage(message: Message) =
        boards.postMessage(message.boardId, message, userName()) ?: throw BoardMissingException(message.boardId)
    override suspend fun updateMessage(id: String, update: MessageUpdate) =
        boards.updateMessage(id, update) ?: throw IllegalArgumentException("That message isn't on this device")
    override suspend fun sync(boardId: String, rev: Long, seq: Long, waitSeconds: Int) =
        boards.sync(boardId, rev, seq, waitSeconds * 1000L) ?: throw BoardMissingException(boardId)
    override fun close() = Unit

    private fun noAccounts(): Nothing = throw IllegalStateException("Boards on this device don't use accounts")
}
