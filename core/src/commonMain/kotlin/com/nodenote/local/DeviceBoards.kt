package com.nodenote.local

import com.nodenote.api.ApiJson
import com.nodenote.api.BoardSnapshot
import com.nodenote.api.BoardSummary
import com.nodenote.api.CreateBoardRequest
import com.nodenote.api.MessageUpdate
import com.nodenote.api.PatchRequest
import com.nodenote.api.PatchResponse
import com.nodenote.api.SyncResponse
import com.nodenote.model.Message
import com.nodenote.model.Project
import com.nodenote.model.applyPatch
import com.nodenote.model.clampImportance
import com.nodenote.model.newId
import com.nodenote.model.nowMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Where "on this device" keeps its files: a key → text store. Desktop: files under
 * ~/.nodenote/device; browser: the page's localStorage; iOS: the app's documents.
 */
interface DeviceStore {
    fun read(key: String): String?

    /** False when it couldn't be saved (the browser's storage is full, a disk error…). */
    fun write(key: String, value: String): Boolean
}

/** The device couldn't save a change; nothing was changed, so the edit can simply be retried. */
class DeviceSaveException(message: String) : RuntimeException(message)

/**
 * Boards and their chats kept on this device, with no server: the same operations and rules
 * as the server's board store (server/BoardStore.kt), saved to a [DeviceStore]. There is
 * one user, so no accounts, and every change is saved before it counts — if saving fails
 * the change is refused with [DeviceSaveException] and the app keeps it as unsent.
 *
 * Each board file is written after its previous version is kept as `.prev`, so a save cut
 * short can never lose the board: loading falls back to the previous version. Nothing is
 * ever deleted (boards are archived, messages get tombstones), same as on a server.
 */
class DeviceBoards(private val store: DeviceStore, private val clock: () -> Long = ::nowMillis) {

    private val mutex = Mutex()
    private var loaded: LinkedHashMap<String, Board>? = null
    private var lastSeq = 0L

    private class Board(var meta: BoardMeta, var project: Project, val messages: LinkedHashMap<String, Message>) {
        var maxSeq: Long = messages.values.maxOfOrNull { it.seq } ?: 0L
        /** Ticks on every change; [sync] waits on it like the server's long-poll. */
        val version = MutableStateFlow(0L)

        fun summary() = BoardSummary(
            id = meta.id,
            name = project.name,
            rev = meta.rev,
            createdAt = meta.createdAt,
            updatedAt = meta.updatedAt,
            nodeCount = project.nodes.size,
            messageCount = messages.values.count { !it.deleted },
            openCount = project.nodes.count { it.status.isActive },
            archived = meta.archived,
        )
    }

    // ---- boards ----

    suspend fun list(includeArchived: Boolean): List<BoardSummary> = mutex.withLock {
        boards().values.filter { includeArchived || !it.meta.archived }.map { it.summary() }.sortedByDescending { it.updatedAt }
    }

    suspend fun create(request: CreateBoardRequest): BoardSummary = mutex.withLock {
        val boards = boards()
        var id = newId()
        while (id in boards) id = newId()
        val imported = request.project
        val name = request.name.trim().ifBlank { imported?.name?.trim().orEmpty() }.ifBlank { "Untitled Board" }
        val project = (imported ?: Project(id = id, name = name)).copy(id = id, name = name)
        val now = clock()
        val board = Board(BoardMeta(id, rev = 1, createdAt = now, updatedAt = now), project, LinkedHashMap())
        saveProject(id, project)
        saveMessages(board)
        boards[id] = board
        saveIndex()
        board.summary()
    }

    suspend fun snapshot(id: String): BoardSnapshot? = mutex.withLock {
        boards()[id]?.let { BoardSnapshot(it.project, it.meta.rev) }
    }

    suspend fun setArchived(id: String, archived: Boolean): BoardSummary? = mutex.withLock {
        val board = boards()[id] ?: return@withLock null
        if (board.meta.archived != archived) {
            val before = board.meta
            board.meta = before.copy(archived = archived)
            if (!tryIndex()) {
                board.meta = before
                throw DeviceSaveException(SAVE_FAILED)
            }
        }
        board.summary()
    }

