package com.thelightphone.filemanager

import io.ktor.server.plugins.NotFoundException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.isDirectory
import kotlin.io.path.isReadable
import kotlin.io.path.name
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

sealed interface WriteCheck {
    data object Safe : WriteCheck
    data class FileExists(val existing: Entry) : WriteCheck
    data object DirectoryExists : WriteCheck
    data object InvalidPath : WriteCheck
    data object ReadOnly : WriteCheck
}

// Lets a DataProvider stage a write and only make it visible (commit) once the
// caller-supplied block finishes successfully, so an aborted upload can roll
// back instead of leaving partial data at the destination path.
class WriteTarget(
    val outputStream: OutputStream,
    private val onCommit: () -> Unit = {},
    private val onRollback: () -> Unit = {}
) {
    fun commit() = onCommit()
    fun rollback() = onRollback()
}

interface DataProvider {
    suspend fun getDirectoryForPath(
        path: Path,
        pageRequest: PageRequest,
        invalidateCache: Boolean = false
    ): Result<PaginatedResponse<Entry>>

    suspend fun getBytes(filePath: Path): Result<InputStream>
    suspend fun getThumbnailBytes(filePath: Path, type: EntryType): Result<InputStream>
    suspend fun checkWrite(filePath: Path): WriteCheck
    suspend fun <T> writeBytes(filePath: Path, block: suspend (OutputStream) -> T): Result<T>
    suspend fun delete(filePath: Path): Result<Int>
    suspend fun rename(filePath: Path, newName: String): Result<Boolean>
    suspend fun getMeta(directoryPath: Path): Result<DirectoryMeta>

    suspend fun notify(directoryPath: Path)
}

class RootDataProvider(
    val getDataProviders: suspend () -> Map<String, DataProvider>,
) : DataProvider {
    private val refreshMutex = Mutex()

    @Volatile
    var cachedDataProviders: Map<String, DataProvider> = emptyMap()

    suspend fun refreshProviders() {
        refreshMutex.withLock {
            cachedDataProviders = getDataProviders()
        }
    }

    private suspend fun awaitRefresh() {
        // If refresh is running, this will suspend until it completes.
        // If not, acquires and immediately releases.
        refreshMutex.withLock {}
    }

    suspend fun getRoot(): Root {
        awaitRefresh()
        return Root(cachedDataProviders.keys.toList())
    }

    private suspend fun <T> dataProviderForPath(
        path: Path,
        block: suspend (DataProvider, Path) -> Result<T>
    ): Result<T> {
        awaitRefresh()
        val normalized = path.normalize()
        val pathRoot = normalized.getName(0).name
        return cachedDataProviders[pathRoot]?.let {
            val subPath = if (normalized.nameCount > 1) {
                normalized.subpath(1, normalized.nameCount)
            } else {
                Path.of(".")
            }
            block(it, subPath)
        } ?: Result.failure(NoSuchElementException("invalid root: $pathRoot"))
    }

    override suspend fun getDirectoryForPath(
        path: Path,
        pageRequest: PageRequest,
        invalidateCache: Boolean
    ): Result<PaginatedResponse<Entry>> {
        if (invalidateCache) refreshProviders()
        val normalized = path.normalize()
        val pathRoot = normalized.getName(0).name
        return dataProviderForPath(path) { dataProvider, subPath ->
            dataProvider.getDirectoryForPath(subPath, pageRequest, invalidateCache).map { response ->
                response.copy(
                    data = response.data.map { entry ->
                        entry.copy(path = "$pathRoot/${entry.path}")
                    }
                )
            }
        }
    }

    override suspend fun getBytes(filePath: Path): Result<InputStream> {
        return dataProviderForPath(filePath) { dataProvider, subPath ->
            dataProvider.getBytes(subPath)
        }
    }

    override suspend fun getThumbnailBytes(filePath: Path, type: EntryType): Result<InputStream> {
        return dataProviderForPath(filePath) { dataProvider, subPath ->
            dataProvider.getThumbnailBytes(subPath, type)
        }
    }

    override suspend fun notify(directoryPath: Path) {
        dataProviderForPath(directoryPath) { dataProvider, subPath ->
            Result.success(dataProvider.notify(subPath))
        }
    }

    override suspend fun getMeta(directoryPath: Path): Result<DirectoryMeta> {
        return dataProviderForPath(directoryPath) { dataProvider, subPath ->
            dataProvider.getMeta(subPath)
        }
    }

    override suspend fun checkWrite(filePath: Path): WriteCheck {
        return dataProviderForPath(filePath) { dataProvider, subPath ->
            Result.success(dataProvider.checkWrite(subPath))
        }.getOrElse { WriteCheck.InvalidPath }
    }

    override suspend fun <T> writeBytes(filePath: Path, block: suspend (OutputStream) -> T): Result<T> {
        return dataProviderForPath(filePath) { dataProvider, subPath ->
            dataProvider.writeBytes(subPath, block)
        }
    }

    override suspend fun delete(filePath: Path): Result<Int> {
        return dataProviderForPath(filePath) { dataProvider, subPath ->
            dataProvider.delete(subPath)
        }
    }

    override suspend fun rename(filePath: Path, newName: String): Result<Boolean> {
        return dataProviderForPath(filePath) { dataProvider, subPath ->
            dataProvider.rename(subPath, newName)
        }
    }
}

