package com.thelightphone.filemanager

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import java.io.File

fun main() {
    val resourceRoot = File(object {}.javaClass.getResource("/sample")!!.toURI())
    val providers = resourceRoot.listFiles { file -> file.isDirectory }
        .orEmpty()
        .associate { dir -> dir.name to FileDataProvider(dir, emptyMap()) as DataProvider }

    val rootDataProvider = RootDataProvider { providers }
    kotlinx.coroutines.runBlocking { rootDataProvider.refreshProviders() }

    embeddedServer(Netty, port = SERVER_PORT, host = "0.0.0.0") {
        module(rootDataProvider, true) { _, _, _ ->
            // Log here
        }
    }.start(wait = true)
}
