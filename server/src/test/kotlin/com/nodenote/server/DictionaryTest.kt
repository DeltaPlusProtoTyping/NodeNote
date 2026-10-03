package com.nodenote.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DictionaryTest {

    @Test
    fun signedInClientsGetTheWordList() = serverTest {
        val response = client.get("/api/dictionary?v=1")
        assertEquals(HttpStatusCode.OK, response.status)
        val words = response.bodyAsText().lineSequence().toHashSet()
        assertTrue("efficiency" in words && "maintenance" in words && words.size > 100_000, "${words.size} words")
        assertTrue("max-age" in response.headers[HttpHeaders.CacheControl].orEmpty())

        assertEquals(HttpStatusCode.Unauthorized, anonymous.get("/api/dictionary").status)
    }
}
