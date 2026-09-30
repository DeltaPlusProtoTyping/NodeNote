package com.nodenote.server

import com.nodenote.api.ApiJson
import com.nodenote.model.Message
import com.nodenote.model.MessageKind
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

/** One row of `boards`. The board itself is stored as the same JSON the clients exchange. */
data class BoardRow(
    val id: String,
    val name: String,
    val json: String,
    val rev: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val archived: Boolean,
    /** The vault folder the board was last written to, so a rename can clean up the old one. */
    val vaultFolder: String?,
)

/**
 * Plain-JDBC access to the SQLite file that is the server's source of truth.
 *
 * Deliberately small: [BoardStore] keeps every board and message in memory and
 * only uses this to load at startup and to write through on every change. There
 * is one connection, and all methods are synchronized on it — SQLite serializes
 * writers anyway, and the load is two people.
 *
 * WAL mode keeps readers (e.g. a backup copying the file) from blocking writes and
 * makes each commit a cheap append.
 */
class Database(file: Path) : AutoCloseable {

    private val connection: Connection

    init {
        file.parent?.let { Files.createDirectories(it) }
        connection = DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}")
        connection.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA synchronous=NORMAL")
            st.execute("PRAGMA busy_timeout=5000")
            st.execute(
                """CREATE TABLE IF NOT EXISTS boards(
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    json TEXT NOT NULL,
                    rev INTEGER NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    archived INTEGER NOT NULL DEFAULT 0,
                    vault_folder TEXT
                )""",
            )
            st.execute(
                """CREATE TABLE IF NOT EXISTS messages(
                    id TEXT PRIMARY KEY,
                    board_id TEXT NOT NULL,
                    node_id TEXT NOT NULL,
                    author TEXT NOT NULL,
                    text TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    importance INTEGER NOT NULL,
                    linked TEXT NOT NULL,
                    done INTEGER NOT NULL,
                    created_at INTEGER NOT NULL,
                    edited_at INTEGER NOT NULL,
                    deleted INTEGER NOT NULL,
                    seq INTEGER NOT NULL
                )""",
            )
            st.execute("CREATE INDEX IF NOT EXISTS messages_board_seq ON messages(board_id, seq)")
            st.execute("CREATE INDEX IF NOT EXISTS messages_board_node ON messages(board_id, node_id)")
            st.execute(
                """CREATE TABLE IF NOT EXISTS board_history(
                    board_id TEXT NOT NULL,
                    rev INTEGER NOT NULL,
                    saved_at INTEGER NOT NULL,
                    json TEXT NOT NULL
                )""",
            )
            st.execute("CREATE INDEX IF NOT EXISTS board_history_board ON board_history(board_id, saved_at)")
        }
        migrate()
    }

    /**
     * Schema versioning via SQLite's `user_version`. Version 1 is the tables created
     * above; each later change appends one step to [migrations] (never edit a released
     * step). A database written by a *newer* server is refused rather than opened, so
     * running an old build can't quietly mangle newer data.
     */
    private fun migrate() {
        val current = connection.createStatement().use { st ->
            st.executeQuery("PRAGMA user_version").use { rs -> rs.next(); rs.getInt(1) }
        }
        check(current <= SCHEMA_VERSION) {
            "Database schema v$current is newer than this server (v$SCHEMA_VERSION) — refusing to open it. Update the server."
        }
        if (current == SCHEMA_VERSION) return
        connection.autoCommit = false
        try {
            connection.createStatement().use { st ->
                for (version in (current + 1)..SCHEMA_VERSION) {
                    migrations[version]?.forEach(st::execute)
                }
                st.execute("PRAGMA user_version = $SCHEMA_VERSION")
            }
            connection.commit()
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    /**
     * Writes a consistent copy of the whole database to [target] (SQLite `VACUUM INTO`),
     * safe while the server is running.
     */
    @Synchronized
    fun backupTo(target: Path) {
        target.parent?.let { Files.createDirectories(it) }
        connection.createStatement().use { st ->
            st.execute("VACUUM INTO '${target.toAbsolutePath().toString().replace("'", "''")}'")
        }
    }

    @Synchronized
    fun isEmpty(): Boolean = query("SELECT (SELECT COUNT(*) FROM boards) + (SELECT COUNT(*) FROM messages)") { it.getLong(1) }
        .single() == 0L

    companion object {
        /** Bump together with a new entry in [migrations]. */
        const val SCHEMA_VERSION = 2

        /** version → SQL that upgrades version-1 → version. Version 1 is the baseline above. */
        private val migrations: Map<Int, List<String>> = mapOf(
            // v2: sign-in. Sessions store only a hash of the token.
            2 to listOf(
                """CREATE TABLE IF NOT EXISTS users(
                    username TEXT PRIMARY KEY COLLATE NOCASE,
                    password_hash TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )""",
                """CREATE TABLE IF NOT EXISTS sessions(
                    token_hash TEXT PRIMARY KEY,
                    username TEXT NOT NULL COLLATE NOCASE,
                    created_at INTEGER NOT NULL,
                    last_seen INTEGER NOT NULL
                )""",
            ),
        )

        private val LinkedIds = ListSerializer(String.serializer())
    }

    // ---- users & sessions ----

    @Synchronized
    fun users(): List<String> = query("SELECT username FROM users ORDER BY username") { it.getString(1) }

    /** (canonical username, password hash) for a case-insensitive [username], or null. */
    @Synchronized
    fun user(username: String): Pair<String, String>? =
        queryWith("SELECT username, password_hash FROM users WHERE username = ?", { setString(1, username) }) {
            it.getString(1) to it.getString(2)
        }.singleOrNull()

    @Synchronized
    fun insertUser(username: String, passwordHash: String, now: Long) = update(
        "INSERT INTO users(username, password_hash, created_at, updated_at) VALUES(?,?,?,?)",
    ) {
        setString(1, username)
        setString(2, passwordHash)
        setLong(3, now)
        setLong(4, now)
    }

    @Synchronized
    fun setPasswordHash(username: String, passwordHash: String, now: Long) = update(
        "UPDATE users SET password_hash = ?, updated_at = ? WHERE username = ?",
    ) {
        setString(1, passwordHash)
        setLong(2, now)
        setString(3, username)
    }

    /** Renames the account, its sessions, and the author of its messages — atomically. */
    @Synchronized
    fun renameUser(old: String, new: String, now: Long) = transaction {
        update("UPDATE users SET username = ?, updated_at = ? WHERE username = ?") {
            setString(1, new); setLong(2, now); setString(3, old)
        }
        update("UPDATE sessions SET username = ? WHERE username = ?") { setString(1, new); setString(2, old) }
        update("UPDATE messages SET author = ? WHERE author = ?") { setString(1, new); setString(2, old) }
    }

    @Synchronized
    fun insertSession(tokenHash: String, username: String, now: Long) = update(
        "INSERT INTO sessions(token_hash, username, created_at, last_seen) VALUES(?,?,?,?)",
    ) {
        setString(1, tokenHash)
        setString(2, username)
        setLong(3, now)
        setLong(4, now)
    }

    /** (username, last_seen) for a session, or null. */
    @Synchronized
    fun session(tokenHash: String): Pair<String, Long>? =
        queryWith("SELECT username, last_seen FROM sessions WHERE token_hash = ?", { setString(1, tokenHash) }) {
            it.getString(1) to it.getLong(2)
        }.singleOrNull()

    @Synchronized
    fun touchSession(tokenHash: String, now: Long) = update("UPDATE sessions SET last_seen = ? WHERE token_hash = ?") {
        setLong(1, now); setString(2, tokenHash)
    }

    @Synchronized
    fun deleteSession(tokenHash: String) = update("DELETE FROM sessions WHERE token_hash = ?") { setString(1, tokenHash) }

    /** Signs a user out everywhere except [keepTokenHash] (after a password change). */
    @Synchronized
    fun deleteOtherSessions(username: String, keepTokenHash: String?) = update(
        "DELETE FROM sessions WHERE username = ? AND token_hash <> ?",
    ) {
        setString(1, username); setString(2, keepTokenHash ?: "")
    }

    @Synchronized
    fun deleteSessionsIdleSince(cutoff: Long) = update("DELETE FROM sessions WHERE last_seen < ?") { setLong(1, cutoff) }

    // ---- boards ----

    @Synchronized
    fun loadBoards(): List<BoardRow> = query(
        "SELECT id, name, json, rev, created_at, updated_at, archived, vault_folder FROM boards",
    ) { rs ->
        BoardRow(
            id = rs.getString(1),
            name = rs.getString(2),
            json = rs.getString(3),
            rev = rs.getLong(4),
            createdAt = rs.getLong(5),
            updatedAt = rs.getLong(6),
            archived = rs.getInt(7) != 0,
            vaultFolder = rs.getString(8),
        )
    }

    @Synchronized
    fun insertBoard(row: BoardRow) = update(
        "INSERT INTO boards(id, name, json, rev, created_at, updated_at, archived, vault_folder) VALUES(?,?,?,?,?,?,?,?)",
    ) {
        setString(1, row.id)
        setString(2, row.name)
        setString(3, row.json)
        setLong(4, row.rev)
        setLong(5, row.createdAt)
        setLong(6, row.updatedAt)
        setInt(7, if (row.archived) 1 else 0)
        setString(8, row.vaultFolder)
    }

    @Synchronized
    fun updateBoardContent(id: String, name: String, json: String, rev: Long, updatedAt: Long) = update(
        "UPDATE boards SET name = ?, json = ?, rev = ?, updated_at = ? WHERE id = ?",
    ) {
        setString(1, name)
        setString(2, json)
        setLong(3, rev)
        setLong(4, updatedAt)
        setString(5, id)
    }

    @Synchronized
    fun updateBoardMeta(id: String, archived: Boolean, updatedAt: Long) = update(
        "UPDATE boards SET archived = ?, updated_at = ? WHERE id = ?",
    ) {
        setInt(1, if (archived) 1 else 0)
        setLong(2, updatedAt)
        setString(3, id)
    }

    @Synchronized
    fun touchBoard(id: String, updatedAt: Long) = update("UPDATE boards SET updated_at = ? WHERE id = ?") {
        setLong(1, updatedAt)
        setString(2, id)
    }

    @Synchronized
    fun setVaultFolder(id: String, folder: String?) = update("UPDATE boards SET vault_folder = ? WHERE id = ?") {
        setString(1, folder)
        setString(2, id)
    }

    // ---- history ----

    /** Newest history snapshot time per board, so the 30-minute throttle survives restarts. */
    @Synchronized
    fun latestHistoryTimes(): Map<String, Long> =
        query("SELECT board_id, MAX(saved_at) FROM board_history GROUP BY board_id") { rs ->
            rs.getString(1) to rs.getLong(2)
        }.toMap()

    @Synchronized
    fun insertHistory(boardId: String, rev: Long, savedAt: Long, json: String) = update(
        "INSERT INTO board_history(board_id, rev, saved_at, json) VALUES(?,?,?,?)",
    ) {
        setString(1, boardId)
        setLong(2, rev)
        setLong(3, savedAt)
        setString(4, json)
    }

    // ---- messages ----

    @Synchronized
    fun loadMessages(): List<Message> = query(
        """SELECT id, board_id, node_id, author, text, kind, importance, linked, done,
                  created_at, edited_at, deleted, seq
           FROM messages ORDER BY seq""",
    ) { rs ->
        Message(
            id = rs.getString(1),
            boardId = rs.getString(2),
            nodeId = rs.getString(3),
            author = rs.getString(4),
            text = rs.getString(5),
            kind = MessageKind.entries.find { it.name == rs.getString(6) } ?: MessageKind.Note,
            importance = rs.getInt(7),
            linkedNodeIds = ApiJson.decodeFromString(LinkedIds, rs.getString(8)),
            done = rs.getInt(9) != 0,
            createdAt = rs.getLong(10),
            editedAt = rs.getLong(11),
            deleted = rs.getInt(12) != 0,
            seq = rs.getLong(13),
        )
    }

    @Synchronized
    fun maxSeq(): Long = query("SELECT COALESCE(MAX(seq), 0) FROM messages") { it.getLong(1) }.single()

    /** Inserts or fully replaces a message (every column comes from [m]). */
    @Synchronized
    fun upsertMessage(m: Message) = update(
        """INSERT OR REPLACE INTO messages(id, board_id, node_id, author, text, kind, importance, linked, done,
                                           created_at, edited_at, deleted, seq)
           VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)""",
    ) {
        setString(1, m.id)
        setString(2, m.boardId)
        setString(3, m.nodeId)
        setString(4, m.author)
        setString(5, m.text)
        setString(6, m.kind.name)
        setInt(7, m.importance)
        setString(8, ApiJson.encodeToString(LinkedIds, m.linkedNodeIds))
        setInt(9, if (m.done) 1 else 0)
        setLong(10, m.createdAt)
        setLong(11, m.editedAt)
        setInt(12, if (m.deleted) 1 else 0)
        setLong(13, m.seq)
    }

    @Synchronized
    override fun close() = connection.close()

    // ---- helpers ----

    private fun <T> queryWith(sql: String, bind: PreparedStatement.() -> Unit, map: (ResultSet) -> T): List<T> =
        connection.prepareStatement(sql).use { st ->
            st.bind()
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
        }

    private fun transaction(block: () -> Unit) {
        connection.autoCommit = false
        try {
            block()
            connection.commit()
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    private fun <T> query(sql: String, map: (ResultSet) -> T): List<T> =
        connection.prepareStatement(sql).use { st ->
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(map(rs)) }
            }
        }

    private fun update(sql: String, bind: PreparedStatement.() -> Unit) {
        connection.prepareStatement(sql).use { st ->
            st.bind()
            st.executeUpdate()
        }
    }

}
