package com.nodenote

import com.nodenote.server.ServerConfig
import com.nodenote.server.nodeNoteModule
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI

/**
 * The NodeNote server built into the desktop app. When the app points at this computer (the
 * default http://localhost:8870) and nothing is running there yet, it starts the same server a
 * standalone install runs: boards in ~/.nodenote-server, the Obsidian mirror in
 * ~/Documents/NodeNote Vault (or wherever this data was mirrored before). So an installed app
 * works on its own computer with its own boards, and other devices can use it too (see the
 * README). A server already running here, like a standalone one started at logon, is simply
 * used instead. It stops with the app.
 */
object LocalServer {
    private var running: EmbeddedServer<*, *>? = null

    /** Starts the built-in server for [serverUrl] when that is this computer and its port is free. */
    fun startIfNeeded(serverUrl: String) {
        if (running != null) return
        val uri = runCatching { URI(serverUrl.trim()) }.getOrNull() ?: return
        if (uri.scheme != "http" || uri.host?.lowercase() !in setOf("localhost", "127.0.0.1")) return
        val port = if (uri.port > 0) uri.port else 80
        if (!portFree(port)) return
        try {
            val config = ServerConfig.load(arrayOf("--port", port.toString(), "--host", "127.0.0.1"))
            running = embeddedServer(Netty, port = config.port, host = config.host) { nodeNoteModule(config) }
                .start(wait = false)
            // The app quits with exitProcess: stop first so the vault gets its last writes.
            Runtime.getRuntime().addShutdownHook(Thread { stop() })
        } catch (e: Exception) {
            System.err.println("NodeNote: couldn't start the built-in server: ${e.message}")
        }
    }

    fun stop() {
        running?.stop(gracePeriodMillis = 500, timeoutMillis = 3_000)
        running = null
    }

    private fun portFree(port: Int): Boolean = try {
        ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).use { true }
    } catch (e: Exception) {
        false
    }
}
