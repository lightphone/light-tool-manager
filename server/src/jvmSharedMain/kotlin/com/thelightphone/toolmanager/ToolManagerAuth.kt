package com.thelightphone.toolmanager

import java.io.File
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalStdlibApi::class)
fun generateApiKey(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return bytes.toHexString()
}

interface KeyCipher {
    fun encrypt(plaintext: String): ByteArray
    fun decrypt(ciphertext: ByteArray): String
}

interface ToolManagerAuth {
    val primaryKey: String

    // True if `key` is currently valid
    fun validateKey(key: String): Boolean

    // for display
    fun currentCode(): String

    // Checks `code` against the current time step. mints and returns new key when valid code passed
    fun mintKey(code: String): String?

    // True if `signature` is a valid HMAC (see RequestSigning.kt) of (method, path, timestampMillis)
    // under any currently-valid key, and timestampMillis is within the replay-tolerance window.
    suspend fun verifySignature(method: String, path: String, timestampMillis: Long, signature: String): Boolean
}

private const val SELF_MINT_PREFIX = "_self_minted_"

// RFC 6238 TOTP (HmacSHA1, 6 digits) over a secret generated fresh per process.
// keys minted using the TOTP flow are persisted as encrypted text files in keyDirectory
// they are valid for [keyValidityDuration]
class TotpToolManagerAuth(
    override val primaryKey: String = generateApiKey(),
    private val step: Duration = 30.seconds,
    private val maxAttemptsPerWindow: Int = 5,
    private val attemptWindow: Duration = 1.minutes,
    private val clock: Clock = Clock.System,
    private val keyDirectory: File? = null,
    private val cipher: KeyCipher? = null,
    private val keyValidityDuration: Duration? = 2.days,
) : ToolManagerAuth {
    private val secret: ByteArray = ByteArray(20).also { SecureRandom().nextBytes(it) }
    private val mintedKeys = ConcurrentHashMap.newKeySet<String>()
    private val cacheLock = Any()

    // for nonces
    private val seenSignatures = ConcurrentHashMap<String, Long>()

    private val lockoutLock = Any()
    private var failureCount = 0
    private var windowStart: Instant = clock.now()

    init {
        invalidateKeyCache()
    }

    override fun validateKey(key: String): Boolean =
        key == primaryKey || mintedKeys.contains(key)

    override suspend fun verifySignature(
        method: String,
        path: String,
        timestampMillis: Long,
        signature: String
    ): Boolean {
        val now = clock.now().toEpochMilliseconds()
        if (kotlin.math.abs(now - timestampMillis) > SignatureToleranceMillis) return false

        val candidateKeys = listOf(primaryKey) + mintedKeys
        val validSignature = candidateKeys.any { key ->
            constantTimeEquals(signRequest(key, method, path, timestampMillis), signature)
        }
        if (!validSignature) return false

        seenSignatures.entries.removeIf { (_, seenAt) -> now - seenAt > SignatureToleranceMillis }
        return seenSignatures.putIfAbsent(signature, timestampMillis) == null
    }

    override fun currentCode(): String = hotp(secret, currentStep())

    override fun mintKey(code: String): String? {
        synchronized(lockoutLock) {
            val now = clock.now()
            if (now - windowStart > attemptWindow) {
                windowStart = now
                failureCount = 0
            }
            if (failureCount >= maxAttemptsPerWindow) {
                return null
            }

            val step = currentStep()
            // ±1 step tolerance, standard
            val validCodes = (-1..1).map { hotp(secret, step + it) }
            if (code !in validCodes) {
                failureCount++
                return null
            }
            failureCount = 0
        }

        val newKey = generateApiKey()
        mintedKeys.add(newKey)
        persistKey(newKey)
        return newKey
    }

    fun invalidateKeyCache() {
        val dir = keyDirectory ?: return
        val keyCipher = cipher ?: return
        synchronized(cacheLock) {
            val now = clock.now()
            val decrypted = dir.listFiles()
                ?.filter { it.isFile }
                ?.mapNotNull { file ->
                    // if the file was created here, auto-expire it after given duration
                    if (file.name.startsWith(SELF_MINT_PREFIX) && keyValidityDuration != null) {
                        val expired = now - Instant.fromEpochMilliseconds(file.lastModified()) > keyValidityDuration
                        if (expired) {
                            file.delete()
                            return@mapNotNull null
                        }
                    }
                    runCatching { keyCipher.decrypt(file.readBytes()) }.getOrNull()
                }
                .orEmpty()
            mintedKeys.clear()
            mintedKeys.addAll(decrypted)
        }
    }

    private fun persistKey(key: String) {
        val dir = keyDirectory ?: return
        val keyCipher = cipher ?: return
        dir.mkdirs()
        val fileName = generateApiKey()
        runCatching {
            File(dir, "$SELF_MINT_PREFIX$fileName.key").writeBytes(keyCipher.encrypt(key))
        }
    }

    private fun currentStep(): Long = clock.now().toEpochMilliseconds() / step.inWholeMilliseconds

    private fun hotp(secret: ByteArray, counter: Long): String {
        val counterBytes = ByteArray(8)
        var value = counter
        for (i in 7 downTo 0) {
            counterBytes[i] = (value and 0xFF).toByte()
            value = value shr 8
        }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))
        val hash = mac.doFinal(counterBytes)
        val offset = hash[hash.size - 1].toInt() and 0xF
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        return (binary % 1_000_000).toString().padStart(6, '0')
    }
}
