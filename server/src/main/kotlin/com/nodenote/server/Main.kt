package com.nodenote.server

import com.nodenote.api.ApiJson
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.defaultForFilePath
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.engine.defaultExceptionStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.CompressedFileType
import io.ktor.server.http.content.staticFiles
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.cachingheaders.CachingHeaders
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.conditionalheaders.ConditionalHeaders
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.matchContentType
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.nio.file.Files
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("com.nodenote.server.Main")

fun main(args: Array<String>) {
    // Admin commands: `users`, `adduser <name> <password>`, `passwd <name> <password>`
    // (plus the usual --data flag). They work on the database directly.
    val command = args.takeWhile { !it.startsWith("--") }
    val flags = args.drop(command.size).toTypedArray()
    val config = try {
        ServerConfig.load(flags)
    } catch (e: HelpRequested) {
        println(ServerConfig.USAGE)
        return
    } catch (e: IllegalArgumentException) {
        System.err.println("${e.message}\n\n${ServerConfig.USAGE}")
        exitProcess(2)
    }
    if (command.isNotEmpty()) {
        exitProcess(runAdminCommand(command, config))
    }
    log.info("NodeNote server $SERVER_VERSION")
    log.info("  listening  http://${config.host}:${config.port}")
    log.info("  database   ${config.databaseFile}")
    log.info("  vault      ${config.vaultDir}")
    log.info("  web app    ${describeWeb(config)}")

    embeddedServer(Netty, port = config.port, host = config.host) {
        nodeNoteModule(config)
    }.start(wait = true)
}

private fun runAdminCommand(command: List<String>, config: ServerConfig): Int = try {
    Database(config.databaseFile).use { db ->
        val accounts = Accounts(db)
        when (command.first()) {
            "users" -> accounts.users().forEach(::println)
            "adduser" -> {
                require(command.size == 3) { "Usage: adduser <name> <password>" }
                accounts.addUser(command[1], command[2])
                println("Added ${command[1]}")
            }
            "passwd" -> {
                require(command.size == 3) { "Usage: passwd <name> <new password>" }
                accounts.resetPassword(command[1], command[2])
                println("Password reset for ${command[1]} (their other sessions were signed out)")
            }
            else -> throw IllegalArgumentException("Unknown command: ${command.first()} (users | adduser | passwd)")
        }
    }
    0
} catch (e: IllegalArgumentException) {
    System.err.println(e.message)
    2
}

/** Error body for every non-2xx API response. */
@Serializable
data class ApiError(val error: String)

/**
 * Wires the server together. Separate from [main] so tests can run the exact same
 * module in `testApplication` against a temp data dir and vault.
 */
fun Application.nodeNoteModule(config: ServerConfig, vaultDebounceMillis: Long = 1500) {
    val database = Database(config.databaseFile)
    config.rememberVault()
    val backups = Backups(database, config.dataDir.resolve("backups")).also { it.start() }
    val store = BoardStore(database)
    val accounts = Accounts(database)
    val vault = VaultWriter(config.vaultDir, store, vaultDebounceMillis)
    store.addListener(vault::schedule)
    vault.rebuildAllAsync()

    monitor.subscribe(ApplicationStopping) {
        runBlocking { vault.flush() }
    }
    monitor.subscribe(ApplicationStopped) {
        vault.close()
        backups.close()
        database.close()
    }

    install(ContentNegotiation) {
        json(ApiJson)
    }
    // Behind `tailscale serve` the browser's Origin is https://<machine>.ts.net while this
    // server sees plain http on 127.0.0.1. The forwarded headers restore the public scheme
    // and host so CORS recognises the web app's own requests as same-origin.
    install(XForwardedHeaders)
    install(CORS) {
        // Only the webpack dev server (and other local tools) get cross-origin access.
        allowOrigins { LocalOrigin.matches(it) }
        HttpMethod.DefaultMethods.forEach { allowMethod(it) }
        allowHeaders { true }
        allowNonSimpleContentTypes = true
    }
    install(Compression) {
        gzip {
            matchContentType(
                ContentType.Application.Json,
                ContentType.Application.JavaScript,
                ContentType.Application.Wasm,
                ContentType.Text.Any,
            )
            minimumSize(1024)
        }
    }
    install(ConditionalHeaders)
    install(CachingHeaders)
    install(CallLogging) {
        level = Level.INFO
        // Long-polls would drown out everything else, and static files are noise.
        filter { call -> call.request.path().startsWith("/api") && !call.request.path().endsWith("/sync") }
        format { call -> "${call.response.status()?.value} ${call.request.httpMethod.value} ${call.request.path()}" }
    }
    install(StatusPages) {
        exception<UnauthorizedException> { call, e ->
            call.respond(HttpStatusCode.Unauthorized, ApiError(e.message ?: "Sign in required"))
        }
        exception<ForbiddenException> { call, e ->
            call.respond(HttpStatusCode.Forbidden, ApiError(e.message ?: "Not allowed"))
        }
        exception<TooManyAttemptsException> { call, e ->
            call.respond(HttpStatusCode.TooManyRequests, ApiError(e.message ?: "Too many attempts"))
        }
        exception<NotFoundException> { call, e ->
            call.respond(HttpStatusCode.NotFound, ApiError(e.message ?: "Not found"))
        }
        exception<BadRequestException> { call, e ->
            // Deserialization failures arrive wrapped; the cause says what was wrong.
            val detail = generateSequence<Throwable>(e) { it.cause }.last().message
            call.respond(HttpStatusCode.BadRequest, ApiError(listOfNotNull(e.message, detail).distinct().joinToString(": ")))
        }
        exception<IllegalArgumentException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ApiError(e.message ?: "Bad request"))
        }
        exception<SerializationException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ApiError(e.message ?: "Malformed JSON"))
        }
        exception<Throwable> { call, e ->
            // Keep Ktor's own mapping for things like 415 Unsupported Media Type.
            val status = defaultExceptionStatusCode(e) ?: HttpStatusCode.InternalServerError
            if (status == HttpStatusCode.InternalServerError) {
                log.error("Unhandled error on ${call.request.httpMethod.value} ${call.request.path()}", e)
            }
            call.respond(status, ApiError(e.message ?: e::class.simpleName ?: status.description))
        }
    }

    routing {
        apiRoutes(store, vault, config, accounts)
        serveWebApp(config)
    }
}

