package com.nodenote.server

import com.nodenote.api.ApiJson
import com.nodenote.api.BoardSummary
import com.nodenote.api.CreateBoardRequest
import com.nodenote.api.LoginRequest
import com.nodenote.api.LoginResponse
import com.nodenote.api.PatchRequest
import com.nodenote.api.PatchResponse
import com.nodenote.model.BoardPatch
import com.nodenote.model.Message
import com.nodenote.model.Node
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path

/** A fresh server over a throwaway data dir and vault, with a JSON client. */
class ServerFixture(
    /** Signed in as [TEST_USER]. */
    val client: HttpClient,
    val dataDir: Path,
    val vaultDir: Path,
    /** Not signed in. */
    val anonymous: HttpClient,
) {

    /** A client signed in as [username]. */
    suspend fun signIn(username: String, password: String = TEST_PASSWORD, builder: ApplicationTestBuilder): HttpClient {
        val login: LoginResponse = anonymous.post("/api/auth/login") { json(LoginRequest(username, password)) }.body()
        return builder.createClient {
            install(ContentNegotiation) { json(ApiJson) }
            defaultRequest { header(HttpHeaders.Authorization, "Bearer ${login.token}") }
        }
    }


    suspend fun createBoard(name: String): BoardSummary =
        client.post("/api/boards") { json(CreateBoardRequest(name)) }.body()

    suspend fun patch(boardId: String, baseRev: Long, patch: BoardPatch): PatchResponse =
        client.post("/api/boards/$boardId/patch") { json(PatchRequest(baseRev, "test", patch)) }.body()

    suspend fun post(boardId: String, message: Message): Message =
        client.post("/api/boards/$boardId/messages") { json(message) }.body()
}

/**
 * Runs [block] against a server whose data and vault live under [root]. Without a
 * [root], a temp directory is used and deleted afterwards; pass one to restart a
 * server over the same data.
 */
fun serverTest(
    vaultDebounceMillis: Long = 60_000,
    root: Path? = null,
    block: suspend ServerFixture.(ApplicationTestBuilder) -> Unit,
) {
    val dir = root ?: Files.createTempDirectory("nodenote-server-test")
    val config = ServerConfig(port = 0, dataDir = dir.resolve("data"), vaultDir = dir.resolve("vault"))
    Database(config.databaseFile).use { db ->
        val accounts = Accounts(db)
        for (name in listOf(TEST_USER, "Nick")) if (name !in accounts.users()) accounts.addUser(name, TEST_PASSWORD)
    }
    try {
        testApplication {
            application { nodeNoteModule(config, vaultDebounceMillis) }
            val anonymous = createClient { install(ContentNegotiation) { json(ApiJson) } }
            val fixture = ServerFixture(anonymous, config.dataDir, config.vaultDir, anonymous)
            val client = fixture.signIn(TEST_USER, builder = this)
            ServerFixture(client, config.dataDir, config.vaultDir, anonymous).block(this)
        }
    } finally {
        if (root == null) dir.toFile().deleteRecursively()
    }
}

const val TEST_USER = "Ben"
const val TEST_PASSWORD = "test-password"

inline fun <reified T> io.ktor.client.request.HttpRequestBuilder.json(body: T) {
    contentType(ContentType.Application.Json)
    setBody(body)
}

fun node(id: String, title: String, type: String = "Topic", createdAt: Long = 1) =
    Node(id = id, type = type, title = title, x = 0f, y = 0f, createdAt = createdAt)

fun message(id: String, boardId: String, nodeId: String, text: String, importance: Int = 0, linked: List<String> = emptyList()) =
    Message(id = id, boardId = boardId, nodeId = nodeId, author = "Ben", text = text, importance = importance, linkedNodeIds = linked)
