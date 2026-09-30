package com.nodenote.server

import com.nodenote.api.ApiJson
import com.nodenote.api.BoardMetaUpdate
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList

/**
 * All boards and messages, in memory, written through to [Database].
 *
 * Two people use this, so simplicity beats cleverness: everything is loaded at
 * startup, every mutation runs under one [Mutex], and reads are plain map lookups.
 * That also makes the ordering guarantees trivial — a message's `seq` and a
 * board's `rev` are assigned in the same critical section that persists them.
 *
 * Change notification has two audiences:
 *  - long-polling clients wait on a per-board [MutableStateFlow] "version" that
 *    ticks on every board or message change ([sync]);
 *  - the vault mirror registers a listener ([addListener]) that is told which
 *    board changed, including metadata-only changes such as archiving.
 */
class BoardStore(
    private val db: Database,
    private val clock: () -> Long = ::nowMillis,
) {
    private val mutex = Mutex()
    private val boards = LinkedHashMap<String, BoardState>()
    /** message id → board id, for idempotent posts and `PATCH /api/messages/{id}`. */
    private val messageBoard = HashMap<String, String>()
    /** The last `seq` handed out — one counter for the whole server, see [Message.seq]. */
    private var lastSeq = 0L
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    private class BoardState(
        val id: String,
        var project: Project,
        var rev: Long,
        val createdAt: Long,
        var updatedAt: Long,
        var archived: Boolean,
        var vaultFolder: String?,
        var lastHistoryAt: Long,
    ) {
        val messages = LinkedHashMap<String, Message>()
        var maxSeq = 0L
        /** Ticks on every change a client could sync; long-polls suspend until it moves. */
        val version = MutableStateFlow(0L)

        fun summary() = BoardSummary(
            id = id,
            name = project.name,
            rev = rev,
            createdAt = createdAt,
            updatedAt = updatedAt,
            nodeCount = project.nodes.size,
            messageCount = messages.values.count { !it.deleted },
            openCount = project.nodes.count { it.status.isActive },
            archived = archived,
        )
    }

    init {
        val history = db.latestHistoryTimes()
        for (row in db.loadBoards()) {
            val project = try {
                ApiJson.decodeFromString(Project.serializer(), row.json)
            } catch (e: Exception) {
                log.error("Skipping board ${row.id} (${row.name}): stored JSON does not parse", e)
                continue
            }
            boards[row.id] = BoardState(
                id = row.id,
                project = project.copy(id = row.id),
                rev = row.rev,
                createdAt = row.createdAt,
                updatedAt = row.updatedAt,
                archived = row.archived,
                vaultFolder = row.vaultFolder,
                lastHistoryAt = history[row.id] ?: 0L,
            )
        }
        for (m in db.loadMessages()) {
            val state = boards[m.boardId] ?: continue
            state.messages[m.id] = m
            state.maxSeq = maxOf(state.maxSeq, m.seq)
            messageBoard[m.id] = m.boardId
        }
        lastSeq = db.maxSeq()
        log.info("Loaded ${boards.size} boards, ${messageBoard.size} messages (seq $lastSeq)")
    }

    /** [listener] is called with a board id after every change to that board, outside the store's lock. */
    fun addListener(listener: (boardId: String) -> Unit) {
        listeners += listener
    }

    // ---- boards ----

    suspend fun list(includeArchived: Boolean): List<BoardSummary> = mutex.withLock {
        boards.values
            .filter { includeArchived || !it.archived }
            .map { it.summary() }
            .sortedByDescending { it.updatedAt }
    }

    suspend fun create(request: CreateBoardRequest): BoardSummary {
        val state = mutex.withLock {
            var id = newId()
            while (id in boards) id = newId()
            val imported = request.project
            val name = request.name.trim().ifBlank { imported?.name?.trim().orEmpty() }.ifBlank { "Untitled Board" }
            val project = (imported ?: Project(id = id, name = name)).copy(id = id, name = name)
            val now = clock()
            val row = BoardRow(id, name, encode(project), rev = 1, createdAt = now, updatedAt = now, archived = false, vaultFolder = null)
            io { db.insertBoard(row) }
            BoardState(id, project, rev = 1, createdAt = now, updatedAt = now, archived = false, vaultFolder = null, lastHistoryAt = 0)
                .also { boards[id] = it }
        }
        log.info("Created board ${state.id} \"${state.project.name}\" (${state.project.nodes.size} nodes)")
        notifyListeners(state.id)
        return mutex.withLock { state.summary() }
    }

    suspend fun snapshot(id: String): BoardSnapshot? = mutex.withLock {
        boards[id]?.let { BoardSnapshot(it.project, it.rev) }
    }

    suspend fun updateMeta(id: String, update: BoardMetaUpdate): BoardSummary? {
        val (summary, changed) = mutex.withLock {
            val state = boards[id] ?: return null
            val archived = update.archived
            if (archived == null || archived == state.archived) return@withLock state.summary() to false
            // Archiving is bookkeeping, not an edit: updatedAt (the list's sort key) stays put.
            io { db.updateBoardMeta(id, archived, state.updatedAt) }
            state.archived = archived
            state.summary() to true
        }
        if (changed) notifyListeners(id)
        return summary
    }

    /**
     * Applies a client's patch. `applyPatch` is deterministic, so when the client's
     * [PatchRequest.baseRev] is the revision the patch was applied to, the client can
     * compute the result itself and gets `project = null`. In every other case —
     * including a no-op patch from a stale client — it gets the merged board back, or
     * it would adopt its own copy as server state and miss the other person's edits.
     */
    suspend fun patch(id: String, request: PatchRequest): PatchResponse? {
        val response = mutex.withLock {
            val state = boards[id] ?: return null
            val revBefore = state.rev
            val current = state.project
            val next = current.applyPatch(request.patch).copy(id = id)
            val upToDate = request.baseRev == revBefore
            if (next == current) return PatchResponse(revBefore, project = current.takeUnless { upToDate })

            val now = clock()
            // Cheap safety net against the other person deleting things: a snapshot of the
            // board as it was before the first change in every 30-minute window.
            if (now - state.lastHistoryAt >= HISTORY_INTERVAL_MS) {
                io { db.insertHistory(id, revBefore, now, encode(current)) }
                state.lastHistoryAt = now
            }
            val rev = revBefore + 1
            io { db.updateBoardContent(id, next.name, encode(next), rev, now) }
            state.project = next
            state.rev = rev
            state.updatedAt = now
            state.version.value++
            PatchResponse(rev, project = next.takeUnless { upToDate })
        }
        notifyListeners(id)
        return response
    }

    // ---- messages ----

    /** Messages of board [id] with seq > [since], oldest first, tombstones included. Null if the board is unknown. */
    suspend fun messagesSince(id: String, since: Long): List<Message>? = mutex.withLock {
        boards[id]?.messagesAfter(since)
    }

    /**
     * Stores a new message on board [boardId], stamping the server-owned fields.
     * Idempotent by id, so a client can safely retry a post whose response it never got.
     * Returns null if the board is unknown.
     */
    /** Stores a new message as written by [author] (the signed-in user — the client's claim is ignored). */
    suspend fun postMessage(boardId: String, incoming: Message, author: String = incoming.author): Message? {
        val stored = mutex.withLock {
            val state = boards[boardId] ?: return null
            messageBoard[incoming.id]?.let { home -> return boards.getValue(home).messages.getValue(incoming.id) }
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
                seq = ++lastSeq,
            )
            io {
                db.upsertMessage(message)
                db.touchBoard(boardId, now)
            }
            state.putMessage(message)
            state.updatedAt = now
            messageBoard[message.id] = boardId
            message
        }
        notifyListeners(boardId)
        return stored
    }

    /** Applies the non-null fields of [update]. Returns null if the message is unknown. */
    /**
     * Applies [update]. Anyone may rate, re-kind, file or tick off a message; only its
     * author ([actor]) may change its text or delete it. A null [actor] skips the check.
     */
    suspend fun updateMessage(id: String, update: MessageUpdate, actor: String? = null): Message? {
        val (message, boardId) = mutex.withLock {
            val boardId = messageBoard[id] ?: return null
            val state = boards.getValue(boardId)
            val old = state.messages.getValue(id)
            if (actor != null && !old.author.equals(actor, ignoreCase = true) &&
                ((update.text != null && update.text != old.text) || update.deleted == true)
            ) {
                throw ForbiddenException("Only ${old.author} can edit or delete this message")
            }
            update.text?.let { require(it.isNotBlank()) { "Message text is empty" } }
            val now = clock()
            val edited = old.copy(
                text = update.text ?: old.text,
                kind = update.kind ?: old.kind,
                importance = update.importance?.let(::clampImportance) ?: old.importance,
                nodeId = update.nodeId ?: old.nodeId,
                linkedNodeIds = update.linkedNodeIds?.distinct() ?: old.linkedNodeIds,
                done = update.done ?: old.done,
                deleted = update.deleted ?: old.deleted,
            )
            if (edited == old) return old // nothing to tell anyone
            val message = edited.copy(
                editedAt = if (edited.text != old.text) now else old.editedAt,
                seq = ++lastSeq,
            )
            io {
                db.upsertMessage(message)
                db.touchBoard(boardId, now)
            }
            state.putMessage(message)
            state.updatedAt = now
            message to boardId
        }
        notifyListeners(boardId)
        return message
    }

    /**
     * After an account rename (the database already renamed the message authors):
     * updates messages in memory and node owner/creator fields on every board, as
     * normal revisions so open clients pick the change up.
     */
    suspend fun renameAuthor(old: String, new: String) {
        val changed = mutableListOf<String>()
        mutex.withLock {
            val now = clock()
            for (state in boards.values) {
                var touched = false
                for (m in state.messages.values.toList()) {
                    if (m.author == old) {
                        state.putMessage(m.copy(author = new, seq = ++lastSeq))
                        touched = true
                    }
                }
                val nodes = state.project.nodes.map { n ->
                    if (n.owner == old || n.createdBy == old) {
                        n.copy(owner = if (n.owner == old) new else n.owner, createdBy = if (n.createdBy == old) new else n.createdBy)
                    } else {
                        n
                    }
                }
                if (nodes != state.project.nodes) {
                    val next = state.project.copy(nodes = nodes)
                    state.rev++
                    io { db.updateBoardContent(state.id, next.name, encode(next), state.rev, now) }
                    state.project = next
                    touched = true
                }
                if (touched) {
                    // Re-store renamed messages so their new seq survives a restart.
                    io { state.messages.values.filter { it.author == new }.forEach(db::upsertMessage) }
                    state.version.value++
                    changed += state.id
                }
            }
        }
        changed.forEach(::notifyListeners)
    }

    // ---- sync ----

    /**
     * Long-poll: answers as soon as board [id] has a rev above [rev] or a message
     * seq above [seq], waiting up to [waitMillis] for that to happen. Null if the
     * board is unknown.
     */
    suspend fun sync(id: String, rev: Long, seq: Long, waitMillis: Long): SyncResponse? {
        val version = mutex.withLock { boards[id]?.version } ?: return null
        // Read the version *before* checking, so a change landing between the check
        // and the wait still wakes us (the flow will already differ from `seen`).
        val seen = version.value
        val ready = mutex.withLock { boards.getValue(id).let { it.rev > rev || it.maxSeq > seq } }
        if (!ready && waitMillis > 0) {
            withTimeoutOrNull(waitMillis) { version.first { it != seen } }
        }
        return mutex.withLock {
            val state = boards.getValue(id)
            val messages = state.messagesAfter(seq)
            SyncResponse(
                rev = state.rev,
                project = state.project.takeIf { state.rev > rev },
                messages = messages,
                seq = maxOf(seq, messages.maxOfOrNull { it.seq } ?: seq),
            )
        }
    }

    // ---- vault mirror ----

    suspend fun vaultEntries(): List<VaultEntry> = mutex.withLock {
        boards.values.map { VaultEntry(it.summary(), it.vaultFolder) }
    }

    suspend fun vaultBoard(id: String): VaultBoard? = mutex.withLock {
        boards[id]?.let { VaultBoard(it.summary(), it.project, it.messages.values.toList()) }
    }

    suspend fun setVaultFolder(id: String, folder: String?) = mutex.withLock {
        val state = boards[id] ?: return@withLock
        if (state.vaultFolder == folder) return@withLock
        io { db.setVaultFolder(id, folder) }
        state.vaultFolder = folder
    }

    // ---- helpers ----

    private fun BoardState.messagesAfter(since: Long): List<Message> =
        messages.values.filter { it.seq > since }.sortedBy { it.seq }

    /** Records [message] and wakes this board's long-polls. Caller holds the lock. */
    private fun BoardState.putMessage(message: Message) {
        messages[message.id] = message
        maxSeq = maxOf(maxSeq, message.seq)
        version.value++
    }

    private fun notifyListeners(boardId: String) {
        for (listener in listeners) {
            try {
                listener(boardId)
            } catch (e: Exception) {
                log.error("Board listener failed for $boardId", e)
            }
        }
    }

    private fun encode(project: Project): String = ApiJson.encodeToString(Project.serializer(), project)

    /** JDBC is blocking; keep it off the request dispatcher. */
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private companion object {
        val log = LoggerFactory.getLogger(BoardStore::class.java)
        const val HISTORY_INTERVAL_MS = 30 * 60 * 1000L
    }
}