abstract class CachingDataProvider(
    private val readOnly: Boolean = false,
    private val showHiddenFiles: Boolean = false,
    private val cacheTtl: Duration = 5.minutes,
    private val timeNow: () -> Instant = { Clock.System.now() }
) : DataProvider {

    protected data class CachedEntries(
        val fileEntries: List<Entry>,
        val directoryEntries: List<Entry>,
        val cachedAt: Instant
    )

    private val CachedEntries.isExpired: Boolean get() = timeNow() - cachedAt > cacheTtl

    private val cache = ConcurrentHashMap<String, CachedEntries>()

    protected abstract fun listEntries(path: Path): Result<List<Entry>>
    protected abstract fun openRead(filePath: Path): Result<InputStream>
    protected abstract fun openWrite(filePath: Path): Result<WriteTarget>
    protected abstract fun performDelete(filePath: Path): Result<Int>
    protected abstract fun performRename(filePath: Path, newName: String): Result<Boolean>
    protected abstract fun performCheckWrite(filePath: Path): WriteCheck
    abstract override suspend fun getThumbnailBytes(filePath: Path, type: EntryType): Result<InputStream>

    override suspend fun checkWrite(filePath: Path): WriteCheck {
        return if (readOnly) WriteCheck.ReadOnly else performCheckWrite(filePath)
    }

    override suspend fun notify(directoryPath: Path) {
        // No-op by default
    }

    protected fun normalizeCacheKey(path: Path): String {
        val str = path.normalize().toString()
        return str.ifEmpty { "." }
    }

    protected fun invalidateParentCache(filePath: Path) {
        val parentKey = normalizeCacheKey(filePath.parent ?: Path.of("."))
        cache.remove(parentKey)
    }

    override suspend fun getMeta(directoryPath: Path): Result<DirectoryMeta> {
        // subclasses can override if subdirectory fidelity needed
        return Result.success(DirectoryMeta(readOnly))
    }

    override suspend fun getDirectoryForPath(
        path: Path,
        pageRequest: PageRequest,
        invalidateCache: Boolean
    ): Result<PaginatedResponse<Entry>> {
        val cacheKey = normalizeCacheKey(path)
        if (invalidateCache) {
            cache.remove(cacheKey)
        }

        val cached = cache[cacheKey]?.takeUnless { it.isExpired }
        val allEntries = cached?.let { Result.success(it) }
            ?: listEntries(path).map { entries ->
                val filtered = entries.filter { showHiddenFiles || !it.title.startsWith(".") }
                val grouped = filtered.groupBy { it.type == EntryType.Directory }
                CachedEntries(
                    fileEntries = grouped[false] ?: emptyList(),
                    directoryEntries = grouped[true] ?: emptyList(),
                    cachedAt = timeNow()
                ).also { cache[cacheKey] = it }
            }

        return allEntries.map { entries ->
            val comparator = when (pageRequest.sortBy) {
                SortBy.DATE -> compareBy { it.lastModified }
                SortBy.SIZE -> compareBy { it.size }
                SortBy.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it: Entry -> it.title }
            }.let { if (pageRequest.sortOrder == SortOrder.DESC) it.reversed() else it }

            val sorted = entries.directoryEntries.sortedWith(comparator) +
                    entries.fileEntries.sortedWith(comparator)

            val totalItems = sorted.size
            val totalPages = if (totalItems == 0) 1 else (totalItems + pageRequest.size - 1) / pageRequest.size
            val startIndex = pageRequest.offset
            val endIndex = minOf(startIndex + pageRequest.size, totalItems)
            val pageData = if (startIndex < totalItems) sorted.subList(startIndex, endIndex) else emptyList()

            PaginatedResponse(
                data = pageData,
                pagination = PaginationInfo(
                    currentPage = pageRequest.page,
                    totalPages = totalPages,
                    pageSize = pageRequest.size,
                    totalItems = totalItems,
                    hasNext = pageRequest.page < totalPages,
                    hasPrevious = pageRequest.page > 1
                )
            )
        }
    }

    override suspend fun getBytes(filePath: Path): Result<InputStream> = openRead(filePath)

    override suspend fun <T> writeBytes(filePath: Path, block: suspend (OutputStream) -> T): Result<T> {
        return when (checkWrite(filePath)) {
            WriteCheck.InvalidPath -> Result.failure(SecurityException("Invalid path: $filePath"))
            WriteCheck.DirectoryExists -> Result.failure(IllegalArgumentException("Cannot overwrite directory: $filePath"))
            WriteCheck.ReadOnly -> Result.failure(SecurityException("Path is read-only: $filePath"))
            WriteCheck.Safe, is WriteCheck.FileExists -> {
                invalidateParentCache(filePath)
                openWrite(filePath).fold(
                    onSuccess = { target ->
                        runCatching { target.outputStream.use { block(it) } }
                            .onSuccess { target.commit() }
                            .onFailure { target.rollback() }
                    },
                    onFailure = { Result.failure(it) }
                )
            }
        }
    }

    override suspend fun delete(filePath: Path): Result<Int> {
        if (readOnly) return Result.failure(SecurityException("Path is read-only: $filePath"))
        return performDelete(filePath).also { result ->
            if (result.isSuccess) {
                invalidateParentCache(filePath)
                // Also invalidate the entry itself in case it was a cached directory
                val cacheKey = normalizeCacheKey(filePath)
                cache.remove(cacheKey)
            }
        }
    }

    override suspend fun rename(filePath: Path, newName: String): Result<Boolean> {
        if (readOnly) return Result.failure(SecurityException("Path is read-only: $filePath"))
        return performRename(filePath, newName).also { result ->
            if (result.getOrDefault(false)) {
                invalidateParentCache(filePath)
                val cacheKey = normalizeCacheKey(filePath)
                cache.remove(cacheKey)
            }
        }
    }
}

