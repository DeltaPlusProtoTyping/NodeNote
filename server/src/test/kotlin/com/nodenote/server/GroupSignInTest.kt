package com.nodenote.server

import com.nodenote.api.ApiJson
import com.nodenote.api.JoinRequest
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

/** A server shared with a group of testers: one password, everyone joins under their own name. */
class GroupSignInTest {

    @Test
    fun withoutAGroupPasswordJoiningIsOff() = serverTest {
        assertFalse(anonymous.get("/api/info").body<ServerInfo>().groupSignIn)
        val attempt = anonymous.post("/api/auth/join") { json(JoinRequest("Sam", TEST_PASSWORD)) }
        assertEquals(HttpStatusCode.Forbidden, attempt.status)
    }

    @Test
    fun aGroupServerWithNoAccountsDoesNotOfferTheFirstAccount() {
        val dir = Files.createTempDirectory("nodenote-group-setup-test")
        try {
            val config = ServerConfig(port = 0, dataDir = dir.resolve("data"), vaultDir = dir.resolve("vault"))
            Database(config.databaseFile).use { db ->
                Accounts(db, groupPasswordFile = config.groupPasswordFile).setGroupPassword("group-secret")
            }
            testApplication {
                application { nodeNoteModule(config, vaultDebounceMillis = 60_000) }
                val client = createClient { install(ContentNegotiation) { json(ApiJson) } }
                val info = client.get("/api/info").body<ServerInfo>()
                assertTrue(info.groupSignIn)
                assertFalse(info.needsSetup)
                val claim = client.post("/api/auth/setup") { json(LoginRequest("Mallory", "other-password")) }
                assertEquals(HttpStatusCode.Forbidden, claim.status)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun anyoneWithTheGroupPasswordJoinsUnderTheirName() {
        val dir = Files.createTempDirectory("nodenote-group-test")
        try {
            val config = ServerConfig(port = 0, dataDir = dir.resolve("data"), vaultDir = dir.resolve("vault"))
            Database(config.databaseFile).use { db ->
                val accounts = Accounts(db, groupPasswordFile = config.groupPasswordFile)
                accounts.addUser("Ben", TEST_PASSWORD)
                accounts.setGroupPassword("group-secret")
            }
            assertFalse(Files.readString(config.groupPasswordFile).contains("group-secret"), "only a hash is kept")
            testApplication {
                application { nodeNoteModule(config, vaultDebounceMillis = 60_000) }
                val client = createClient { install(ContentNegotiation) { json(ApiJson) } }
                assertTrue(client.get("/api/info").body<ServerInfo>().groupSignIn)

                val wrong = client.post("/api/auth/join") { json(JoinRequest("Sam", "not-it")) }
                assertEquals(HttpStatusCode.Unauthorized, wrong.status)

                val sam: LoginResponse = client.post("/api/auth/join") { json(JoinRequest("Sam", "group-secret")) }.body()
                assertEquals("Sam", sam.username)
                val me = client.get("/api/auth/me") { header(HttpHeaders.Authorization, "Bearer ${sam.token}") }
                assertEquals(HttpStatusCode.OK, me.status)

                // Same name again (any case, another phone): the same member.
                val again: LoginResponse = client.post("/api/auth/join") { json(JoinRequest(" sam ", "group-secret")) }.body()
                assertEquals("Sam", again.username)

                // A real account's name can't be joined as, and members can't sign in the ordinary way.
                val asBen = client.post("/api/auth/join") { json(JoinRequest("ben", "group-secret")) }
                assertEquals(HttpStatusCode.Forbidden, asBen.status)
                for (password in listOf("group-secret", Accounts.GROUP_MEMBER, "")) {
                    val login = client.post("/api/auth/login") { json(LoginRequest("Sam", password)) }
                    assertTrue(login.status == HttpStatusCode.Unauthorized || login.status == HttpStatusCode.TooManyRequests)
                }
                assertEquals(HttpStatusCode.OK, client.post("/api/auth/login") { json(LoginRequest("Ben", TEST_PASSWORD)) }.status)

                val badName = client.post("/api/auth/join") { json(JoinRequest("x", "group-secret")) }
                assertEquals(HttpStatusCode.BadRequest, badName.status)
            }
            Database(config.databaseFile).use { db ->
                Accounts(db, groupPasswordFile = config.groupPasswordFile).setGroupPassword(null)
            }
            assertFalse(Files.exists(config.groupPasswordFile))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
