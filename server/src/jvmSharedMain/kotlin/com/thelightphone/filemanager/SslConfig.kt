package com.thelightphone.filemanager

import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

object SslConfig {
    fun loadKeyStore(password: CharArray = "changeit".toCharArray()): KeyStore {
        // need to download these from local-ip.co
        // and put them in server/src/jvmSharedMain/resources/certs
        val certPem = loadResource("/certs/server.pem")
        val chainPem = loadResource("/certs/chain.pem")
        val keyPem = loadResource("/certs/server.key")

        val cf = CertificateFactory.getInstance("X.509")

        val serverCert = cf.generateCertificate(ByteArrayInputStream(certPem)) as X509Certificate
        val chainCerts = cf.generateCertificates(ByteArrayInputStream(chainPem))
            .map { it as X509Certificate }

        val fullChain = arrayOf(serverCert) + chainCerts.toTypedArray()

        val privateKey = parsePrivateKey(keyPem)

        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, null)
        keyStore.setKeyEntry("server", privateKey, password, fullChain)

        return keyStore
    }

    private fun parsePrivateKey(pem: ByteArray): java.security.PrivateKey {
        val pemStr = pem.decodeToString()
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")

        val keyBytes = Base64.getDecoder().decode(pemStr)
        val keySpec = PKCS8EncodedKeySpec(keyBytes)
        return KeyFactory.getInstance("RSA").generatePrivate(keySpec)
    }

    private fun loadResource(path: String): ByteArray {
        return SslConfig::class.java.getResourceAsStream(path)?.readBytes()
            ?: throw IllegalStateException("Resource not found: $path - please make sure certs are downloaded.")
    }
}
