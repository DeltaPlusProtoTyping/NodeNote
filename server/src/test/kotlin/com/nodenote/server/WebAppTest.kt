package com.nodenote.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The browser app as tools/prepare-web.mjs leaves it: each big file next to its .br and .gz copies. */
class WebAppTest {

    private val wasm = "0123456789abcdef0123.wasm"

    @Test
    fun browsersThatTakeBrotliGetTheBrotliCopy() = webTest {
        val response = anonymous.get("/$wasm") { header(HttpHeaders.AcceptEncoding, "gzip, deflate, br, zstd") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("br", response.headers[HttpHeaders.ContentEncoding])
        assertEquals("application/wasm", response.type())
        assertTrue("max-age=31536000" in response.headers[HttpHeaders.CacheControl].orEmpty())
        // Sent as it is: the server mustn't gzip it again on the way out.
        assertEquals(copy("brotli", wasm), response.bodyAsText())
    }

    @Test
    fun gzipOnlyBrowsersGetTheGzipCopy() = webTest {
        val response = anonymous.get("/$wasm") { header(HttpHeaders.AcceptEncoding, "gzip") }
        assertEquals("gzip", response.headers[HttpHeaders.ContentEncoding])
        assertEquals("application/wasm", response.type())
        assertEquals(copy("gzip", wasm), response.bodyAsText())
    }

    @Test
    fun othersGetTheFileItself() = webTest {
        val response = anonymous.get("/$wasm")
        assertNull(response.headers[HttpHeaders.ContentEncoding])
        assertEquals("application/wasm", response.type())
        assertEquals("raw $wasm", response.bodyAsText())
    }

    @Test
    fun pageAndScriptKeepTheirTypesAndAreCheckedEveryTime() = webTest {
        for (path in listOf("/", "/index.html")) {
            val page = anonymous.get(path) { header(HttpHeaders.AcceptEncoding, "br") }
            assertEquals(HttpStatusCode.OK, page.status, path)
            assertEquals("text/html", page.type(), path)
            assertEquals("no-cache", page.headers[HttpHeaders.CacheControl], path)
        }
        val script = anonymous.get("/app.js") { header(HttpHeaders.AcceptEncoding, "br") }
        assertEquals("br", script.headers[HttpHeaders.ContentEncoding])
        assertTrue("javascript" in script.type(), script.type())
        assertEquals("no-cache", script.headers[HttpHeaders.CacheControl])
    }

    private fun HttpResponse.type(): String = headers[HttpHeaders.ContentType].orEmpty().substringBefore(';')

    /** Over Compression's 1 KB minimum, so a second compression would show. */
    private fun copy(kind: String, name: String) = "$kind $name ".repeat(100)

    private fun webTest(block: suspend ServerFixture.() -> Unit) {
        val web: Path = Files.createTempDirectory("nodenote-web-test")
        try {
            for (name in listOf("index.html", "app.js", wasm)) {
                Files.writeString(web.resolve(name), "raw $name")
                Files.writeString(web.resolve("$name.br"), copy("brotli", name))
                Files.writeString(web.resolve("$name.gz"), copy("gzip", name))
            }
            serverTest(webDir = web) { block() }
        } finally {
            web.toFile().deleteRecursively()
        }
    }
}
