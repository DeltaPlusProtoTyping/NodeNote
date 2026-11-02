package com.nodenote.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nodenote.api.BoardSummary
import com.nodenote.api.CreateBoardRequest
import com.nodenote.model.Project
import com.nodenote.storage.ClientPrefs
import com.nodenote.storage.ProjectSerializer
import com.nodenote.storage.ProjectStorage
import com.nodenote.storage.reloadWhenHidden
import com.nodenote.storage.showNotification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Top-level multi-board state while signed in: the board list and the open tabs
 * (each a full [AppState] bound to one server board, or a Home tab). Per-board
 * logic stays in [AppState].
 */
class Workspace(val session: Session) {

    val tabs = mutableStateListOf<AppState>()

    /** The tab on screen. */
    var current by mutableStateOf<AppState?>(null)
        private set

    // ---- Boards panel (left) ----
    var boards by mutableStateOf(listOf<BoardSummary>())
        private set
    var showArchived by mutableStateOf(false)
        private set
    var boardsPanelVisible by mutableStateOf(true)
    var boardsPanelWidth by mutableStateOf(260f)
        private set

    // ---- Dialogs owned by the workspace ----
    var settingsOpen by mutableStateOf(false)
    var newBoardOpen by mutableStateOf(false)
    var activityOpen by mutableStateOf(false)
    var historyOpen by mutableStateOf(false)

    /** The first-run tips card, until "Got it" (then never again on this device, unless asked for). */
    var tipsVisible by mutableStateOf(!ClientPrefs.tipsDismissed)

    fun dismissTips() {
        tipsVisible = false
        ClientPrefs.tipsDismissed = true
    }

    fun resizeBoardsPanel(deltaDp: Float) {
        boardsPanelWidth = (boardsPanelWidth + deltaDp).coerceIn(190f, 460f)
    }

    private var refreshJob: Job? = null
    private var repliesJob: Job? = null

    init {
        // Reopen what was open last time; always keep at least one tab.
        val reopen = ClientPrefs.openBoardIds
        if (reopen.isEmpty()) newTab() else reopen.forEach { openBoard(it) }
        showInFocusedPane(tabs.first())
        refreshJob = session.scope.launch { refreshLoop() }
        repliesJob = session.scope.launch { repliesLoop() }
    }

    /** Stops all syncing (sign-out). Unsent edits get one last push. */
    fun dispose() {
        refreshJob?.cancel()
        repliesJob?.cancel()
        tabs.forEach { it.closeBoard() }
    }

    /** The tab on screen — it drives the toolbar, right panel, keyboard and dialogs. */
    val focused: AppState
        get() = current ?: tabs.first()

    fun isFocused(state: AppState): Boolean = focused === state

    // ---- Board list ----

    /** Refreshes the board list now and every 20 s (also how the dot next to Online recovers). */
    private suspend fun refreshLoop() {
        while (true) {
            refreshBoardsNow()
            delay(20_000)
        }
    }

    private suspend fun refreshBoardsNow() {
        try {
            val wasStale = session.updateAvailable
            session.noteServerInfo(session.api.info())
            if (session.updateAvailable && !wasStale) {
                // Reload as soon as nobody is looking and nothing is unsent.
                reloadWhenHidden { tabs.none { it.dirty || it.draftActive || it.pendingMessageIds.isNotEmpty() } }
            }
            boards = session.api.boards(includeArchived = showArchived)
            session.markOnline()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            session.markOffline(e)
        }
    }

    // ---- Replies (others chatting on my cards) ----

    /** While signed in to a server: checks for replies now and every minute. */
    private suspend fun repliesLoop() {
        if (session.onThisDevice) return
        delay(5_000) // let the boards open first
        while (true) {
            checkReplies()
            delay(60_000)
        }
    }

    private suspend fun checkReplies() {
        val key = "${session.serverUrl}|${session.userName}"
        try {
            val seen = ClientPrefs.repliesSeen(key)
            val replies = session.api.replies(seen ?: 0)
            val newest = replies.maxOfOrNull { it.seq } ?: (seen ?: 0)
            // The first check on this device only learns where things stand.
            if (seen != null) replies.sortedBy { it.seq }.forEach(::announce)
            ClientPrefs.saveRepliesSeen(key, maxOf(newest, seen ?: 0))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline or an older server: try again next time.
        }
    }

