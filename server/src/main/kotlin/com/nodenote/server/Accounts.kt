package com.nodenote.server

import com.nodenote.api.LoginResponse
import com.nodenote.model.nowMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** 401 — no or invalid session. */
class UnauthorizedException(message: String = "Sign in required") : RuntimeException(message)

/** 403 — signed in, but not allowed to do that. */
class ForbiddenException(message: String) : RuntimeException(message)

/** 429 — too many failed sign-ins. */
class TooManyAttemptsException(message: String) : RuntimeException(message)

/**
 * Accounts and sign-in sessions. The server is reachable from the public internet
 * (Tailscale Funnel), so:
 *  - passwords are stored as salted PBKDF2-SHA256 hashes, never in the clear;
 *  - session tokens are random 256-bit values, and only their SHA-256 is stored;
 *  - failed sign-ins are throttled per username and overall, and each failure is slowed down.
 *
 * Sessions last until signed out or unused for [SESSION_IDLE_DAYS] days.
 */
class Accounts(
    private val db: Database,
    private val clock: () -> Long = ::nowMillis,
    private val failureDelayMillis: Long = 600,
) {
    private val random = SecureRandom()
    private val failures = ConcurrentHashMap<String, MutableList<Long>>()

    // ---- sign-in ----

    suspend fun login(username: String, password: String): LoginResponse {
        val key = username.trim().lowercase()
        checkThrottle(key)
        val row = io { db.user(username.trim()) }
        if (row == null || !verifyPassword(password, row.second)) {
            recordFailure(key)
            delay(failureDelayMillis)
            throw UnauthorizedException("Wrong username or password")
        }
        failures.remove(key)
        val token = newToken()
        io { db.insertSession(hashToken(token), row.first, clock()) }
        return LoginResponse(token, row.first)
    }

    /** The username for a bearer token, or null if it isn't a live session. */
    suspend fun authenticate(token: String): String? {
        if (token.isBlank()) return null
        val hash = hashToken(token)
        val (username, lastSeen) = io { db.session(hash) } ?: return null
        val now = clock()
        if (now - lastSeen > SESSION_IDLE_DAYS * DAY_MS) {
            io { db.deleteSession(hash) }
            return null
        }
        // Sliding expiry, written at most hourly so long-polls don't hammer the disk.
        if (now - lastSeen > HOUR_MS) io { db.touchSession(hash, now) }
        return username
    }

    suspend fun logout(token: String) = io { db.deleteSession(hashToken(token)) }

    // ---- account changes (the signed-in user, re-confirming their password) ----

    suspend fun changePassword(username: String, token: String, current: String, new: String) {
        requireCurrentPassword(username, current)
        validatePassword(new)
        io {
            db.setPasswordHash(username, hashPassword(new), clock())
            db.deleteOtherSessions(username, keepTokenHash = hashToken(token))
        }
    }

    /** Renames the account (and its messages' author); returns the canonical new name. */
    suspend fun changeUsername(username: String, password: String, newUsername: String): String {
        requireCurrentPassword(username, password)
        val name = validateUsername(newUsername)
        val taken = io { db.user(name) }
        require(taken == null || taken.first.equals(username, ignoreCase = true)) { "The username \"$name\" is already taken" }
        io { db.renameUser(username, name, clock()) }
        return name
    }

    // ---- first run ----

    private val setupLock = Mutex()

    /** True while the server has no accounts at all (a fresh install). */
    suspend fun needsSetup(): Boolean = io { db.users().isEmpty() }

    /**
     * A fresh install's first account, made from the sign-in screen and signed straight in.
     * Refused once any account exists, so it can never be used to add someone later.
     */
    suspend fun setUp(username: String, password: String): LoginResponse = setupLock.withLock {
        if (!needsSetup()) throw ForbiddenException("This server already has accounts: sign in instead")
        io { addUser(username, password) }
        login(username, password)
    }

    // ---- admin (command line) ----

    fun addUser(username: String, password: String) {
        val name = validateUsername(username)
        validatePassword(password)
        require(db.user(name) == null) { "User \"$name\" already exists" }
        db.insertUser(name, hashPassword(password), clock())
    }

    fun resetPassword(username: String, password: String) {
        validatePassword(password)
        val row = db.user(username) ?: throw IllegalArgumentException("No user \"$username\"")
        db.setPasswordHash(row.first, hashPassword(password), clock())
        db.deleteOtherSessions(row.first, keepTokenHash = null)
    }

    fun users(): List<String> = db.users()

    // ---- helpers ----

    private suspend fun requireCurrentPassword(username: String, password: String) {
        val row = io { db.user(username) } ?: throw UnauthorizedException()
        if (!verifyPassword(password, row.second)) {
            delay(failureDelayMillis)
            throw ForbiddenException("Current password is wrong")
        }
    }

    private fun checkThrottle(key: String) {
        val now = clock()
        val cutoff = now - WINDOW_MS
        val mine = failures[key]?.let { list -> synchronized(list) { list.removeAll { it < cutoff }; list.size } } ?: 0
        val all = failures.values.sumOf { list -> synchronized(list) { list.count { it >= cutoff } } }
        if (mine >= MAX_FAILURES_PER_USER || all >= MAX_FAILURES_TOTAL) {
            throw TooManyAttemptsException("Too many failed sign-ins — try again in 15 minutes")
        }
    }

    private fun recordFailure(key: String) {
        val list = failures.getOrPut(key) { mutableListOf() }
        synchronized(list) { list.add(clock()) }
    }

    private fun newToken(): String = ByteArray(32).also(random::nextBytes).toHex()

    private fun hashPassword(password: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        val hash = pbkdf2(password, salt, ITERATIONS)
        val b64 = Base64.getEncoder()
        return "pbkdf2-sha256\$$ITERATIONS\$${b64.encodeToString(salt)}\$${b64.encodeToString(hash)}"
    }

    private fun verifyPassword(password: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2-sha256") return false
        val b64 = Base64.getDecoder()
        val expected = b64.decode(parts[3])
        val actual = pbkdf2(password, b64.decode(parts[2]), parts[1].toInt())
        return MessageDigest.isEqual(expected, actual)
    }

    private fun pbkdf2(password: String, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, 256))
            .encoded

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        const val MIN_PASSWORD_LENGTH = 8
        private const val ITERATIONS = 210_000
        private const val SESSION_IDLE_DAYS = 60L
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val HOUR_MS = 60L * 60 * 1000
        private const val WINDOW_MS = 15L * 60 * 1000
        private const val MAX_FAILURES_PER_USER = 5
        private const val MAX_FAILURES_TOTAL = 30
        private val UsernamePattern = Regex("""[A-Za-z0-9][A-Za-z0-9 ._-]{1,31}""")

        fun hashToken(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).toHex()

        fun validateUsername(raw: String): String {
            val name = raw.trim()
            require(UsernamePattern.matches(name)) {
                "Usernames are 2–32 characters: letters, digits, space, dot, dash or underscore"
            }
            return name
        }

        fun validatePassword(password: String) {
            require(password.length >= MIN_PASSWORD_LENGTH) { "Passwords need at least $MIN_PASSWORD_LENGTH characters" }
        }

        private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}
