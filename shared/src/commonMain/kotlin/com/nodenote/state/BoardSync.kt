package com.nodenote.state

import androidx.compose.runtime.snapshotFlow
import com.nodenote.api.MessageUpdate
import com.nodenote.api.PatchRequest
import com.nodenote.model.BoardDiff
import com.nodenote.model.Message
import com.nodenote.model.Project
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SyncStatus(val label: String) {
    Loading("Loading…"),
    Synced("Synced"),
    Syncing("Saving…"),
    Offline("Offline — changes kept locally"),
}

/**
 * Keeps one open board ([AppState]) in step with the server.
 *
 * Board edits: the canvas edits [AppState.project] locally as before; a
 * debounced push sends `diff(syncedProject, project)` with the server revision
 * it was based on. When the other person's changes arrive (long-poll, or a
 * push answered with a merged board), local unsent work is re-based onto the
 * newer server board — see [AppState.applyRemote]. Patches are idempotent
 * upserts/deletes, so resending after a lost response is always safe.
 *
 * Chat: messages are posted optimistically (shown at once, confirmed when the
 * server echoes them back with a seq) and new ones stream in on the same
 * long-poll.
 */
class BoardSync(private val state: AppState, private val session: Session, val boardId: String) {

    private val scope = session.scope
    private var job: Job? = null
    private val mutex = Mutex()
    /** One patch on its way at a time (edits and restores), so a restore never races an edit. */
    private val sending = Mutex()
    /** History entry a restore not yet confirmed by the server put the board back to; 0 = none. */
    private var pendingRestore = 0L
    private var baseRev = 0L
    private var msgSeq = 0L

