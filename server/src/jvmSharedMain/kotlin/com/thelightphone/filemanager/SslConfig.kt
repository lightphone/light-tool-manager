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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.util.Base64

// This file was mostly written by an LLM, but reviewed by a human
private const val CERT_URL = "https://local-ip.co/cert/server.pem"
private const val CHAIN_URL = "https://local-ip.co/cert/chain.pem"
private const val KEY_URL = "https://local-ip.co/cert/server.key"

private const val CERT_FILE = "server.pem"
private const val CHAIN_FILE = "chain.pem"
private const val KEY_FILE = "server.key"

// Authority Information Access extension (RFC 5280 4.2.2.1) and its "CA Issuers" access method —
// used to fetch a missing intermediate directly from the issuing CA when chain.pem doesn't have it.
private const val AIA_EXTENSION_OID = "1.3.6.1.5.5.7.1.1"
private const val CA_ISSUERS_OID = "1.3.6.1.5.5.7.48.2"
private const val MAX_CHAIN_LENGTH = 5

// Treat certs within this window of notAfter as already expired, so we refresh
// before they actually start failing handshakes.
private val EXPIRY_GRACE: Duration = Duration.ofDays(2)
class SslConfig(private val logger: Logger) {

    companion object {
        const val TAG = "SslConfig"
    }

