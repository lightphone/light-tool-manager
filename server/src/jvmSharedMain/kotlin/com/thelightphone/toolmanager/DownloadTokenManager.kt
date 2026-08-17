package com.thelightphone.toolmanager

import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/**
 * Manages download tokens with automatic cleanup of expired tokens
 */
class DownloadTokenManager {
    private val sessions = ConcurrentHashMap<String, DownloadSession>()
    private val tokenExpiryDuration = 1.hours // Tokens expire after 1 hour

    /**
     * Creates a new download token for the given image IDs
     */
    fun createToken(paths: List<String>): DownloadTokenResponse {
        cleanupExpiredTokens()

        val token = UUID.randomUUID().toString()
        val now = Clock.System.now()
        val expiresAt = now + tokenExpiryDuration

        val session = DownloadSession(
            paths = paths,
            createdAt = now.epochSeconds,
            expiresAt = expiresAt.epochSeconds
        )

        sessions[token] = session

        return DownloadTokenResponse(
            token = token,
            expiresAt = expiresAt.toString()
        )
    }

    /**
     * Retrieves the download session for a given token
     */
    fun getSession(token: String): DownloadSession? {
        cleanupExpiredTokens()
        return sessions[token]
    }

    /**
     * Consumes (removes) a token after use to prevent reuse
     */
    fun consumeToken(token: String): DownloadSession? {
        cleanupExpiredTokens()
        return sessions.remove(token)
    }

    /**
     * Removes expired tokens from memory
     */
    private fun cleanupExpiredTokens() {
        val now = Clock.System.now().epochSeconds
        sessions.entries.removeIf { it.value.expiresAt < now }
    }

    /**
     * Gets current number of active sessions (for monitoring)
     */
    fun getActiveSessionCount(): Int {
        cleanupExpiredTokens()
        return sessions.size
    }
}