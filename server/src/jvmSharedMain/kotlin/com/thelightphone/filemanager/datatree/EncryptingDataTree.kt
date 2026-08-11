package com.thelightphone.filemanager.datatree

import com.thelightphone.filemanager.EntryType
import com.thelightphone.filemanager.KeyCipher
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private const val DEFAULT_MAX_PLAINTEXT_BYTES = 1024

// Only use for small text files!!
open class EncryptingDataTree(
    root: File,
    private val cipher: KeyCipher,
    private val maxPlaintextBytes: Int = DEFAULT_MAX_PLAINTEXT_BYTES,
    defaultThumbnails: Map<EntryType, File> = emptyMap(),
    readOnly: Boolean = false,
    showHiddenFiles: Boolean = false,
    cacheTtl: Duration = 5.minutes,
    timeNow: () -> Instant = { Clock.System.now() },
) : FileDataTree(root, defaultThumbnails, readOnly, showHiddenFiles, cacheTtl, timeNow) {
    override suspend fun <T> writeBytes(
        filePath: Path,
        block: suspend (OutputStream) -> T
    ): Result<T> {
        return super.writeBytes(filePath) { rawOutputStream ->
            val buffer = ByteArrayOutputStream()
            val result = block(SizeLimitingOutputStream(buffer, maxPlaintextBytes))
            val ciphertext = cipher.encrypt(buffer.toString(Charsets.UTF_8.name()))
            rawOutputStream.write(ciphertext)
            result
        }
    }

    override fun openRead(filePath: Path): Result<InputStream> {
        return super.openRead(filePath).mapCatching { rawInputStream ->
            val ciphertext = rawInputStream.use { it.readBytes() }
            val plaintext = cipher.decrypt(ciphertext)
            ByteArrayInputStream(plaintext.toByteArray(Charsets.UTF_8))
        }
    }
}

// So we can bail out early if someone tries to upload something unsupported
private class SizeLimitingOutputStream(
    private val delegate: OutputStream,
    private val maxBytes: Int,
) : OutputStream() {
    private var written = 0

    override fun write(b: Int) {
        checkLimit(1)
        delegate.write(b)
        written++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        checkLimit(len)
        delegate.write(b, off, len)
        written += len
    }

    private fun checkLimit(additional: Int) {
        if (written + additional > maxBytes) {
            throw IOException("File exceeds max size of $maxBytes bytes")
        }
    }
}
