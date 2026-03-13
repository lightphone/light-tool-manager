package com.thelightphone.filemanager

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.io.File
import kotlin.test.*

class ApplicationTest {

    @Test
    fun testRootEndpoint() = testApplication {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "apptest-${System.nanoTime()}")
        tempDir.mkdirs()
        try {
            val provider = FileDataProvider(tempDir, emptyMap())
            val rootProvider = RootDataProvider { mapOf("files" to provider) }
            rootProvider.refreshProviders()

            application {
                module(rootProvider, false)
            }
            val response = client.get("/api/root")
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("files"))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
