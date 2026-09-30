package com.nodenote.server

import com.nodenote.api.ChangePasswordRequest
import com.nodenote.api.ChangeUsernameRequest
import com.nodenote.api.LoginRequest
import com.nodenote.api.LoginResponse
import com.nodenote.api.MessageUpdate
import com.nodenote.model.Message
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AuthTest {

    @Test
    fun apiNeedsSignInButInfoAndLoginDoNot() = serverTest {
        assertEquals(HttpStatusCode.Unauthorized, anonymous.get("/api/boards").status)
        assertEquals(HttpStatusCode.OK, anonymous.get("/api/info").status)
        val bad = anonymous.post("/api/auth/login") { json(LoginRequest(TEST_USER, "wrong-password")) }
        assertEquals(HttpStatusCode.Unauthorized, bad.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/boards").status)
    }

    @Test
    fun repeatedFailuresAreThrottled() = serverTest {
        repeat(5) { anonymous.post("/api/auth/login") { json(LoginRequest("Nick", "nope-nope")) } }
        val locked = anonymous.post("/api/auth/login") { json(LoginRequest("Nick", TEST_PASSWORD)) }
        assertEquals(HttpStatusCode.TooManyRequests, locked.status)
    }

    @Test
    fun serverStampsTheAuthorAndOnlyTheAuthorEditsText() = serverTest { app ->
        val board = createBoard("B")
        val posted = post(board.id, Message("m1", board.id, author = "Somebody Else", text = "hi"))
        assertEquals(TEST_USER, posted.author)

        val nick = signIn("Nick", builder = app)
        val edit = nick.patch("/api/messages/m1") { json(MessageUpdate(text = "hacked")) }
        assertEquals(HttpStatusCode.Forbidden, edit.status)
        val rate = nick.patch("/api/messages/m1") { json(MessageUpdate(importance = 8)) }
        assertEquals(HttpStatusCode.OK, rate.status)
    }

    @Test
    fun changePasswordAndUsername() = serverTest { app ->
        val board = createBoard("B")
        post(board.id, Message("m1", board.id, author = "", text = "mine"))

        val wrong = client.post("/api/auth/password") { json(ChangePasswordRequest("nope", "new-password-1")) }
        assertEquals(HttpStatusCode.Forbidden, wrong.status)
        val ok = client.post("/api/auth/password") { json(ChangePasswordRequest(TEST_PASSWORD, "new-password-1")) }
        assertEquals(HttpStatusCode.NoContent, ok.status)

        val renamed: LoginResponse = client.post("/api/auth/username") {
            json(ChangeUsernameRequest("new-password-1", "Benjamin"))
        }.body()
        assertEquals("Benjamin", renamed.username)
        val messages: List<Message> = client.get("/api/boards/${board.id}/messages?since=0").body()
        assertEquals("Benjamin", messages.single().author)

        val taken = client.post("/api/auth/username") { json(ChangeUsernameRequest("new-password-1", "nick")) }
        assertEquals(HttpStatusCode.BadRequest, taken.status)
        // The old name no longer signs in; the new one does, with the new password.
        assertNotEquals(
            HttpStatusCode.OK,
            anonymous.post("/api/auth/login") { json(LoginRequest(TEST_USER, "new-password-1")) }.status,
        )
        assertEquals(HttpStatusCode.OK, anonymous.post("/api/auth/login") { json(LoginRequest("benjamin", "new-password-1")) }.status)
    }
}