    private fun announce(reply: com.nodenote.api.Reply) {
        val title = "${reply.author} on ${reply.nodeTitle.ifBlank { reply.boardName }}"
        val shown = showNotification(title, reply.text.take(200), "reply-${reply.seq}") { openBoardAt(reply.boardId, reply.nodeId) }
        if (!shown) focused.status = "$title: ${reply.text.lineSequence().first().take(80)}"
    }

    /** Opens a board and, once it has loaded, selects [nodeId] (its chat shows). */
    fun openBoardAt(boardId: String, nodeId: String) {
        openBoard(boardId)
        val tab = tabs.firstOrNull { it.boardId == boardId } ?: return
        session.scope.launch {
            repeat(100) {
                if (tab.isLoaded) {
                    if (tab.project.nodes.any { it.id == nodeId }) {
                        tab.selectOnly(nodeId)
                        tab.rightTab = AppState.RightTab.Chat
                        tab.focusNode(nodeId)
                    }
                    return@launch
                }
                delay(150)
            }
        }
    }

    fun refreshBoards() {
        session.scope.launch { refreshBoardsNow() }
    }

    fun toggleShowArchived() {
        showArchived = !showArchived
        refreshBoards()
    }

    fun summaryOf(boardId: String?): BoardSummary? = boards.find { it.id == boardId }

    // ---- Opening boards ----

    /** Opens a server board: focuses its tab if already open, reuses an empty Home tab, else opens a new tab. */
    fun openBoard(boardId: String) {
        tabs.firstOrNull { it.boardId == boardId }?.let {
            selectTab(it)
            return
        }
        val home = focusedOrNull()?.takeIf { it.boardId == null }
        val target = home ?: AppState(session).also { tabs.add(it) }
        target.openBoard(boardId)
        if (current != null) showInFocusedPane(target)
        rememberOpenBoards()
    }

    /** Creates a board on the server (optionally importing [project]) and opens it. */
    fun createBoard(name: String, project: Project? = null) {
        val status = focusedOrNull()
        session.scope.launch {
            try {
                val summary = session.api.createBoard(CreateBoardRequest(name.trim(), project, session.userName))
                refreshBoardsNow()
                openBoard(summary.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                session.markOffline(e)
                status?.status = "Could not create the board — is the server running at ${session.serverUrl}?"
            }
        }
    }

    fun setArchived(boardId: String, archived: Boolean) {
        session.scope.launch {
            try {
                session.api.setArchived(boardId, archived)
                refreshBoardsNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                session.markOffline(e)
            }
        }
    }

    /** Imports a NodeNote .json file as a new board. */
    fun importBoardFromFile() {
        ProjectStorage.pickFile(".json") { picked ->
            if (picked == null) return@pickFile
            val project = try {
                ProjectSerializer.decode(picked.bytes.decodeToString())
            } catch (e: Exception) {
                focused.status = "${picked.name} is not a NodeNote board/project file"
                return@pickFile
            }
            createBoard(project.name.ifBlank { picked.name.substringBeforeLast('.') }, project)
        }
    }

    /** Downloads/saves the focused board as a .json file (backup, or to share outside the server). */
    fun exportFocusedBoard() {
        val state = focused
        if (!state.isLoaded) return
        val fileName = state.project.name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "board" } + ".json"
        ProjectStorage.saveFile(fileName, ProjectSerializer.encode(state.project).encodeToByteArray()) { path ->
            state.status = if (path != null) "Exported $path" else "Export cancelled"
        }
    }

    private fun rememberOpenBoards() {
        ClientPrefs.openBoardIds = tabs.mapNotNull { it.boardId }
    }

    private fun focusedOrNull(): AppState? = if (tabs.isEmpty()) null else focused

    // ---- Tab management ----

    fun newTab(): AppState {
        val s = AppState(session)
        tabs.add(s)
        showInFocusedPane(s)
        return s
    }

    /** Brings an existing tab into the focused pane (clicking a tab in the bar). */
    fun selectTab(state: AppState) {
        showInFocusedPane(state)
    }

    private fun showInFocusedPane(state: AppState) {
        current = state
    }

    fun closeTab(state: AppState) {
        val idx = tabs.indexOf(state)
        if (idx < 0) return
        state.closeBoard()
        tabs.removeAt(idx)
        rememberOpenBoards()
        if (tabs.isEmpty()) {
            // Never leave zero tabs open.
            current = AppState(session).also { tabs.add(it) }
            return
        }
        if (current === state) current = tabs[idx.coerceAtMost(tabs.size - 1)]
    }
}
