package com.thelightphone.filemanager

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64

private const val CERT_URL = "https://local-ip.co/cert/server.pem"
private const val CHAIN_URL = "https://local-ip.co/cert/chain.pem"
private const val KEY_URL = "https://local-ip.co/cert/server.key"

private const val CERT_FILE = "server.pem"
private const val CHAIN_FILE = "chain.pem"
private const val KEY_FILE = "server.key"

// Treat certs within this window of notAfter as already expired, so we refresh
// before they actually start failing handshakes.
private val EXPIRY_GRACE: Duration = Duration.ofDays(2)
class SslConfig(private val logger: Logger) {

    companion object {
        const val TAG = "SslConfig"
    }

    fun loadKeyStore(
        cacheDir: File,
        password: CharArray = "changeit".toCharArray()
    ): KeyStore {
        val certs = obtainCerts(cacheDir)
        return buildKeyStore(certs, password)
    }

    private data class Certs(val cert: ByteArray, val chain: ByteArray, val key: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Certs

            if (!cert.contentEquals(other.cert)) return false
            if (!chain.contentEquals(other.chain)) return false
            if (!key.contentEquals(other.key)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = cert.contentHashCode()
            result = 31 * result + chain.contentHashCode()
            result = 31 * result + key.contentHashCode()
            return result
        }
    }

    private fun obtainCerts(cacheDir: File): Certs {
        val fsCerts = readFromDir(cacheDir)
        if (fsCerts != null && !isExpiringSoon(fsCerts.cert)) return fsCerts

        val resourceCerts = readFromResources()
        if (resourceCerts != null && !isExpiringSoon(resourceCerts.cert)) return resourceCerts

        val downloaded = runCatching { downloadCerts() }
            .onFailure { logger.reportError(TAG, Exception(it), "Error downloading certs") }
            .getOrNull()
        if (downloaded != null) {
            runCatching { writeToDir(cacheDir, downloaded) }
                .onFailure { logger.reportError(TAG, Exception(it), "Error writing certs") }
            return downloaded
        }

        // Network unavailable — fall back to whatever we have, even if expired.
        return fsCerts
            ?: resourceCerts
            ?: throw IllegalStateException("No certs on disk or in resources, and download failed")
    }

    private fun readFromDir(dir: File): Certs? = runCatching {
        val cert = File(dir, CERT_FILE)
        val chain = File(dir, CHAIN_FILE)
        val key = File(dir, KEY_FILE)
        if (!cert.isFile || !chain.isFile || !key.isFile) return@runCatching null
        Certs(cert.readBytes(), chain.readBytes(), key.readBytes())
    }
        .onFailure { logger.reportError(TAG, Exception(it), "Error reading certs") }
        .getOrNull()

    private fun readFromResources(): Certs? {
        val cert = loadResourceOrNull("/certs/server.pem") ?: return null
        val chain = loadResourceOrNull("/certs/chain.pem") ?: return null
        val key = loadResourceOrNull("/certs/server.key") ?: return null
        return Certs(cert, chain, key)
    }

    private fun writeToDir(dir: File, certs: Certs) {
        dir.mkdirs()
        File(dir, CERT_FILE).writeBytes(certs.cert)
        File(dir, CHAIN_FILE).writeBytes(certs.chain)
        File(dir, KEY_FILE).writeBytes(certs.key)
    }

    private fun downloadCerts(): Certs = runBlocking(Dispatchers.IO) {
        logger.log(TAG, "Light File Manager downloading certs from local-ip.co")
        val (cert, chain, key) = listOf(CERT_URL, CHAIN_URL, KEY_URL)
            .map { url -> async { httpGet(url) } }
            .awaitAll()
        Certs(cert, chain, key)
    }

