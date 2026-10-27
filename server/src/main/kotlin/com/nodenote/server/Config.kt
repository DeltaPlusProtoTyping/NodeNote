package com.nodenote.server

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Where the server listens and where it keeps things.
 *
 * Every setting can come from a command-line flag (`--port 8870` or `--port=8870`),
 * an environment variable, or a default — in that order — so the same install can be
 * run by hand for testing and from a service wrapper that only sets env vars.
 *
 * The default host is 127.0.0.1 on purpose: the server is reached through
 * `tailscale serve` (which proxies https → 127.0.0.1:8870), so nothing on the LAN
 * can talk to it directly. Pass `--host 0.0.0.0` to expose it anyway.
 */
data class ServerConfig(
    val port: Int = DEFAULT_PORT,
    val host: String = DEFAULT_HOST,
    /** Holds `nodenote.db` (SQLite, the source of truth). */
    val dataDir: Path,
    /** Root of the generated Obsidian mirror; may be a subfolder of a bigger vault. */
    val vaultDir: Path,
    /** The browser build of the app (index.html + wasm/js), or null to use the bundled copy. */
    val webDir: Path? = null,
) {
    val databaseFile: Path get() = dataDir.resolve("nodenote.db")

    /** The group password's hash, when group sign-in is on (see [Accounts]). */
    val groupPasswordFile: Path get() = dataDir.resolve("group-password.txt")

    /** Notes the vault folder next to the data, for servers later started without `--vault`. */
    fun rememberVault() {
        runCatching {
            Files.createDirectories(dataDir)
            Files.writeString(dataDir.resolve(VAULT_NOTE), vaultDir.toString())
        }
    }

    companion object {
        const val DEFAULT_PORT = 8870
        const val DEFAULT_HOST = "127.0.0.1"

        /** Where `gradlew :shared:wasmJsBrowserDistribution` puts the browser app, relative to the repo. */
        const val DEV_WEB_DIR = "shared/build/dist/wasmJs/productionExecutable"

        val USAGE = """
            |Usage: nodenote-server [options]
            |  --port <n>     NODENOTE_PORT   HTTP port (default $DEFAULT_PORT)
            |  --host <addr>  NODENOTE_HOST   bind address (default $DEFAULT_HOST; 0.0.0.0 = all interfaces)
            |  --data <dir>   NODENOTE_DATA   database directory (default ~/.nodenote-server)
            |  --vault <dir>  NODENOTE_VAULT  Obsidian mirror directory (default ~/Documents/NodeNote Vault)
            |  --web <dir>    NODENOTE_WEB    browser app to serve (default: $DEV_WEB_DIR if built, else bundled)
        """.trimMargin()

        private val FLAGS = setOf("port", "host", "data", "vault", "web")

        /** Resolves the config from [args] over [env] over defaults. Throws [IllegalArgumentException] on bad input. */
        fun load(args: Array<String>, env: Map<String, String> = System.getenv()): ServerConfig {
            val flags = parseArgs(args)
            fun setting(flag: String, envVar: String): String? =
                flags[flag] ?: env[envVar]?.takeIf { it.isNotBlank() }

            val home = Paths.get(System.getProperty("user.home"))
            val port = setting("port", "NODENOTE_PORT")?.let {
                it.toIntOrNull()?.takeIf { p -> p in 0..65535 } ?: throw IllegalArgumentException("Invalid port: $it")
            } ?: DEFAULT_PORT
            val dataDir = setting("data", "NODENOTE_DATA")?.let(::expandPath) ?: home.resolve(".nodenote-server")
            return ServerConfig(
                port = port,
                host = setting("host", "NODENOTE_HOST") ?: DEFAULT_HOST,
                dataDir = dataDir,
                // Without --vault: the folder this data was last mirrored to, so a server started
                // another way (the desktop app's own) keeps writing to the same place.
                vaultDir = setting("vault", "NODENOTE_VAULT")?.let(::expandPath)
                    ?: rememberedVault(dataDir)
                    ?: home.resolve("Documents").resolve("NodeNote Vault"),
                webDir = setting("web", "NODENOTE_WEB")?.let(::expandPath) ?: defaultWebDir(),
            )
        }

        /** Where [ServerConfig.rememberVault] noted the vault for this data directory. */
        private fun rememberedVault(dataDir: Path): Path? =
            runCatching { Files.readString(dataDir.resolve(VAULT_NOTE)).trim() }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { Paths.get(it) }

        private const val VAULT_NOTE = "vault-path.txt"

        /** The dev build of the browser app, when the server is run from a repo checkout that has one. */
        private fun defaultWebDir(): Path? =
            Paths.get(DEV_WEB_DIR).toAbsolutePath().normalize().takeIf { Files.isRegularFile(it.resolve("index.html")) }

        private fun parseArgs(args: Array<String>): Map<String, String> {
            val result = HashMap<String, String>()
            var i = 0
            while (i < args.size) {
                val arg = args[i]
                if (arg == "--help" || arg == "-h") throw HelpRequested()
                require(arg.startsWith("--")) { "Unexpected argument: $arg" }
                val name = arg.removePrefix("--").substringBefore('=')
                require(name in FLAGS) { "Unknown option: --$name" }
                val value = if ('=' in arg) {
                    arg.substringAfter('=')
                } else {
                    i++
                    require(i < args.size) { "Missing value for --$name" }
                    args[i]
                }
                result[name] = value
                i++
            }
            return result
        }

        /** Absolute path, with a leading `~` meaning the user's home (shells on Windows don't expand it). */
        private fun expandPath(raw: String): Path {
            val home = System.getProperty("user.home")
            val expanded = when {
                raw == "~" -> home
                raw.startsWith("~/") || raw.startsWith("~\\") -> home + raw.substring(1)
                else -> raw
            }
            return Paths.get(expanded).toAbsolutePath().normalize()
        }
    }
}

/** `--help` was passed: print usage and exit cleanly rather than failing. */
class HelpRequested : RuntimeException()
