package com.thelightphone.toolmanager

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val AndroidKeyStoreProvider = "AndroidKeyStore"
private const val Transformation = "AES/GCM/NoPadding"
private const val GcmIvLengthBytes = 12
private const val GcmTagLengthBits = 128

// AES-GCM via a key that never leaves AndroidKeyStore — a core system service, not a Play
// Services API, so this works on GMS-free devices. Deliberately skips the androidx.security
// (Jetpack Security/Tink) dependency for something this small: KeyGenParameterSpec + Cipher
// directly is ~40 lines with no extra dependency weight.
class AndroidKeystoreKeyCipher(
    private val keyAlias: String = "tool_manager_auth_key",
    private val keyStore: KeyStore = KeyStore.getInstance(AndroidKeyStoreProvider)
        .apply { load(null) }
) : KeyCipher {
    init {
        if (!keyStore.containsAlias(keyAlias)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                AndroidKeyStoreProvider
            )
            val spec = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }
    }

    private fun secretKey(): SecretKey = keyStore.getKey(keyAlias, null) as SecretKey

    override fun encrypt(plaintext: String): ByteArray {
        val cipher = Cipher.getInstance(Transformation)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        // GCM's IV isn't secret, just needs to travel with the ciphertext to decrypt it later.
        return cipher.iv + ciphertext
    }

    override fun decrypt(ciphertext: ByteArray): String {
        val iv = ciphertext.copyOfRange(0, GcmIvLengthBytes)
        val encrypted = ciphertext.copyOfRange(GcmIvLengthBytes, ciphertext.size)
        val cipher = Cipher.getInstance(Transformation)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GcmTagLengthBits, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }
}
