package com.thelightphone.filemanager

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.io.File
import kotlin.test.*

class ApplicationTest {

    private val logger = object : Logger {
        override fun log(tag: String, message: String) {
            println("$tag: $message")
        }

        override fun reportError(
            tag: String,
            exception: Exception?,
            message: String
        ) {
            System.err.println("$tag: $message")
            exception?.let { System.err.println(it.stackTraceToString()) }
        }
    }

    @Test
    fun testRootEndpoint() = testApplication {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "apptest-${System.nanoTime()}")
        tempDir.mkdirs()
        try {
            val provider = FileDataProvider(tempDir, emptyMap())
            val rootProvider = RootDataProvider { mapOf("files" to provider) }
            rootProvider.refreshProviders()

            application {
                module(rootProvider, false, logger)
            }
            val response = client.get("/api/root")
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("files"))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
