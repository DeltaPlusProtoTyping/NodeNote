package com.nodenote.server

import com.nodenote.api.ApiJson
import com.nodenote.api.LoginRequest
import com.nodenote.api.LoginResponse
import com.nodenote.api.ServerInfo
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A fresh install (the desktop app's own server): the first account is made from the sign-in screen. */
class SetupTest {

    @Test
    fun aFreshServerLetsItsFirstAccountBeCreatedOnce() {
        val dir = Files.createTempDirectory("nodenote-setup-test")
        try {
            val config = ServerConfig(port = 0, dataDir = dir.resolve("data"), vaultDir = dir.resolve("vault"))
            testApplication {
                application { nodeNoteModule(config, vaultDebounceMillis = 60_000) }
                val client = createClient { install(ContentNegotiation) { json(ApiJson) } }
                assertTrue(client.get("/api/info").body<ServerInfo>().needsSetup)

                // A bad password is turned down and leaves the server waiting for its first account.
                val tooShort = client.post("/api/auth/setup") { json(LoginRequest("Sam", "short")) }
                assertEquals(HttpStatusCode.BadRequest, tooShort.status)
                assertTrue(client.get("/api/info").body<ServerInfo>().needsSetup)

                val first: LoginResponse = client.post("/api/auth/setup") { json(LoginRequest("Sam", "first-password")) }.body()
                assertEquals("Sam", first.username)
                assertFalse(client.get("/api/info").body<ServerInfo>().needsSetup)
                val me = client.get("/api/auth/me") { header(HttpHeaders.Authorization, "Bearer ${first.token}") }
                assertEquals(HttpStatusCode.OK, me.status, "signed in straight away")

                // Never again, not even to add someone else.
                val again = client.post("/api/auth/setup") { json(LoginRequest("Mallory", "other-password")) }
                assertEquals(HttpStatusCode.Forbidden, again.status)
                assertEquals(HttpStatusCode.OK, client.post("/api/auth/login") { json(LoginRequest("Sam", "first-password")) }.status)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun aServerWithAccountsNeverOffersSetup() = serverTest {
        assertFalse(anonymous.get("/api/info").body<ServerInfo>().needsSetup)
        val attempt = anonymous.post("/api/auth/setup") { json(LoginRequest("Mallory", "other-password")) }
        assertEquals(HttpStatusCode.Forbidden, attempt.status)
    }

    @Test
    fun aServerStartedWithoutVaultKeepsWritingWhereTheDataWasMirroredBefore() {
        val dir = Files.createTempDirectory("nodenote-vault-memory-test")
        try {
            val data = dir.resolve("data")
            ServerConfig(port = 0, dataDir = data, vaultDir = dir.resolve("IdeaVerse").resolve("Work Boards")).rememberVault()
            val later = ServerConfig.load(arrayOf("--data", data.toString()), env = emptyMap())
            assertEquals(dir.resolve("IdeaVerse").resolve("Work Boards"), later.vaultDir)
            val explicit = ServerConfig.load(arrayOf("--data", data.toString(), "--vault", dir.resolve("other").toString()), env = emptyMap())
            assertEquals(dir.resolve("other"), explicit.vaultDir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