    private fun httpGet(url: String): ByteArray {
        val conn = (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code from $url")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun isExpiringSoon(certPem: ByteArray): Boolean = runCatching {
        val cf = CertificateFactory.getInstance("X.509")
        val cert = cf.generateCertificate(ByteArrayInputStream(certPem)) as X509Certificate
        val expiresAt = cert.notAfter.toInstant()
        Instant.now().plus(EXPIRY_GRACE).isAfter(expiresAt)
    }
        .onFailure { logger.reportError(TAG, Exception(it), "Error checking expiry") }
        .getOrElse { true }
        .also { if (it) logger.log(TAG, "local-ip certs expiring soon") }

    private fun buildKeyStore(certs: Certs, password: CharArray): KeyStore {
        val cf = CertificateFactory.getInstance("X.509")
        val serverCert = cf.generateCertificate(ByteArrayInputStream(certs.cert)) as X509Certificate
        val chainCerts = cf.generateCertificates(ByteArrayInputStream(certs.chain))
            .map { it as X509Certificate }

        val fullChain = buildValidatedChain(serverCert, chainCerts)
        if (chainCerts.isNotEmpty() && fullChain.size == 1) {
            logger.log(TAG, "chain.pem does not chain to server.pem; serving leaf only")
        }

        val privateKey = parsePrivateKey(certs.key)
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, null)
        keyStore.setKeyEntry("server", privateKey, password, fullChain)
        return keyStore
    }

    // Walk from the leaf and only include intermediates whose subject matches the
    // previous cert's issuer, so PKCS12 won't reject a stale/mismatched chain.pem.
    private fun buildValidatedChain(
        leaf: X509Certificate,
        intermediates: List<X509Certificate>
    ): Array<X509Certificate> {
        val bySubject = intermediates.associateBy { it.subjectX500Principal }
        val chain = mutableListOf(leaf)
        var current = leaf
        while (current.issuerX500Principal != current.subjectX500Principal) {
            val next = bySubject[current.issuerX500Principal] ?: break
            if (next in chain) break
            chain.add(next)
            current = next
        }
        return chain.toTypedArray()
    }

    private fun parsePrivateKey(pem: ByteArray): java.security.PrivateKey {
        val pemStr = pem.decodeToString()
        val isPkcs1 = pemStr.contains("BEGIN RSA PRIVATE KEY")
        val body = pemStr
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")

        val der = Base64.getDecoder().decode(body)
        val pkcs8 = if (isPkcs1) pkcs1ToPkcs8(der) else der
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
    }

    // Wrap a PKCS#1 RSAPrivateKey in a PKCS#8 PrivateKeyInfo envelope.
    private fun pkcs1ToPkcs8(pkcs1: ByteArray): ByteArray {
        // AlgorithmIdentifier for rsaEncryption (OID 1.2.840.113549.1.1.1) with NULL params.
        val rsaAlgId = byteArrayOf(
            0x30, 0x0D,
            0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(),
            0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01,
            0x05, 0x00
        )
        val version = byteArrayOf(0x02, 0x01, 0x00)
        val octetString = asn1Wrap(0x04, pkcs1)
        return asn1Wrap(0x30, version + rsaAlgId + octetString)
    }

    private fun asn1Wrap(tag: Int, content: ByteArray): ByteArray {
        val len = content.size
        val lengthBytes = when {
            len < 0x80 -> byteArrayOf(len.toByte())
            len < 0x100 -> byteArrayOf(0x81.toByte(), len.toByte())
            len < 0x10000 -> byteArrayOf(
                0x82.toByte(),
                (len ushr 8).toByte(),
                (len and 0xFF).toByte()
            )
            else -> byteArrayOf(
                0x83.toByte(),
                (len ushr 16).toByte(),
                (len ushr 8).toByte(),
                (len and 0xFF).toByte()
            )
        }
        return byteArrayOf(tag.toByte()) + lengthBytes + content
    }

    private fun loadResourceOrNull(path: String): ByteArray? =
        SslConfig::class.java.getResourceAsStream(path)?.readBytes()
}
