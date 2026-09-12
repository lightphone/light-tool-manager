package com.thelightphone.toolmanager

import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

// Anti-CSRF token (state string) for /api/job-callback.
// State Format: "<issuedAtMillis>|<url-encoded origin>|<url-encoded path>|<url-encoded jobId>|<hex
// HMAC-SHA256 signature, or the literal "unsigned" if minted with no auth configured>" .
private val JobCallbackStateTolerance: Duration = 1.hours

data class JobCallbackTarget(val path: String, val origin: String, val jobId: String)

private const val UnsignedMarker = "unsigned"

// auth is nullable so callers don't need to branch themselves: when the server is running with no
// auth configured (local/dev use, matching every other endpoint's behavior in that mode), this
// mints/verifies the same shape of token but skips the cryptographic step entirely.
fun mintJobCallbackState(
    auth: ToolManagerAuth?,
    path: String,
    origin: String,
    jobId: String,
    timeNow: Instant = Clock.System.now()
): String {
    val issuedAtMillis = timeNow.toEpochMilliseconds()
    val signature = auth?.sign(jobCallbackStateCanonical(path, origin, jobId, issuedAtMillis)) ?: UnsignedMarker
    return listOf(
        issuedAtMillis.toString(),
        URLEncoder.encode(origin, "UTF-8"),
        URLEncoder.encode(path, "UTF-8"),
        URLEncoder.encode(jobId, "UTF-8"),
        signature
    ).joinToString("|")
}

// Returns the verified, decoded (path, origin, jobId) on success, or null if state is malformed,
// expired, or (when auth is non-null) doesn't check out under any currently-valid key.
fun verifyJobCallbackState(
    auth: ToolManagerAuth?,
    state: String,
    timeNow: Instant = Clock.System.now(),
    tolerance: Duration = JobCallbackStateTolerance
): JobCallbackTarget? {
    val parts = state.split('|', limit = 5)
    if (parts.size != 5) return null
    val issuedAtMillis = parts[0].toLongOrNull() ?: return null
    val origin = parts[1].decodeOrNull() ?: return null
    val path = parts[2].decodeOrNull() ?: return null
    val jobId = parts[3].decodeOrNull() ?: return null
    val signature = parts[4]
    if (kotlin.math.abs(timeNow.toEpochMilliseconds() - issuedAtMillis) > tolerance.inWholeMilliseconds) {
        return null
    }
    val target = JobCallbackTarget(path, origin, jobId)
    if (auth == null) return target
    val canonical = jobCallbackStateCanonical(path, origin, jobId, issuedAtMillis)
    return target.takeIf { auth.verify(canonical, signature) }
}

private fun String.decodeOrNull(): String? = runCatching { URLDecoder.decode(this, "UTF-8") }.getOrNull()

private fun jobCallbackStateCanonical(path: String, origin: String, jobId: String, issuedAtMillis: Long) =
    "$path\n$origin\n$jobId\n$issuedAtMillis"