    /** Same contract as the server: the merged board comes back unless the caller was up to date. */
    suspend fun patch(id: String, request: PatchRequest): PatchResponse? = mutex.withLock {
        val board = boards()[id] ?: return@withLock null
        val current = board.project
        val next = current.applyPatch(request.patch).copy(id = id)
        val upToDate = request.baseRev == board.meta.rev
        if (next == current) return@withLock PatchResponse(board.meta.rev, project = current.takeUnless { upToDate })
        saveProject(id, next, previous = current)
        board.project = next
        board.meta = board.meta.copy(rev = board.meta.rev + 1, updatedAt = clock())
        saveIndex()
        board.version.value++
        PatchResponse(board.meta.rev, project = next.takeUnless { upToDate })
    }

    // ---- messages ----

    suspend fun messagesSince(id: String, since: Long): List<Message>? = mutex.withLock {
        boards()[id]?.messages?.values?.filter { it.seq > since }?.sortedBy { it.seq }
    }

    suspend fun postMessage(boardId: String, incoming: Message, author: String): Message? = mutex.withLock {
        val board = boards()[boardId] ?: return@withLock null
        board.messages[incoming.id]?.let { return@withLock it } // a retried post
        require(incoming.text.isNotBlank()) { "Message text is empty" }
        val now = clock()
        val message = incoming.copy(
            id = incoming.id.ifBlank { newId() },
            boardId = boardId,
            author = author,
            importance = clampImportance(incoming.importance),
            linkedNodeIds = incoming.linkedNodeIds.distinct(),
            createdAt = now,
            editedAt = 0,
            deleted = false,
            seq = lastSeq + 1,
        )
        store(board, message, now)
        message
    }

    suspend fun updateMessage(id: String, update: MessageUpdate): Message? = mutex.withLock {
        val board = boards().values.firstOrNull { id in it.messages } ?: return@withLock null
        val old = board.messages.getValue(id)
        update.text?.let { require(it.isNotBlank()) { "Message text is empty" } }
        val edited = old.copy(
            text = update.text ?: old.text,
            kind = update.kind ?: old.kind,
            importance = update.importance?.let(::clampImportance) ?: old.importance,
            nodeId = update.nodeId ?: old.nodeId,
            linkedNodeIds = update.linkedNodeIds?.distinct() ?: old.linkedNodeIds,
            done = update.done ?: old.done,
            deleted = update.deleted ?: old.deleted,
        )
        if (edited == old) return@withLock old
        val now = clock()
        val message = edited.copy(editedAt = if (edited.text != old.text) now else old.editedAt, seq = lastSeq + 1)
        store(board, message, now)
        message
    }

    /** A new name for the one user: their messages and cards follow, as on a server. */
    suspend fun renameAuthor(old: String, new: String) = mutex.withLock {
        if (old == new) return@withLock
        for (board in boards().values) {
            val nodes = board.project.nodes.map { n ->
                if (n.owner != old && n.createdBy != old) n
                else n.copy(owner = if (n.owner == old) new else n.owner, createdBy = if (n.createdBy == old) new else n.createdBy)
            }
            if (nodes != board.project.nodes) {
                val next = board.project.copy(nodes = nodes)
                saveProject(board.meta.id, next, previous = board.project)
                board.project = next
                board.meta = board.meta.copy(rev = board.meta.rev + 1)
            }
            val renamed = board.messages.values.filter { it.author == old }
            if (renamed.isNotEmpty()) {
                val before = LinkedHashMap(board.messages)
                renamed.forEach { m -> board.messages[m.id] = m.copy(author = new, seq = ++lastSeq) }
                if (!trySaveMessages(board)) {
                    board.messages.clear()
                    board.messages.putAll(before)
                    throw DeviceSaveException(SAVE_FAILED)
                }
                board.maxSeq = board.messages.values.maxOf { it.seq }
            }
            board.version.value++
        }
        saveIndex()
    }

    // ---- sync ----

