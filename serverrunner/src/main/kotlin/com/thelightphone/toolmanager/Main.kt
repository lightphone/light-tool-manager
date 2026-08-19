package com.thelightphone.toolmanager

import com.thelightphone.toolmanager.datatree.CustomDataTree
import com.thelightphone.toolmanager.datatree.RootDataTree
import com.thelightphone.toolmanager.datatree.StaticBranchProvider
import io.ktor.http.ContentType
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.io.File
import java.io.InputStream
import java.net.NetworkInterface
import java.nio.file.Path

fun main() {
    val resourceRoot = File(object {}.javaClass.getResource("/sample")!!.toURI())
    val uploadsDir = resourceRoot.resolve("Uploads").apply { mkdirs() }
    val allDirs = resourceRoot.listFiles { file -> file.isDirectory }.orEmpty().toList()
    println(allDirs)

    val perDirViews = allDirs.map { dir ->
        LeafView(
            FileBrowserSpec(
                label = dir.name,
                path = dir.name,
                headerText = "This directory is called: ${dir.name}\n\nFeel free to take a look around!"
            ),
            DesktopDataTree(dir)
        )
    }
    val combinedView = LeafView(
        FileBrowserSpec(
            label = "All Files",
            path = "all",
            headerText = "These are all of the files!"
        ),
        DesktopDataTree(allDirs, uploadsDir)
    )

    val uploadsDropBox = LeafView(
        DropboxSpec(
            "Uploads Dropbox",
            uploadsDir.name,
            headerText = "This is the Uploads Dropbox\n" +
                    "\n" +
                    "When you upload here, your files will show in the Uploads directory!",
            buttonText = "Click Here to Upload"
        ),
        DesktopDataTree(uploadsDir)
    )

    val export = LeafView(
        ExportSpec(
            "Export Greeting",
            "export",
            "hello.txt",
            "Click below to export your greeting.",
            "Export"
        ),
        DesktopDataTree(resourceRoot.resolve("Text"))
    )

    val hiddenDropbox = LeafView(
        DropboxSpec(
            "You Shouldn't See This",
            uploadsDir.name,
            headerText = "This is the HIDDEN Uploads Dropbox\n" +
                    "\n" +
                    "When you upload here, your files will show in the Uploads directory!",
            buttonText = "Click Here to Upload"
        ),
        DesktopDataTree(uploadsDir),
        forceHide = true
    )

    val customDataEndpoint = LeafView(
        CustomSpec("custom", "custom"),
        object : CustomDataTree() {
            override suspend fun getBytes(filePath: Path): Result<InputStream> {
                return Result.success("""{"data" : "here"}""".byteInputStream())
            }

        }
    )

    val rootDataProvider = RootDataTree {
        BranchView(
            RootViewSpec("root", ""),
            StaticBranchProvider(
                perDirViews + combinedView + uploadsDropBox + hiddenDropbox + customDataEndpoint + export
            )
        )
    }
    kotlinx.coroutines.runBlocking { rootDataProvider.refreshProviders() }

    val auth: ToolManagerAuth = TotpToolManagerAuth()
    val certCacheDir = File(System.getProperty("user.home") ?: ".", ".thelightphone/certs")
    val sslConfig = SslConfig(logger)
    val keyStore = sslConfig.loadKeyStore(certCacheDir)
    // not important since we're using local-ip.co's certs which are public
    val keyStorePassword = "changeit"
    val localIp = getLocalIpAddress()

    println("Server starting...")
    println("HTTP:  http://0.0.0.0:$SERVER_PORT/#${auth.primaryKey}")
    if (localIp != null) {
        val domain = localIp.replace('.', '-') + ".my.local-ip.co"
        println("HTTPS: https://$domain:$HTTPS_PORT/#${auth.primaryKey}")
    }
    // Alternative to sharing the link/QR directly: read this code off to someone and have them
    // enter it manually to pair their own persistent key. It rotates every 30s, so this printed
    // value goes stale — there's no live-refreshing display of it yet.
    println("Pairing code: ${auth.currentCode()} (valid ~30s)")

    embeddedServer(Netty, configure = {
        // See the matching comment in ToolManagerServiceAndroid
        enableHttp2 = false
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
        module(rootDataProvider, true, logger, auth)
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
        println("Light Tool Manager - $tag: $message")
    }

    override fun reportError(
        tag: String,
        exception: Throwable?,
        message: String
    ) {
        System.err.println("Light Tool Manager - $tag: $message")
        exception?.let { System.err.println(it.stackTraceToString()) }
    }
}