open class FileDataProvider(
    protected val readRoots: List<File>,
    protected val writeRoot: File,
    private val defaultThumbnails: Map<EntryType, File>,
    readOnly: Boolean = false,
    showHiddenFiles: Boolean = false,
    cacheTtl: Duration = 5.minutes,
    timeNow: () -> Instant = { Clock.System.now() }
) : CachingDataProvider(readOnly, showHiddenFiles, cacheTtl, timeNow) {

    constructor(
        root: File,
        defaultThumbnails: Map<EntryType, File>,
        readOnly: Boolean = false,
        showHiddenFiles: Boolean = false,
        cacheTtl: Duration = 5.minutes,
        timeNow: () -> Instant = { Clock.System.now() }
    ) : this(listOf(root), root, defaultThumbnails, readOnly, showHiddenFiles, cacheTtl, timeNow)

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

    // Reads can come from any source root; the first root (in order) that has the
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
        // Merge listings from every root that has this subpath; first root wins on name collisions.
        val merged = LinkedHashMap<String, Entry>()
        for (root in readRoots) {
            resolveAndValidate(path, root).mapCatching { dir ->
                if (!dir.isDirectory()) {
                    throw IllegalArgumentException("Not a directory: $path")
                }
                Files.list(dir).use { stream ->
                    stream
                        .filter { !Files.isSymbolicLink(it) }
                        .map { it.toEntry(root.toPath()) }
                        .toList()
                }
            }.onSuccess { entries ->
                anySuccess = true
                // Keyed by title (not entry.path): all entries here are siblings within the
                // same listed directory, so the filename alone identifies a collision.
                entries.forEach { entry -> merged.putIfAbsent(entry.title, entry) }
            }.onFailure { lastFailure = it }
        }
        return if (anySuccess) Result.success(merged.values.toList()) else Result.failure(lastFailure)
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
        // The parent may only exist virtually (in another source root) at this point,
        // since checkWrite treats those as valid write targets too.
        Files.createDirectories(resolved.parent)
        // Write to a temp file in the same directory and only move it into place once
        // the upload finishes successfully so an aborted upload never leaves a
        // truncated/partial file at the destination path.
        val tempFile = Files.createTempFile(resolved.parent, "${resolved.fileName}.", ".part")
        WriteTarget(
            outputStream = Files.newOutputStream(tempFile, StandardOpenOption.TRUNCATE_EXISTING),
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

    private val EntryType.default: Result<InputStream> get() {
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
            resolveAndValidate(writeRootPath.relativize(parent), root).getOrNull()?.isDirectory() == true
        }
        if (!parentExists) {
            return WriteCheck.InvalidPath
        }
        findInReadRoots(filePath).getOrNull()?.let { (resolved, root) ->
            return if (resolved.isDirectory()) {
                WriteCheck.DirectoryExists
            } else {
                WriteCheck.FileExists(resolved.toEntry(root.toPath()))
            }
        }
        return WriteCheck.Safe
    }
}

