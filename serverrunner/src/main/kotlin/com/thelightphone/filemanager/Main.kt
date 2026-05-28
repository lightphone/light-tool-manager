package com.thelightphone.filemanager

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import java.io.File
import java.net.NetworkInterface

fun main() {
    val resourceRoot = File(object {}.javaClass.getResource("/sample")!!.toURI())
    val providers = resourceRoot.listFiles { file -> file.isDirectory }
        .orEmpty()
        .associate { dir -> dir.name to FileDataProvider(dir, emptyMap()) as DataProvider }

    val rootDataProvider = RootDataProvider { providers }
    kotlinx.coroutines.runBlocking { rootDataProvider.refreshProviders() }

    val apiKey = generateApiKey()
    val keyStore = SslConfig.loadKeyStore()
    // not important since we're using local-ip.co's certs which are public
    val keyStorePassword = "changeit"
    val localIp = getLocalIpAddress()

    println("Server starting...")
    println("HTTP:  http://0.0.0.0:$SERVER_PORT/#$apiKey")
    if (localIp != null) {
        val domain = localIp.replace('.', '-') + ".my.local-ip.co"
        println("HTTPS: https://$domain:$HTTPS_PORT/#$apiKey")
    }

    embeddedServer(Netty, configure = {
        connector { port = SERVER_PORT; host = "0.0.0.0" }
        sslConnector(
            keyStore = keyStore,
            keyAlias = "server",
            keyStorePassword = { keyStorePassword.toCharArray() },
            privateKeyPassword = { keyStorePassword.toCharArray() }
        ) {
            port = HTTPS_PORT
            host = "0.0.0.0"
        }
    }) {
        module(rootDataProvider, true, apiKey) { _, _, _ -> }
    }.start(wait = true)
}

private fun getLocalIpAddress(): String? {
    return NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { !it.isLoopback && it.isUp }
        .flatMap { it.inetAddresses.asSequence() }
        .filter { it is java.net.Inet4Address && !it.isLoopbackAddress }
        .map { it.hostAddress }
        .firstOrNull()
}
