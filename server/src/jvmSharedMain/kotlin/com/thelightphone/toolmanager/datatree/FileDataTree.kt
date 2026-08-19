package com.thelightphone.toolmanager.datatree

import com.thelightphone.toolmanager.Entry
import com.thelightphone.toolmanager.EntryType
import io.ktor.server.plugins.NotFoundException
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.isDirectory
import kotlin.io.path.isReadable
import kotlin.io.path.name
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

open class FileDataTree(
    protected val readRoots: List<File>,
    protected val writeRoot: File,
    private val defaultThumbnails: Map<EntryType, File>,
    readOnly: Boolean = false,
    showHiddenFiles: Boolean = false,
    cacheTtl: Duration = 5.minutes,
    timeNow: () -> Instant = { Clock.System.now() },
) : CachingDataTree(readOnly, showHiddenFiles, cacheTtl, timeNow) {

    constructor(
        root: File,
        defaultThumbnails: Map<EntryType, File>,
        readOnly: Boolean = false,
        showHiddenFiles: Boolean = false,
        cacheTtl: Duration = 5.minutes,
        timeNow: () -> Instant = { Clock.System.now() },
    ) : this(
        listOf(root),
        root,
        defaultThumbnails,
        readOnly,
        showHiddenFiles,
        cacheTtl,
        timeNow,
    )

    private fun resolveAndValidate(path: Path, root: File): Result<Path> {
        val resolved = try {
            root.toPath().resolve(path).toRealPath()
        } catch (_: java.nio.file.NoSuchFileException) {
            return Result.failure(NoSuchElementException("Path not found: $path"))
        }
        if (!resolved.startsWith(root.toPath().toRealPath())) {
            return Result.failure(SecurityException("Path traversal not allowed"))
        }
        if (!resolved.isReadable()) {
            return Result.failure(NoSuchElementException("Path not found: $path"))
        }
        return Result.success(resolved)
    }

    // Reads can come from any source root. the first root (in order) that has the
    // path wins. Used for everything except new-file writes, which always target writeRoot.
    private fun findInReadRoots(path: Path): Result<Pair<Path, File>> {
        var lastFailure: Throwable = NoSuchElementException("Path not found: $path")
        for (root in readRoots) {
            val result = resolveAndValidate(path, root)
            if (result.isSuccess) return result.map { it to root }
            lastFailure = result.exceptionOrNull() ?: lastFailure
        }
        return Result.failure(lastFailure)
    }

    override fun listEntries(path: Path): Result<List<Entry>> {
        var anySuccess = false
        var lastFailure: Throwable = NoSuchElementException("Path not found: $path")
        // Merge listings from every root that has this subpath, first root wins on name collisions.
        val merged = LinkedHashMap<String, Entry>()
        for (root in readRoots) {
            resolveAndValidate(path, root).mapCatching { dir ->
                if (!dir.isDirectory()) {
                    throw IllegalArgumentException("Not a directory: $path")
                }
                Files.list(dir).use { stream ->
                    stream
                        .filter { !Files.isSymbolicLink(it) }
                        .map { it.toEntry(root.toPath(), this::appendMeta) }
                        .toList()
                }
            }.onSuccess { entries ->
                anySuccess = true
                // Keyed by title (not entry.path): all entries here are siblings within the
                // same listed directory, so the filename alone identifies a collision.
                entries.forEach { entry -> merged.putIfAbsent(entry.title, entry) }
            }.onFailure { lastFailure = it }
        }
        return if (anySuccess) Result.success(merged.values.toList()) else Result.failure(
            lastFailure
        )
    }

    override fun openRead(filePath: Path): Result<InputStream> {
        return findInReadRoots(filePath).mapCatching { (resolved, _) ->
            if (resolved.isDirectory()) {
                throw IllegalArgumentException("Cannot read bytes from a directory: $filePath")
            }
            Files.newInputStream(resolved)
        }
    }

    override fun openWrite(filePath: Path): Result<WriteTarget> = runCatching {
        val resolved = writeRoot.toPath().resolve(filePath).normalize()
        // The parent may only exist virtually in another source root at this point,
        // since checkWrite treats those as valid write targets too.
        Files.createDirectories(resolved.parent)
        // Write to a temp file at first, so we can rollback an incomplete file
        val tempFile = Files.createTempFile(resolved.parent, "${resolved.fileName}.", ".part")
        WriteTarget(
            outputStream = Files.newOutputStream(tempFile, StandardOpenOption.TRUNCATE_EXISTING),
            onValidate = { validateFile(resolved, tempFile) },
            onCommit = {
                Files.move(
                    tempFile,
                    resolved,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            },
            onRollback = { Files.deleteIfExists(tempFile) }
        )
    }

    protected open fun getImageThumbnail(filePath: Path): Result<InputStream>? {
        // Android implementation can handle this on its own
        return null
    }

    protected open fun getVideoThumbnail(filePath: Path): Result<InputStream>? {
        // Android implementation can handle this on its own
        return null
    }

    private val EntryType.default: Result<InputStream>
        get() {
            return defaultThumbnails[this]?.inputStream()?.let { Result.success(it) }
                ?: Result.failure(NotFoundException())
        }

    override suspend fun getThumbnailBytes(filePath: Path, type: EntryType): Result<InputStream> {
        return when (type) {
            EntryType.Image -> getImageThumbnail(filePath) ?: getBytes(filePath)
            EntryType.Video -> getVideoThumbnail(filePath) ?: type.default
            EntryType.Directory,
            EntryType.GenericFile,
            EntryType.Audio,
            EntryType.Text -> {
                type.default
            }
        }
    }

    override fun performDelete(filePath: Path): Result<Int> {
        // Deletes operate on whichever source root actually holds the file, not just writeRoot.
        return findInReadRoots(filePath).mapCatching { (resolved, _) ->
            if (resolved.isDirectory()) {
                resolved.toFile().walkBottomUp().count { it.delete() }
            } else {
                if (Files.deleteIfExists(resolved)) 1 else 0
            }
        }
    }

    override fun performRename(filePath: Path, newName: String): Result<Boolean> {
        // Renames stay within whichever source root the file was found in.
        return findInReadRoots(filePath).mapCatching { (resolved, root) ->
            val dest = resolved.resolveSibling(newName)
            if (!dest.parent.startsWith(root.toPath().toRealPath())) {
                throw SecurityException("Path traversal not allowed")
            }
            if (Files.exists(dest)) {
                throw IllegalArgumentException("Destination already exists: $newName")
            }
            Files.move(resolved, dest) != null
        }
    }

    override fun performCheckWrite(filePath: Path): WriteCheck {
        val writeRootPath = writeRoot.toPath().normalize()
        val normalized = writeRootPath.resolve(filePath).normalize()
        if (!normalized.startsWith(writeRootPath)) {
            return WriteCheck.InvalidPath
        }
        val parent = normalized.parent ?: return WriteCheck.InvalidPath
        // The parent is a valid write target if it exists in writeRoot already, or if it
        // exists virtually via one of the other source roots (openWrite will create it).
        val parentExists = Files.isDirectory(parent) || readRoots.any { root ->
            resolveAndValidate(writeRootPath.relativize(parent), root).getOrNull()
                ?.isDirectory() == true
        }
        if (!parentExists) {
            return WriteCheck.InvalidPath
        }
        findInReadRoots(filePath).getOrNull()?.let { (resolved, root) ->
            return if (resolved.isDirectory()) {
                WriteCheck.DirectoryExists
            } else {
                // TODO append meta might not be necessary here
                WriteCheck.FileExists(resolved.toEntry(root.toPath(), this::appendMeta))
            }
        }
        return WriteCheck.Safe
    }
}

private val Path.entryType: EntryType
    get() {
        if (isDirectory()) return EntryType.Directory
        return entryTypeForName(name)
    }

private fun Path.toEntry(rootPath: Path, appendMeta: (Entry) -> Entry): Entry {
    val attrs = Files.readAttributes(this, java.nio.file.attribute.BasicFileAttributes::class.java)
    // `this` is always a resolved real path (see resolveAndValidate), so rootPath must be
    // resolved too before relativizing
    return Entry(
        type = entryType,
        title = name,
        path = rootPath.toRealPath().relativize(this).toString(),
        lastModified = attrs.lastModifiedTime().toMillis(),
        size = if (attrs.isDirectory) 0L else attrs.size()
    ).let(appendMeta)
}