    fun loadKeyStore(
        cacheDir: File,
        password: CharArray = "changeit".toCharArray() // arbitrary password is fine here
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

    // Writes each file to a temp path first and only renames into place once all three downloads
    // have fully landed on disk, so a crash mid-write can't leave a mismatched cert/chain/key
    // triple cached — which would otherwise pass isExpiringSoon (the leaf's notAfter looks fine)
    // and get reused indefinitely, breaking every handshake until the cache dir is cleared by hand.
    private fun writeToDir(dir: File, certs: Certs) {
        dir.mkdirs()
        val pending = listOf(
            File(dir, CERT_FILE) to certs.cert,
            File(dir, CHAIN_FILE) to certs.chain,
            File(dir, KEY_FILE) to certs.key,
        ).map { (dest, bytes) ->
            val temp = File(dir, "${dest.name}.tmp")
            temp.writeBytes(bytes)
            temp to dest
        }
        for ((temp, dest) in pending) {
            Files.move(
                temp.toPath(),
                dest.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        }
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
        if (fullChain.size == 1) {
            logger.log(TAG, "Could not complete cert chain via chain.pem or AIA fetch; serving leaf only")
        }

        val privateKey = parsePrivateKey(certs.key)
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, null)
        keyStore.setKeyEntry("server", privateKey, password, fullChain)
        return keyStore
    }

    // Walk from the leaf toward a self-signed root. Prefers intermediates already present in
    // chain.pem, matched by subject == previous cert's issuer (so PKCS12 won't reject a
    // stale/mismatched chain.pem). If chain.pem doesn't have the right intermediate — observed in
    // practice: local-ip.co's published chain.pem can drift out of sync with whichever CA is
    // currently issuing server.pem — fetches the missing issuer via the cert's Authority
    // Information Access "CA Issuers" URL instead. That's the same mechanism most OS trust stores
    // (e.g. macOS's, which is why curl succeeds where Node-based clients don't) use to complete
    // chains a server gets wrong, so this makes us resilient to the same class of mismatch.
    private fun buildValidatedChain(
        leaf: X509Certificate,
        intermediates: List<X509Certificate>
    ): Array<X509Certificate> {
        val bySubject = intermediates.associateBy { it.subjectX500Principal }
        val chain = mutableListOf(leaf)
        var current = leaf
        var usedAiaFetch = false
        // Real chains are leaf -> intermediate(s) -> root, essentially never more than a handful
        // of hops. Caps how many AIA fetches a misbehaving/compromised upstream could force.
        while (current.issuerX500Principal != current.subjectX500Principal && chain.size < MAX_CHAIN_LENGTH) {
            val next = bySubject[current.issuerX500Principal]
                ?: fetchIssuerCert(current)?.also { usedAiaFetch = true }
            if (next == null || next in chain) break
            chain.add(next)
            current = next
        }
        if (usedAiaFetch && chain.size > 1) {
            logger.log(
                TAG,
                "chain.pem missing current intermediate for server.pem; completed chain via AIA fetch"
            )
        }
        return chain.toTypedArray()
    }

    private fun fetchIssuerCert(cert: X509Certificate): X509Certificate? {
        // caIssuerUrl hand-parses DER out of a cert extension; malformed input (e.g. a length
        // field that overflows the multi-byte-length loop) should fail this one lookup, not crash
        // the whole loadKeyStore() call — which currently has no caller-side catch of its own.
        val url = runCatching { caIssuerUrl(cert) }
            .onFailure { logger.reportError(TAG, Exception(it), "Error parsing AIA extension") }
            .getOrNull() ?: return null
        // AIA URLs are conventionally plain http:// — CDNs hosting these almost always answer on
        // https:// too, and Android blocks cleartext HTTP by default (API 28+), so this would
        // otherwise silently fail on-device even though it works fine on desktop JVM.
        val httpsUrl = if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else null

        if (httpsUrl != null) {
            fetchCert(httpsUrl).onSuccess { return it }
                .onFailure {
                    logger.reportError(TAG, Exception(it), "Error fetching issuer cert via AIA over https: $httpsUrl")
                }
        }

        return fetchCert(url)
            .onFailure { logger.reportError(TAG, Exception(it), "Error fetching issuer cert via AIA: $url") }
            .getOrNull()
    }

    private fun fetchCert(url: String): Result<X509Certificate> = runCatching {
        // loadKeyStore is called synchronously from FileManagerServiceAndroid.start(), which runs
        // on the main thread — same reason downloadCerts() below dispatches its network calls too.
        val bytes = runBlocking(Dispatchers.IO) { httpGet(url) }
        val cf = CertificateFactory.getInstance("X.509")
        cf.generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
    }

    // Extracts the "CA Issuers" URI from a certificate's Authority Information Access extension
    // (RFC 5280 4.2.2.1). No off-the-shelf parser for this is available on Android without
    // pulling in BouncyCastle, so this reads the handful of DER TLVs involved by hand.
    private fun caIssuerUrl(cert: X509Certificate): String? {
        val extnValue = cert.getExtensionValue(AIA_EXTENSION_OID) ?: return null
        // extnValue is itself DER: an OCTET STRING wrapping the real AuthorityInfoAccessSyntax.
        val outer = readDerTlv(extnValue, 0) ?: return null
        val sequence = readDerTlv(outer.content, 0) ?: return null

        var offset = 0
        val body = sequence.content
        while (offset < body.size) {
            val accessDescription = readDerTlv(body, offset) ?: break
            val oidTlv = readDerTlv(accessDescription.content, 0)
            val nameTlv = oidTlv?.let { readDerTlv(accessDescription.content, it.nextOffset) }
            if (oidTlv != null && nameTlv != null) {
                val isCaIssuers = decodeOid(oidTlv.content) == CA_ISSUERS_OID
                // [6] IMPLICIT IA5String (uniformResourceIdentifier), context-specific primitive.
                val isUri = nameTlv.tag == 0x86
                if (isCaIssuers && isUri) {
                    return String(nameTlv.content, Charsets.US_ASCII)
                }
            }
            offset = accessDescription.nextOffset
        }
        return null
    }

    private data class DerTlv(val tag: Int, val content: ByteArray, val nextOffset: Int)

    private fun readDerTlv(data: ByteArray, offset: Int): DerTlv? {
        if (offset + 1 >= data.size) return null
        val tag = data[offset].toInt() and 0xFF
        val firstLenByte = data[offset + 1].toInt() and 0xFF
        val contentStart: Int
        val length: Int
        if (firstLenByte and 0x80 == 0) {
            length = firstLenByte
            contentStart = offset + 2
        } else {
            val numLenBytes = firstLenByte and 0x7F
            if (offset + 2 + numLenBytes > data.size) return null
            var len = 0
            for (i in 0 until numLenBytes) {
                len = (len shl 8) or (data[offset + 2 + i].toInt() and 0xFF)
            }
            length = len
            contentStart = offset + 2 + numLenBytes
        }
        if (contentStart + length > data.size) return null
        return DerTlv(tag, data.copyOfRange(contentStart, contentStart + length), contentStart + length)
    }

    private fun decodeOid(bytes: ByteArray): String {
        val parts = mutableListOf<Long>()
        var value = 0L
        for (b in bytes) {
            val byteVal = b.toInt() and 0xFF
            value = (value shl 7) or (byteVal and 0x7F).toLong()
            if (byteVal and 0x80 == 0) {
                parts.add(value)
                value = 0
            }
        }
        if (parts.isEmpty()) return ""
        val first = parts[0]
        val x = if (first < 40) 0L else if (first < 80) 1L else 2L
        val rest = parts.drop(1)
        return (listOf(x, first - x * 40) + rest).joinToString(".")
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