val extensionTypeMap = listOf(
    setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "tiff", "tif") to EntryType.Image,
    setOf("mp4", "mov", "avi", "mkv", "webm", "m4v") to EntryType.Video,
    setOf("mp3", "wav", "aac", "flac", "ogg", "m4a", "wma") to EntryType.Audio,
    setOf("txt", "md", "csv", "json", "xml", "html", "css", "js", "kt", "java", "py", "sh", "yml", "yaml", "toml", "log") to EntryType.Text
).fold(mutableMapOf<String, EntryType>()) { acc, (extensions, entryType) ->
    acc.apply { putAll(extensions.associateWith { entryType }) }
}

fun entryTypeForName(name: String): EntryType {
    val ext = name.substringAfterLast('.', "").lowercase()
    return extensionTypeMap[ext] ?: EntryType.GenericFile
}

private val Path.entryType: EntryType
    get() {
        if (isDirectory()) return EntryType.Directory
        return entryTypeForName(name)
    }

private fun Path.toEntry(rootPath: Path): Entry {
    val attrs = Files.readAttributes(this, java.nio.file.attribute.BasicFileAttributes::class.java)
    // `this` is always a resolved real path (see resolveAndValidate), so rootPath must be
    // resolved too before relativizing, or a symlinked root (e.g. macOS's /tmp) produces
    // a garbled relative path instead of a clean one.
    return Entry(
        type = entryType,
        title = name,
        path = rootPath.toRealPath().relativize(this).toString(),
        lastModified = attrs.lastModifiedTime().toMillis(),
        size = if (attrs.isDirectory) 0L else attrs.size()
    )
}
