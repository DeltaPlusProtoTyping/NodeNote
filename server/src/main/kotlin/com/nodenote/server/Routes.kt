package com.nodenote.server

import com.nodenote.api.AccountInfo
import com.nodenote.api.BoardMetaUpdate
import com.nodenote.api.ChangePasswordRequest
import com.nodenote.api.ChangeUsernameRequest
import com.nodenote.api.LoginRequest
import com.nodenote.api.LoginResponse
import com.nodenote.api.CreateBoardRequest
import com.nodenote.api.MessageUpdate
import com.nodenote.api.PatchRequest
import com.nodenote.api.ServerInfo
import com.nodenote.model.Message
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.request.path
import io.ktor.util.AttributeKey
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** Server version reported by `/api/info`; 2.x = the networked, multi-user NodeNote. */
const val SERVER_VERSION = "2.0.0"

/**
 * The JSON API. Shapes and semantics are defined in core's `Api.kt`; this file is
 * only the HTTP plumbing — parameter parsing, 404s — around [BoardStore].
 */
fun Route.apiRoutes(store: BoardStore, vault: VaultWriter, config: ServerConfig, accounts: Accounts) {
    route("/api") {
        install(RequireSignIn) { this.accounts = accounts }

        route("/auth") {
            post("/login") {
                val request = call.receive<LoginRequest>()
                call.respond(accounts.login(request.username, request.password))
            }
            post("/logout") {
                accounts.logout(call.token())
                call.respond(HttpStatusCode.NoContent)
            }
            get("/me") {
                call.respond(AccountInfo(call.user()))
            }
            post("/password") {
                val request = call.receive<ChangePasswordRequest>()
                accounts.changePassword(call.user(), call.token(), request.currentPassword, request.newPassword)
                call.respond(HttpStatusCode.NoContent)
            }
            post("/username") {
                val request = call.receive<ChangeUsernameRequest>()
                val old = call.user()
                val new = accounts.changeUsername(old, request.password, request.newUsername)
                if (new != old) store.renameAuthor(old, new)
                call.respond(LoginResponse(call.token(), new))
            }
        }

        get("/info") {
            // Set by `tailscale serve` for requests from tailnet users; absent when hit directly.
            val identity = call.request.headers["Tailscale-User-Name"]?.takeIf { it.isNotBlank() }
                ?: call.request.headers["Tailscale-User-Login"]?.takeIf { it.isNotBlank() }
            call.respond(ServerInfo("NodeNote", SERVER_VERSION, config.vaultDir.toString(), identity, webBuild(config)))
        }

        route("/boards") {
            get {
                val archived = call.request.queryParameters["archived"]?.toBooleanStrictOrNull() ?: false
                call.respond(store.list(includeArchived = archived))
            }
            post {
                call.respond(store.create(call.receive<CreateBoardRequest>()))
            }

            route("/{id}") {
                get {
                    call.respond(store.snapshot(call.boardId()) ?: boardNotFound(call.boardId()))
                }
                patch("/meta") {
                    val update = call.receive<BoardMetaUpdate>()
                    call.respond(store.updateMeta(call.boardId(), update) ?: boardNotFound(call.boardId()))
                }
                post("/patch") {
                    val request = call.receive<PatchRequest>()
                    call.respond(store.patch(call.boardId(), request) ?: boardNotFound(call.boardId()))
                }
                get("/messages") {
                    val since = call.longParam("since", 0)
                    call.respond(store.messagesSince(call.boardId(), since) ?: boardNotFound(call.boardId()))
                }
                post("/messages") {
                    val message = call.receive<Message>()
                    call.respond(store.postMessage(call.boardId(), message, author = call.user()) ?: boardNotFound(call.boardId()))
                }
                get("/sync") {
                    val rev = call.longParam("rev", 0)
                    val seq = call.longParam("seq", 0)
                    val waitSeconds = call.longParam("wait", DEFAULT_WAIT_SECONDS).coerceIn(0, MAX_WAIT_SECONDS)
                    val response = store.sync(call.boardId(), rev, seq, waitSeconds * 1000)
                    call.respond(response ?: boardNotFound(call.boardId()))
                }
            }
        }

        patch("/messages/{id}") {
            val id = call.parameters["id"].orEmpty()
            val update = call.receive<MessageUpdate>()
            call.respond(store.updateMessage(id, update, actor = call.user()) ?: throw NotFoundException("Unknown message: $id"))
        }

        post("/vault/rebuild") {
            vault.rebuildAll()
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/** Short hash of the served app.js (it embeds the content-hashed wasm names), computed once. */
private var cachedWebBuild: String? = null

private fun webBuild(config: ServerConfig): String? {
    cachedWebBuild?.let { return it }
    val bytes = config.webDir?.resolve("app.js")?.toFile()?.takeIf { it.isFile }?.readBytes()
        ?: ServerConfig::class.java.getResourceAsStream("/web/app.js")?.use { it.readBytes() }
        ?: return null
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.take(6).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }.also { cachedWebBuild = it }
}

private const val DEFAULT_WAIT_SECONDS = 25L
/** Stays under typical proxy idle timeouts (tailscale serve, browsers). */
private const val MAX_WAIT_SECONDS = 30L

private fun ApplicationCall.boardId(): String = parameters["id"].orEmpty()

private val UserKey = AttributeKey<String>("nodenote.user")
private val TokenKey = AttributeKey<String>("nodenote.token")

/** The signed-in username (set by [RequireSignIn]). */
private fun ApplicationCall.user(): String = attributes.getOrNull(UserKey) ?: throw UnauthorizedException()

private fun ApplicationCall.token(): String = attributes.getOrNull(TokenKey) ?: throw UnauthorizedException()

class RequireSignInConfig {
    lateinit var accounts: Accounts
}

/** Paths under /api that work without signing in. */
private val OpenPaths = setOf("/api/info", "/api/auth/login")

/**
 * Every /api call except [OpenPaths] needs `Authorization: Bearer <token>` from
 * /api/auth/login. Static files (the web app itself) stay public so the sign-in
 * page can load.
 */
val RequireSignIn = createRouteScopedPlugin("RequireSignIn", ::RequireSignInConfig) {
    val accounts = pluginConfig.accounts
    onCall { call ->
        if (call.request.path() in OpenPaths) return@onCall
        val token = call.request.headers[HttpHeaders.Authorization]
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(7)?.trim()
            ?: throw UnauthorizedException()
        val user = accounts.authenticate(token) ?: throw UnauthorizedException("Session expired — sign in again")
        call.attributes.put(UserKey, user)
        call.attributes.put(TokenKey, token)
    }
}

private fun boardNotFound(id: String): Nothing = throw NotFoundException("Unknown board: $id")

private fun ApplicationCall.longParam(name: String, default: Long): Long {
    val raw = request.queryParameters[name] ?: return default
    return raw.toLongOrNull() ?: throw BadRequestException("Query parameter '$name' must be a number, got '$raw'")
}
