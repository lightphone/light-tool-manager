package com.thelightphone.filemanager

import com.thelightphone.filemanager.datatree.EncryptingDataTree
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Path
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.test.*

// This file was largely written by an LLM
private class InMemoryKeyCipher(
    private val secretKey: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
) : KeyCipher {
    override fun encrypt(plaintext: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        return cipher.iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
    }

    override fun decrypt(ciphertext: ByteArray): String {
        val iv = ciphertext.copyOfRange(0, 12)
        val encrypted = ciphertext.copyOfRange(12, ciphertext.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }
}

class EncryptingDataTreeTest {

    private fun withTempDir(block: (File) -> Unit) {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "encryptingdatatreetest-${System.nanoTime()}")
        tempDir.mkdirs()
        try {
            block(tempDir)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `written content round-trips through encryption`() = withTempDir { tempDir ->
        runBlocking {
            val tree = EncryptingDataTree(
                tempDir,
                InMemoryKeyCipher()
            )
            val content = "hello, encrypted world"

            val writeResult = tree.writeBytes(Path.of("note.txt")) { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            }
            assertTrue(writeResult.isSuccess)

            val readBack = tree.getBytes(Path.of("note.txt"))
                .getOrThrow()
                .use { it.readBytes() }
                .toString(Charsets.UTF_8)
            assertEquals(content, readBack)
        }
    }

    @Test
    fun `raw file on disk is not the plaintext`() = withTempDir { tempDir ->
        runBlocking {
            val tree = EncryptingDataTree(
                tempDir,
                InMemoryKeyCipher()
            )
            val content = "super secret notes nobody else should read"

            tree.writeBytes(Path.of("secret.txt")) { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            }

            val rawBytes = File(tempDir, "secret.txt").readBytes()
            assertFalse(rawBytes.toString(Charsets.UTF_8).contains(content))
        }
    }

    @Test
    fun `content over the plaintext limit fails and commits nothing`() = withTempDir { tempDir ->
        runBlocking {
            val tree = EncryptingDataTree(
                tempDir,
                InMemoryKeyCipher(),
                maxPlaintextBytes = 8
            )
            val content = "this is definitely more than eight bytes"

            val writeResult = tree.writeBytes(Path.of("toolong.txt")) { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            }

            assertTrue(writeResult.isFailure)
            assertFalse(File(tempDir, "toolong.txt").exists())
        }
    }

    @Test
    fun `content at exactly the plaintext limit succeeds`() = withTempDir { tempDir ->
        runBlocking {
            val tree = EncryptingDataTree(
                tempDir,
                InMemoryKeyCipher(),
                maxPlaintextBytes = 8
            )
            val content = "12345678" // exactly 8 bytes

            val writeResult = tree.writeBytes(Path.of("exact.txt")) { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            }

            assertTrue(writeResult.isSuccess)
            val readBack = tree.getBytes(Path.of("exact.txt"))
                .getOrThrow()
                .use { it.readBytes() }
                .toString(Charsets.UTF_8)
            assertEquals(content, readBack)
        }
    }

    @Test
    fun `reading with the wrong key fails instead of returning garbage`() = withTempDir { tempDir ->
        runBlocking {
            val writer =
                EncryptingDataTree(
                    tempDir,
                    InMemoryKeyCipher()
                )
            writer.writeBytes(Path.of("note.txt")) { out ->
                out.write("hi there".toByteArray(Charsets.UTF_8))
            }

            // A second cipher instance gets its own random key by default, standing in for e.g.
            // a different device/install trying to read files it never encrypted.
            val readerWithDifferentKey =
                EncryptingDataTree(
                    tempDir,
                    InMemoryKeyCipher()
                )
            val readResult = readerWithDifferentKey.getBytes(Path.of("note.txt"))

            assertTrue(readResult.isFailure)
        }
    }
}