/** http://localhost:<any port> and http://127.0.0.1:<any port>. */
private val LocalOrigin = Regex("""http://(localhost|127\.0\.0\.1)(:\d+)?""")

/**
 * The browser app: from [ServerConfig.webDir] when set (a dev build or `--web`), else
 * from the copy bundled into the jar by `-PwithWeb`. Ktor already maps `.wasm` to
 * application/wasm (required for streaming compilation); set it explicitly anyway,
 * along with `.mjs`, which Ktor doesn't know.
 *
 * The bundled copy comes with brotli and gzip versions of each big file (see
 * tools/prepare-web.mjs), sent as they are to browsers that accept them: the first
 * visit on a phone downloads about a quarter less, and nothing is compressed per request.
 */
private fun io.ktor.server.routing.Route.serveWebApp(config: ServerConfig) {
    // Always from the original's name: "index.html.br" must still be text/html.
    fun typeOf(fileName: String): ContentType {
        val name = uncompressed(fileName)
        return when (name.substringAfterLast('.', "").lowercase()) {
            "wasm" -> ContentType.Application.Wasm
            "mjs" -> ContentType.Application.JavaScript
            else -> ContentType.defaultForFilePath(name)
        }
    }
    val dir = config.webDir
    if (dir != null) {
        staticFiles("/", dir.toFile()) {
            preCompressed(CompressedFileType.BROTLI, CompressedFileType.GZIP)
            contentType { typeOf(it.name) }
            cacheControl { cachingFor(it.path) }
        }
    } else if (bundledWebApp()) {
        staticResources("/", "web") {
            preCompressed(CompressedFileType.BROTLI, CompressedFileType.GZIP)
            contentType { typeOf(it.path) }
            cacheControl { cachingFor(it.path) }
        }
    }
}

/** "x.wasm.br" -> "x.wasm": a precompressed copy has its original's type and caching. */
private fun uncompressed(path: String): String = path.removeSuffix(".br").removeSuffix(".gz")

/**
 * Webpack names the .wasm files by content hash, so a phone can keep them for a
 * year (and the browser also keeps its compiled code) — repeat visits skip ~4 MB.
 * index.html and app.js keep fixed names, so they're revalidated every time
 * (cheap 304s via ConditionalHeaders) and an update shows up on the next load.
 */
private fun cachingFor(path: String): List<CacheControl> =
    if (Regex("""[0-9a-f]{16,}\.wasm$""").containsMatchIn(uncompressed(path))) {
        listOf(CacheControl.MaxAge(maxAgeSeconds = 365 * 24 * 3600, visibility = CacheControl.Visibility.Public))
    } else {
        listOf(CacheControl.NoCache(null))
    }

private fun bundledWebApp(): Boolean = ServerConfig::class.java.getResource("/web/index.html") != null

private fun describeWeb(config: ServerConfig): String {
    val dir = config.webDir
    return when {
        dir != null && Files.isRegularFile(dir.resolve("index.html")) -> dir.toString()
        dir != null -> "$dir (WARNING: no index.html there)"
        bundledWebApp() -> "bundled in the jar"
        else -> "none — build it with `gradlew :shared:wasmJsBrowserDistribution` (API only for now)"
    }
}
