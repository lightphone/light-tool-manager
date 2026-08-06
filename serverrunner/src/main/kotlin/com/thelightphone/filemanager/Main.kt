package com.thelightphone.filemanager

import io.ktor.http.ContentType
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.io.File
import java.net.NetworkInterface

fun main() {
    val resourceRoot = File(object {}.javaClass.getResource("/sample")!!.toURI())
    val uploadsDir = resourceRoot.resolve("uploads").apply{ mkdirs() }
    val allDirs = resourceRoot.listFiles { file -> file.isDirectory }.orEmpty().toList()

    val perDirViews = allDirs.map { dir ->
        DataView(FileBrowserSpec(label = dir.name, path = listOf(dir.name)), FileFileTree(dir, emptyMap()))
    }
    val combinedView = DataView(
        FileBrowserSpec(label = "all", path = listOf("all")),
        FileFileTree(allDirs, uploadsDir, emptyMap())
    )

    val secondLevel = DataView(RootViewSpec("Deeper", listOf("second")), StaticBranchProvider(perDirViews))

    val rootDataProvider = RootFileTree {
        DataView(RootViewSpec("root", emptyList()), StaticBranchProvider(perDirViews + combinedView + secondLevel))
    }
    kotlinx.coroutines.runBlocking { rootDataProvider.refreshProviders() }

    val apiKey = generateApiKey()
    val certCacheDir = File(System.getProperty("user.home") ?: ".", ".thelightphone/certs")
    val sslConfig = SslConfig(logger)
    val keyStore = sslConfig.loadKeyStore(certCacheDir)
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
        module(rootDataProvider, true, logger, apiKey)
        routing {
            get("/qr") {
                val html = object {}.javaClass.getResource("/qr-scan-test.html")!!.readText()
                call.respondText(html, ContentType.Text.Html)
            }
        }
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

val logger = object : Logger {
    override fun log(tag: String, message: String) {
        println("Light File Manager - $tag: $message")
    }

    override fun reportError(
        tag: String,
        exception: Exception?,
        message: String
    ) {
        System.err.println("Light File Manager - $tag: $message")
        exception?.let { System.err.println(it.stackTraceToString()) }
    }
}
