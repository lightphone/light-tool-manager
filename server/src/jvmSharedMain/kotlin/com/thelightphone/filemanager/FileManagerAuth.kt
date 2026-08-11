package com.thelightphone.filemanager

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

fun generateApiKey(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

// Encrypts/decrypts the contents of one minted-key file. Kept platform-agnostic (this file is
// jvmSharedMain, but AndroidKeyStore isn't available on plain desktop JVM) so the actual crypto
// lives in a platform-specific implementation — e.g. an androidMain one backed by AndroidKeyStore
// — and gets injected into TotpFileManagerAuth rather than baked into it.
interface KeyCipher {
    fun encrypt(plaintext: String): ByteArray
    fun decrypt(ciphertext: ByteArray): String
}

// Replaces a single static apiKey as what gets passed to Application.module: still supports the
// original "one key generated at boot, shared via link/QR" flow (primaryKey), but also lets a
// person standing at the server hand out a rotating 6-digit code instead — anyone who enters the
// currently-valid code gets a real persistent key minted for them via mintKey, from then on
// indistinguishable from the primary key as far as validateKey is concerned.
interface FileManagerAuth {
    val primaryKey: String

    // True if `key` is currently valid
    fun validateKey(key: String): Boolean

    // for display
    fun currentCode(): String

    // Checks `code` against the current time step. mints and returns new key when valid code passed
    fun mintKey(code: String): String?
}

private const val SELF_MINT_PREFIX = "_self_minted_"

// RFC 6238 TOTP (HmacSHA1, 6 digits) over a secret generated fresh per process — there's no
// external authenticator app to keep a secret in sync with here, so nothing is lost by not
// persisting it across restarts.
//
// Keys minted via mintKey can optionally be persisted so pairing survives a server restart: pass
// keyDirectory + cipher and each minted key is written out as its own encrypted file in that
// directory, one key per file. On construction (and whenever invalidateKeyCache() is called) the
// directory is (re-)scanned: every file is decrypted and the result cached in mintedKeys, so
// validateKey stays a plain in-memory set lookup rather than hitting disk/crypto per request.
// Files that fail to decrypt (corrupted, or encrypted under a since-rotated key) are skipped
// rather than failing the whole scan. If keyValidityDuration is set, a file older than that is
// deleted instead of being read at all — expiry is enforced only at scan time, not continuously.
class TotpFileManagerAuth(
    override val primaryKey: String = generateApiKey(),
    private val step: Duration = 30.seconds,
    private val maxAttemptsPerWindow: Int = 5,
    private val attemptWindow: Duration = 1.minutes,
    private val clock: Clock = Clock.System,
    private val keyDirectory: File? = null,
    private val cipher: KeyCipher? = null,
    private val keyValidityDuration: Duration? = 2.days,
) : FileManagerAuth {
    private val secret: ByteArray = ByteArray(20).also { SecureRandom().nextBytes(it) }
    private val mintedKeys = ConcurrentHashMap.newKeySet<String>()
    private val cacheLock = Any()

    private val lockoutLock = Any()
    private var failureCount = 0
    private var windowStart: Instant = clock.now()

    init {
        invalidateKeyCache()
    }

    override fun validateKey(key: String): Boolean =
        key == primaryKey || mintedKeys.contains(key)

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
            // ±1 step tolerates the code having just rolled over between being read and typed.
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

    // Drops the in-memory cache of minted keys and re-reads keyDirectory from scratch — e.g.
    // after another process/instance has written new key files into it. A no-op if this instance
    // wasn't given a directory + cipher to persist through in the first place.
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
        // Filename just needs to be unique per key; the key itself only lives in the (encrypted)
        // file contents, so a fresh random name avoids leaking anything about the key through
        // the filename or needing to track any shared state to pick the next one.
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