    /** Answers as soon as board [id] is past [rev] or [seq], waiting up to [waitMillis]. */
    suspend fun sync(id: String, rev: Long, seq: Long, waitMillis: Long): SyncResponse? {
        val version = mutex.withLock { boards()[id]?.version } ?: return null
        val seen = version.value
        val ready = mutex.withLock { boards().getValue(id).let { it.meta.rev > rev || it.maxSeq > seq } }
        if (!ready && waitMillis > 0) withTimeoutOrNull(waitMillis) { version.first { it != seen } }
        return mutex.withLock {
            val board = boards().getValue(id)
            val messages = board.messages.values.filter { it.seq > seq }.sortedBy { it.seq }
            SyncResponse(
                rev = board.meta.rev,
                project = board.project.takeIf { board.meta.rev > rev },
                messages = messages,
                seq = maxOf(seq, messages.maxOfOrNull { it.seq } ?: seq),
            )
        }
    }

    // ---- storage ----

    private fun store(board: Board, message: Message, now: Long) {
        val before = board.messages[message.id]
        board.messages[message.id] = message
        if (!trySaveMessages(board)) {
            if (before == null) board.messages.remove(message.id) else board.messages[message.id] = before
            throw DeviceSaveException(SAVE_FAILED)
        }
        lastSeq = message.seq
        board.maxSeq = maxOf(board.maxSeq, message.seq)
        board.meta = board.meta.copy(updatedAt = now)
        saveIndex()
        board.version.value++
    }

    private fun boards(): LinkedHashMap<String, Board> {
        loaded?.let { return it }
        val index = store.read(INDEX)?.let { runCatching { ApiJson.decodeFromString(Index.serializer(), it) }.getOrNull() } ?: Index()
        val boards = LinkedHashMap<String, Board>()
        for (meta in index.boards) {
            val project = readProject(meta.id) ?: continue // unreadable: left on disk untouched, just not shown
            val messages = store.read(messagesKey(meta.id))
                ?.let { runCatching { ApiJson.decodeFromString(MessageList, it) }.getOrNull() }
                .orEmpty()
            boards[meta.id] = Board(meta, project.copy(id = meta.id), LinkedHashMap(messages.associateBy { it.id }))
        }
        lastSeq = maxOf(index.lastSeq, boards.values.maxOfOrNull { it.maxSeq } ?: 0L)
        loaded = boards
        return boards
    }

    /** The board's file, else the version kept before the last save (a save cut short). */
    private fun readProject(id: String): Project? =
        listOf(projectKey(id), previousKey(id)).firstNotNullOfOrNull { key ->
            store.read(key)?.let { runCatching { ApiJson.decodeFromString(Project.serializer(), it) }.getOrNull() }
        }

    private fun saveProject(id: String, project: Project, previous: Project? = null) {
        if (previous != null && !store.write(previousKey(id), ApiJson.encodeToString(Project.serializer(), previous))) {
            throw DeviceSaveException(SAVE_FAILED)
        }
        if (!store.write(projectKey(id), ApiJson.encodeToString(Project.serializer(), project))) throw DeviceSaveException(SAVE_FAILED)
    }

    private fun saveMessages(board: Board) {
        if (!trySaveMessages(board)) throw DeviceSaveException(SAVE_FAILED)
    }

    private fun trySaveMessages(board: Board): Boolean =
        store.write(messagesKey(board.meta.id), ApiJson.encodeToString(MessageList, board.messages.values.toList()))

    private fun saveIndex() {
        if (!tryIndex()) throw DeviceSaveException(SAVE_FAILED)
    }

    private fun tryIndex(): Boolean {
        val boards = loaded ?: return true
        return store.write(INDEX, ApiJson.encodeToString(Index.serializer(), Index(boards.values.map { it.meta }, lastSeq)))
    }

    @Serializable
    private data class Index(val boards: List<BoardMeta> = emptyList(), val lastSeq: Long = 0)

    @Serializable
    private data class BoardMeta(
        val id: String,
        val rev: Long,
        val createdAt: Long,
        val updatedAt: Long,
        val archived: Boolean = false,
    )

    private companion object {
        const val INDEX = "boards.json"
        const val SAVE_FAILED = "Couldn't save on this device (is its storage full?). Your change is kept and will be saved again."
        val MessageList = ListSerializer(Message.serializer())
        fun projectKey(id: String) = "board-$id.json"
        fun previousKey(id: String) = "board-$id.prev.json"
        fun messagesKey(id: String) = "messages-$id.json"
    }
}