    fun start() {
        job?.cancel()
        state.syncStatus = SyncStatus.Loading
        job = scope.launch {
            if (!loadInitial()) return@launch
            launch { pushLoop() }
            pollLoop()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Sends any unsent edits one last time (tab closing), then stops. */
    fun flushAndStop() {
        val running = job ?: return
        job = null
        scope.launch {
            runCatching { pushNow() }
            running.cancel()
        }
    }

    // ---- Board ----

    /** Loads board + chat; false if the server doesn't have this board. */
    private suspend fun loadInitial(): Boolean {
        var attempt = 0
        while (true) {
            try {
                // Both at once: on a phone each request is a slow round trip.
                val (snapshot, messages) = coroutineScope {
                    val board = async { session.api.board(boardId) }
                    val chat = async { session.api.messages(boardId, since = 0) }
                    board.await() to chat.await()
                }
                mutex.withLock {
                    baseRev = snapshot.rev
                    msgSeq = messages.maxOfOrNull { it.seq } ?: 0L
                    state.loadFromServer(snapshot.project)
                    state.replaceMessages(messages)
                }
                session.markOnline()
                state.syncStatus = SyncStatus.Synced
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: com.nodenote.net.BoardMissingException) {
                // Same as a server's 404, for boards on this device.
                job = null
                state.boardMissing()
                return false
            } catch (e: ClientRequestException) {
                if (e.response.status == HttpStatusCode.NotFound) {
                    // A tab remembered from before (deleted board, other server): drop it quietly.
                    job = null
                    session.markOnline() // the server answered; it just doesn't have this board
                    state.boardMissing()
                    return false
                }
                failed(e)
                delay(backoff(attempt++))
            } catch (e: Exception) {
                failed(e)
                delay(backoff(attempt++))
            }
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun pushLoop() {
        snapshotFlow { state.project }
            .debounce(350)
            .collect {
                var attempt = 0
                while (true) {
                    try {
                        pushNow()
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed(e)
                        delay(backoff(attempt++))
                    }
                }
            }
    }

    private suspend fun pushNow() = sending.withLock { pushLocked() }

    /** Sends what's unsent ([pendingRestore] marks it as putting the board back to that saved copy). */
    private suspend fun pushLocked() {
        val restoredFrom = pendingRestore
        val sentLocal: Project
        val sentRev: Long
        val patch = mutex.withLock {
            val base = state.syncedProject ?: return
            sentLocal = state.project
            sentRev = baseRev
            BoardDiff.diff(base, sentLocal).takeUnless { it.isEmpty } ?: run {
                pendingRestore = 0
                return
            }
        }
        state.syncStatus = SyncStatus.Syncing
        val response = session.api.patch(boardId, PatchRequest(sentRev, session.userName, patch, restoredFrom))
        if (pendingRestore == restoredFrom) pendingRestore = 0
        mutex.withLock {
            val merged = response.project
            when {
                merged != null -> if (response.rev > baseRev) adoptServerBoard(merged, response.rev)
                // Server board == what we sent, unless a long-poll already moved us past it.
                baseRev == sentRev -> {
                    state.syncedProject = sentLocal
                    baseRev = response.rev
                }
            }
        }
        session.markOnline()
        state.syncStatus = if (state.dirty) SyncStatus.Syncing else SyncStatus.Synced
    }

    /**
     * Puts the board back the way it was at a saved copy ([entryId], the board [old] as it was
     * then): unsent edits go first, then ONE patch from the board as the server has it now to
     * [old], marked with the entry so the server saves the current board first (that's what
     * makes the restore undoable from the list). Chats aren't part of a board, so they stay.
     * Null when done, else what went wrong (nothing changed then, or the edit is kept and retried).
     */
    suspend fun restore(entryId: Long, old: Project): String? {
        try {
            sending.withLock {
                pushLocked()
                val changed = mutex.withLock { state.restoreLocally(old) }
                if (!changed) return "The board is already like that"
                // Kept until it's through, so a retry after a lost connection is still a restore.
                pendingRestore = entryId
                pushLocked()
            }
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(e)
            return if (pendingRestore != 0L) "Restored here; it goes to the server when it's back" else "Could not reach the server"
        }
    }

    private suspend fun pollLoop() {
        var attempt = 0
        while (true) {
            try {
                val response = session.api.sync(boardId, baseRev, msgSeq)
                mutex.withLock {
                    val board = response.project
                    if (board != null && response.rev > baseRev) adoptServerBoard(board, response.rev)
                    if (response.messages.isNotEmpty()) state.mergeMessages(response.messages)
                    msgSeq = maxOf(msgSeq, response.seq)
                }
                attempt = 0
                session.markOnline()
                if (state.syncStatus == SyncStatus.Offline) {
                    state.syncStatus = if (state.dirty) SyncStatus.Syncing else SyncStatus.Synced
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(e)
                delay(backoff(attempt++))
            }
        }
    }

    private fun adoptServerBoard(server: Project, rev: Long) {
        state.applyRemote(state.syncedProject ?: server, server)
        baseRev = rev
    }

    // ---- Chat ----

    fun send(message: Message) {
        state.upsertLocalMessage(message, pending = true)
        post(message)
    }

    /** Re-sends a message that failed (same id, so the server never stores it twice). */
    fun retry(messageId: String) {
        val message = state.messageById(messageId) ?: return
        state.upsertLocalMessage(message, pending = true)
        post(message)
    }

    private fun post(message: Message) {
        scope.launch {
            try {
                state.mergeMessages(listOf(session.api.postMessage(message)))
                session.markOnline()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(e)
                state.markMessageFailed(message.id)
                state.status = "Message not sent — click it to retry"
            }
        }
    }

    /** Applies [optimistic] locally at once, then confirms with the server (reverting on failure). */
    fun update(messageId: String, update: MessageUpdate, optimistic: (Message) -> Message) {
        val before = state.messageById(messageId) ?: return
        state.upsertLocalMessage(optimistic(before))
        scope.launch {
            try {
                state.mergeMessages(listOf(session.api.updateMessage(messageId, update)))
                session.markOnline()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(e)
                state.upsertLocalMessage(before)
                state.status = "Could not update the message — server unreachable"
            }
        }
    }

    private fun failed(e: Throwable) {
        session.markOffline(e)
        state.syncStatus = SyncStatus.Offline
    }

    private fun backoff(attempt: Int): Long = (1_000L shl attempt.coerceAtMost(4)).coerceAtMost(15_000L)
}
