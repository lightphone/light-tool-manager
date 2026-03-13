package com.thelightphone.filemanager

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class DataProviderTest {

    private lateinit var tempDir: File

    private fun createProvider(): FileDataProvider =
        FileDataProvider(root = tempDir, defaultThumbnails = emptyMap())

    private fun createRootProvider(vararg named: Pair<String, FileDataProvider>): RootDataProvider {
        val map = named.toMap()
        return RootDataProvider { map }.also { runBlocking { it.refreshProviders() } }
    }

    @BeforeTest
    fun setup() {
        tempDir = createTempDirectory("meep").toFile()
    }

    @AfterTest
    fun teardown() {
        tempDir.deleteRecursively()
    }

    // -- FileDataProvider tests --

    @Test
    fun `list directory returns entries`() = runBlocking {
        File(tempDir, "photo.jpg").writeText("image data")
        File(tempDir, "notes.txt").writeText("some notes")
        File(tempDir, "subdir").mkdir()

        val result = createProvider().getDirectoryForPath(Path.of("."), PageRequest())
        assertTrue(result.isSuccess)
        val response = result.getOrThrow()
        assertEquals(3, response.pagination.totalItems)

        val types = response.data.map { it.type }.toSet()
        assertTrue(EntryType.Image in types)
        assertTrue(EntryType.Text in types)
        assertTrue(EntryType.Directory in types)
    }

    @Test
    fun `hidden files are excluded`() = runBlocking {
        File(tempDir, ".hidden").writeText("secret")
        File(tempDir, "visible.txt").writeText("hello")

        val result = createProvider().getDirectoryForPath(Path.of("."), PageRequest())
        val entries = result.getOrThrow().data
        assertEquals(1, entries.size)
        assertEquals("visible.txt", entries[0].title)
    }

    @Test
    fun `directories are listed before files`() = runBlocking {
        File(tempDir, "zebra.txt").writeText("last alphabetically")
        File(tempDir, "aardvark").mkdir()
        File(tempDir, "alpha.jpg").writeText("image")
        File(tempDir, "beta").mkdir()

        val result = createProvider().getDirectoryForPath(Path.of("."), PageRequest())
            .getOrThrow().data

        val dirCount = result.count { it.type == EntryType.Directory }
        assertEquals(2, dirCount)
        // All directories should come before any file
        val types = result.map { it.type }
        val lastDirIndex = types.indexOfLast { it == EntryType.Directory }
        val firstFileIndex = types.indexOfFirst { it != EntryType.Directory }
        assertTrue(lastDirIndex < firstFileIndex)
    }

    @Test
    fun `pagination works`() = runBlocking {
        repeat(5) { i -> File(tempDir, "file$i.txt").writeText("content $i") }

        val page1 = createProvider().getDirectoryForPath(Path.of("."), PageRequest(page = 1, size = 2))
            .getOrThrow()
        assertEquals(2, page1.data.size)
        assertEquals(5, page1.pagination.totalItems)
        assertEquals(3, page1.pagination.totalPages)
        assertTrue(page1.pagination.hasNext)
        assertFalse(page1.pagination.hasPrevious)

        val page3 = createProvider().getDirectoryForPath(Path.of("."), PageRequest(page = 3, size = 2))
            .getOrThrow()
        assertEquals(1, page3.data.size)
        assertFalse(page3.pagination.hasNext)
        assertTrue(page3.pagination.hasPrevious)
    }

    @Test
    fun `getBytes returns file content`() = runBlocking {
        File(tempDir, "hello.txt").writeText("hello world")

        val result = createProvider().getBytes(Path.of("hello.txt"))
        assertTrue(result.isSuccess)
        assertEquals("hello world", result.getOrThrow().bufferedReader().readText())
    }

    @Test
    fun `getBytes fails for directory`() = runBlocking {
        File(tempDir, "subdir").mkdir()

        val result = createProvider().getBytes(Path.of("subdir"))
        assertTrue(result.isFailure)
        assertIs<IllegalArgumentException>(result.exceptionOrNull())
        Unit
    }

    @Test
    fun `path traversal is rejected`() = runBlocking {
        val result = createProvider().getBytes(Path.of("../../etc/passwd"))
        assertTrue(result.isFailure)
        assertIs<NoSuchElementException>(result.exceptionOrNull())
        Unit
    }

    @Test
    fun `entry types are detected correctly`() = runBlocking {
        File(tempDir, "song.mp3").writeText("")
        File(tempDir, "movie.mp4").writeText("")
        File(tempDir, "photo.png").writeText("")
        File(tempDir, "data.bin").writeText("")

        val entries = createProvider().getDirectoryForPath(Path.of("."), PageRequest())
            .getOrThrow().data.associateBy { it.title }

        assertEquals(EntryType.Audio, entries["song.mp3"]!!.type)
        assertEquals(EntryType.Video, entries["movie.mp4"]!!.type)
        assertEquals(EntryType.Image, entries["photo.png"]!!.type)
        assertEquals(EntryType.GenericFile, entries["data.bin"]!!.type)
    }

    @Test
    fun `subdirectory listing works`() = runBlocking {
        val sub = File(tempDir, "photos")
        sub.mkdir()
        File(sub, "a.jpg").writeText("img")
        File(sub, "b.jpg").writeText("img")

        val result = createProvider().getDirectoryForPath(Path.of("photos"), PageRequest())
        assertEquals(2, result.getOrThrow().pagination.totalItems)
    }

    @Test
    fun `nonexistent path fails`() = runBlocking {
        val result = createProvider().getDirectoryForPath(Path.of("nope"), PageRequest())
        assertTrue(result.isFailure)
    }

    @Test
    fun `getThumbnailBytes returns image bytes`() = runBlocking {
        File(tempDir, "pic.jpg").writeText("jpeg data")

        val result = createProvider().getThumbnailBytes(Path.of("pic.jpg"), EntryType.Image)
        assertTrue(result.isSuccess)
        assertEquals("jpeg data", result.getOrThrow().bufferedReader().readText())
    }

    // -- RootDataProvider tests --

    @Test
    fun `root provider routes to correct child`() = runBlocking {
        val dir1 = File(tempDir, "photos").apply { mkdir() }
        File(dir1, "a.jpg").writeText("photo")
        val dir2 = File(tempDir, "docs").apply { mkdir() }
        File(dir2, "readme.md").writeText("docs")

        val root = createRootProvider(
            "photos" to FileDataProvider(dir1, emptyMap()),
            "docs" to FileDataProvider(dir2, emptyMap())
        )

        val photosResult = root.getDirectoryForPath(Path.of("photos/."), PageRequest())
        assertEquals(1, photosResult.getOrThrow().pagination.totalItems)
        assertEquals(EntryType.Image, photosResult.getOrThrow().data[0].type)

        val docsResult = root.getDirectoryForPath(Path.of("docs/."), PageRequest())
        assertEquals(1, docsResult.getOrThrow().pagination.totalItems)
        assertEquals(EntryType.Text, docsResult.getOrThrow().data[0].type)
    }

    @Test
    fun `root provider getBytes routes correctly`() = runBlocking {
        val dir = File(tempDir, "files").apply { mkdir() }
        File(dir, "test.txt").writeText("content here")

        val root = createRootProvider("files" to FileDataProvider(dir, emptyMap()))

        val result = root.getBytes(Path.of("files/test.txt"))
        assertEquals("content here", result.getOrThrow().bufferedReader().readText())
    }

    @Test
    fun `root provider fails for unknown root`() = runBlocking {
        val root = createRootProvider()

        val result = root.getDirectoryForPath(Path.of("unknown/path"), PageRequest())
        assertTrue(result.isFailure)
        assertIs<NoSuchElementException>(result.exceptionOrNull())
        Unit
    }

    @Test
    fun `root provider lists roots`() = runBlocking {
        val dir = File(tempDir, "a").apply { mkdir() }
        val root = createRootProvider(
            "alpha" to FileDataProvider(dir, emptyMap()),
            "beta" to FileDataProvider(dir, emptyMap())
        )

        val roots = root.getRoot()
        assertEquals(setOf("alpha", "beta"), roots.paths.toSet())
    }

    @Test
    fun `root provider invalidateCache refreshes providers`() = runBlocking {
        val dir = File(tempDir, "dynamic").apply { mkdir() }
        File(dir, "file1.txt").writeText("v1")

        var providerMap = mapOf("src" to FileDataProvider(dir, emptyMap()) as DataProvider)
        val root = RootDataProvider { providerMap }
        root.refreshProviders()

        println(root.getDirectoryForPath(Path.of("src/."), PageRequest()))
        assertTrue(root.getDirectoryForPath(Path.of("src/."), PageRequest()).isSuccess)

        // Simulate adding a new provider
        val dir2 = File(tempDir, "extra").apply { mkdir() }
        File(dir2, "new.txt").writeText("new")
        providerMap = providerMap + ("extra" to FileDataProvider(dir2, emptyMap()))

        // Without invalidation, new root is unknown
        assertTrue(root.getDirectoryForPath(Path.of("extra/."), PageRequest()).isFailure)

        // With invalidation, it refreshes
        assertTrue(root.getDirectoryForPath(Path.of("extra/."), PageRequest(), invalidateCache = true).isSuccess)
    }

    // -- checkWrite tests --

    @Test
    fun `checkWrite returns Safe for new file`() = runBlocking {
        val check = createProvider().checkWrite(Path.of("newfile.txt"))
        assertIs<WriteCheck.Safe>(check)
        Unit
    }

    @Test
    fun `checkWrite returns FileExists for existing file`() = runBlocking {
        File(tempDir, "existing.txt").writeText("data")

        val check = createProvider().checkWrite(Path.of("existing.txt"))
        assertIs<WriteCheck.FileExists>(check)
        assertEquals("existing.txt", check.existing.title)
    }

    @Test
    fun `checkWrite returns DirectoryExists for existing directory`() = runBlocking {
        File(tempDir, "mydir").mkdir()

        val check = createProvider().checkWrite(Path.of("mydir"))
        assertIs<WriteCheck.DirectoryExists>(check)
        Unit
    }

    @Test
    fun `checkWrite returns InvalidPath for missing parent`() = runBlocking {
        val check = createProvider().checkWrite(Path.of("no/such/parent/file.txt"))
        assertIs<WriteCheck.InvalidPath>(check)
        Unit
    }

    @Test
    fun `checkWrite returns InvalidPath for path traversal`() = runBlocking {
        val check = createProvider().checkWrite(Path.of("../../etc/evil.txt"))
        assertIs<WriteCheck.InvalidPath>(check)
        Unit
    }

    // -- writeBytes tests --

    @Test
    fun `writeBytes creates new file`() = runBlocking {
        val provider = createProvider()
        val result = provider.writeBytes(Path.of("newfile.txt")) { out ->
            out.write("hello".toByteArray())
        }
        assertTrue(result.isSuccess)
        assertEquals("hello", File(tempDir, "newfile.txt").readText())
    }

    @Test
    fun `writeBytes overwrites existing file`() = runBlocking {
        File(tempDir, "existing.txt").writeText("old content")

        val provider = createProvider()
        val result = provider.writeBytes(Path.of("existing.txt")) { out ->
            out.write("new content".toByteArray())
        }
        assertTrue(result.isSuccess)
        assertEquals("new content", File(tempDir, "existing.txt").readText())
    }

    @Test
    fun `writeBytes fails for directory path`() = runBlocking {
        File(tempDir, "mydir").mkdir()

        val result = createProvider().writeBytes(Path.of("mydir")) { out ->
            out.write("data".toByteArray())
        }
        assertTrue(result.isFailure)
        assertIs<IllegalArgumentException>(result.exceptionOrNull())
        Unit
    }

    @Test
    fun `writeBytes fails for path traversal`() = runBlocking {
        val result = createProvider().writeBytes(Path.of("../../evil.txt")) { out ->
            out.write("data".toByteArray())
        }
        assertTrue(result.isFailure)
        assertIs<SecurityException>(result.exceptionOrNull())
        Unit
    }

    @Test
    fun `writeBytes fails for missing parent directory`() = runBlocking {
        val result = createProvider().writeBytes(Path.of("nonexistent/file.txt")) { out ->
            out.write("data".toByteArray())
        }
        assertTrue(result.isFailure)
        assertIs<SecurityException>(result.exceptionOrNull())
        Unit
    }

    @Test
    fun `writeBytes invalidates directory cache`() = runBlocking {
        val provider = createProvider()

        // Prime the cache
        val before = provider.getDirectoryForPath(Path.of("."), PageRequest()).getOrThrow()
        assertEquals(0, before.pagination.totalItems)

        // Write a new file
        provider.writeBytes(Path.of("cached.txt")) { out ->
            out.write("data".toByteArray())
        }

        // Cache should be invalidated, so the new file appears
        val after = provider.getDirectoryForPath(Path.of("."), PageRequest()).getOrThrow()
        assertEquals(1, after.pagination.totalItems)
        assertEquals("cached.txt", after.data[0].title)
    }

    // -- RootDataProvider write tests --

    @Test
    fun `root provider checkWrite routes correctly`() = runBlocking {
        val dir = File(tempDir, "files").apply { mkdir() }
        File(dir, "existing.txt").writeText("data")

        val root = createRootProvider("files" to FileDataProvider(dir, emptyMap()))

        assertIs<WriteCheck.Safe>(root.checkWrite(Path.of("files/newfile.txt")))
        assertIs<WriteCheck.FileExists>(root.checkWrite(Path.of("files/existing.txt")))
        assertIs<WriteCheck.InvalidPath>(root.checkWrite(Path.of("unknown/file.txt")))
        Unit
    }

    @Test
    fun `root provider writeBytes routes correctly`() = runBlocking {
        val dir = File(tempDir, "files").apply { mkdir() }
        val root = createRootProvider("files" to FileDataProvider(dir, emptyMap()))

        val result = root.writeBytes(Path.of("files/test.txt")) { out ->
            out.write("via root".toByteArray())
        }
        assertTrue(result.isSuccess)
        assertEquals("via root", File(dir, "test.txt").readText())
    }

    // -- delete tests --

    @Test
    fun `delete removes a file`() = runBlocking {
        File(tempDir, "doomed.txt").writeText("bye")

        val result = createProvider().delete(Path.of("doomed.txt"))
        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow())
        assertFalse(File(tempDir, "doomed.txt").exists())
    }

    @Test
    fun `delete removes a directory recursively`() = runBlocking {
        val sub = File(tempDir, "subdir").apply { mkdir() }
        File(sub, "a.txt").writeText("a")
        File(sub, "b.txt").writeText("b")

        val result = createProvider().delete(Path.of("subdir"))
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow() >= 3) // 2 files + directory
        assertFalse(sub.exists())
    }

    @Test
    fun `delete fails for nonexistent path`() = runBlocking {
        val result = createProvider().delete(Path.of("nope.txt"))
        assertTrue(result.isFailure)
        assertIs<NoSuchElementException>(result.exceptionOrNull())
        Unit
    }

    @Test
    fun `delete fails for path traversal`() = runBlocking {
        val result = createProvider().delete(Path.of("../../etc/passwd"))
        assertTrue(result.isFailure)
        assertIs<NoSuchElementException>(result.exceptionOrNull())
        Unit
    }

    @Test
    fun `delete invalidates cache`() = runBlocking {
        File(tempDir, "cached.txt").writeText("data")
        val provider = createProvider()

        // Prime cache
        val before = provider.getDirectoryForPath(Path.of("."), PageRequest()).getOrThrow()
        assertEquals(1, before.pagination.totalItems)

        // Delete
        provider.delete(Path.of("cached.txt"))

        // Cache should be invalidated
        val after = provider.getDirectoryForPath(Path.of("."), PageRequest()).getOrThrow()
        assertEquals(0, after.pagination.totalItems)
    }

    // -- rename tests --

    @Test
    fun `rename renames a file`() = runBlocking {
        File(tempDir, "old.txt").writeText("content")

        val result = createProvider().rename(Path.of("old.txt"), "new.txt")
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow())
        assertFalse(File(tempDir, "old.txt").exists())
        assertTrue(File(tempDir, "new.txt").exists())
        assertEquals("content", File(tempDir, "new.txt").readText())
    }

    @Test
    fun `rename renames a directory`() = runBlocking {
        val sub = File(tempDir, "olddir").apply { mkdir() }
        File(sub, "child.txt").writeText("hi")

        val result = createProvider().rename(Path.of("olddir"), "newdir")
        assertTrue(result.isSuccess)
        assertFalse(File(tempDir, "olddir").exists())
        assertTrue(File(tempDir, "newdir/child.txt").exists())
    }

    @Test
    fun `rename fails if destination already exists`() = runBlocking {
        File(tempDir, "a.txt").writeText("a")
        File(tempDir, "b.txt").writeText("b")

        val result = createProvider().rename(Path.of("a.txt"), "b.txt")
        assertTrue(result.isFailure)
        assertIs<IllegalArgumentException>(result.exceptionOrNull())
        // Original should still exist
        assertTrue(File(tempDir, "a.txt").exists())
        Unit
    }

    @Test
    fun `rename fails for nonexistent file`() = runBlocking {
        val result = createProvider().rename(Path.of("nope.txt"), "new.txt")
        assertTrue(result.isFailure)
        assertIs<NoSuchElementException>(result.exceptionOrNull())
        Unit
    }

    @Test
    fun `rename fails for path traversal`() = runBlocking {
        val result = createProvider().rename(Path.of("../../etc/passwd"), "evil.txt")
        assertTrue(result.isFailure)
        assertIs<NoSuchElementException>(result.exceptionOrNull())
        Unit
    }

    @Test
    fun `rename invalidates cache`() = runBlocking {
        File(tempDir, "before.txt").writeText("data")
        val provider = createProvider()

        // Prime cache
        val before = provider.getDirectoryForPath(Path.of("."), PageRequest()).getOrThrow()
        assertEquals(1, before.pagination.totalItems)
        assertEquals("before.txt", before.data[0].title)

        // Rename
        provider.rename(Path.of("before.txt"), "after.txt")

        // Cache should be invalidated
        val after = provider.getDirectoryForPath(Path.of("."), PageRequest()).getOrThrow()
        assertEquals(1, after.pagination.totalItems)
        assertEquals("after.txt", after.data[0].title)
    }

    // -- RootDataProvider delete/rename tests --

    @Test
    fun `root provider delete routes correctly`() = runBlocking {
        val dir = File(tempDir, "files").apply { mkdir() }
        File(dir, "target.txt").writeText("delete me")

        val root = createRootProvider("files" to FileDataProvider(dir, emptyMap()))

        val result = root.delete(Path.of("files/target.txt"))
        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow())
        assertFalse(File(dir, "target.txt").exists())
    }

    @Test
    fun `root provider rename routes correctly`() = runBlocking {
        val dir = File(tempDir, "files").apply { mkdir() }
        File(dir, "old.txt").writeText("rename me")

        val root = createRootProvider("files" to FileDataProvider(dir, emptyMap()))

        val result = root.rename(Path.of("files/old.txt"), "new.txt")
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow())
        assertFalse(File(dir, "old.txt").exists())
        assertTrue(File(dir, "new.txt").exists())
    }

    @Test
    fun `root provider delete fails for unknown root`() = runBlocking {
        val root = createRootProvider()
        val result = root.delete(Path.of("unknown/file.txt"))
        assertTrue(result.isFailure)
        assertIs<NoSuchElementException>(result.exceptionOrNull())
        Unit
    }

    @Test
    fun `root provider rename fails for unknown root`() = runBlocking {
        val root = createRootProvider()
        val result = root.rename(Path.of("unknown/file.txt"), "new.txt")
        assertTrue(result.isFailure)
        assertIs<NoSuchElementException>(result.exceptionOrNull())
        Unit
    }
}
