package com.thelightphone.toolmanager

import com.thelightphone.toolmanager.datatree.FileDataTree
import com.thelightphone.toolmanager.datatree.RootDataTree
import com.thelightphone.toolmanager.datatree.StaticBranchProvider
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
            exception: Throwable?,
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
            val provider = FileDataTree(
                tempDir,
                emptyMap()
            )
            val view = LeafView(FileBrowserSpec("files", "files"), provider)
            val rootProvider =
                RootDataTree {
                    BranchView(
                        RootViewSpec("root", ""),
                        StaticBranchProvider(
                            listOf(view)
                        )
                    )
                }
            rootProvider.refreshProviders()

            application {
                module(rootProvider, false, logger)
            }
            val response = client.get("/api/tree")
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("files"))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
