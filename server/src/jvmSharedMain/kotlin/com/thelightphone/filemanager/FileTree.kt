package com.thelightphone.filemanager

import com.thelightphone.filemanager.EntryType.Audio
import com.thelightphone.filemanager.EntryType.Directory
import com.thelightphone.filemanager.EntryType.GenericFile
import com.thelightphone.filemanager.EntryType.Image
import com.thelightphone.filemanager.EntryType.Text
import com.thelightphone.filemanager.EntryType.Video
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
import kotlin.collections.emptyMap
import kotlin.collections.orEmpty
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

// A node is either a LeafDataProvider (real file operations, no children of its own) or a
// BranchDataProvider (its own pages, no files of its own) — never both, so neither has to fake
// support for the other's methods.
sealed interface FileTree {
    // Drops any cached state. Distinct DataProvider instances can end up backed by overlapping
    // or identical physical storage (e.g. a directory exposed both as its own page and as one
    // root of a combined page); a write through one instance has no way to know which other
    // instances need to see it, so RootDataProvider invalidates everything on every successful
    // write rather than trying to detect the overlap.
    suspend fun invalidateCache() {}
}

interface LeafFileTree : FileTree {
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
    suspend fun getDirectoryMeta(directoryPath: Path): Result<DirectoryMeta>
    suspend fun notify(directoryPath: Path)

    // opportunity for data provider to tack on additional data
    fun appendMeta(entry: Entry): Entry = entry
}

interface BranchFileTree : FileTree {
    // Named children this provider wants to expose as their own pages. Each returned DataView's
    // spec must carry only its own local path segment, since RootDataProvider applies all
    // ancestor path-prefixing centrally as it walks.
    suspend fun getChildren(): List<DataView>
}

// A BranchDataProvider whose children are a fixed, precomputed list — used for statically
// declared branches (e.g. hand-built in Main.kt) as opposed to dynamically discovered ones.
class StaticBranchProvider(private val children: List<DataView>) : BranchFileTree {
    override suspend fun getChildren(): List<DataView> = children
}

class RootFileTree(
    private val childrenCacheTtl: Duration = 5.minutes,
    private val timeNow: () -> Instant = { Clock.System.now() },
    val getRootView: suspend () -> DataView,
) : LeafFileTree {
    private val refreshMutex = Mutex()

    @Volatile
    private var cachedRootView: DataView = DataView(RootViewSpec("root", emptyList()), StaticBranchProvider(emptyList()))

    private data class CachedChildren(val children: List<DataView>, val cachedAt: Instant)
    private val CachedChildren.isExpired: Boolean get() = timeNow() - cachedAt > childrenCacheTtl

    // Keyed by the joined path of the branch node whose children these are.
    private val childrenCache = ConcurrentHashMap<String, CachedChildren>()

    // Leaf providers `walk` has actually reached, so invalidateCache() can reach them without
    // forcing resolution of branches nobody has navigated into yet.
    private val resolvedLeafProviders: MutableSet<LeafFileTree> = ConcurrentHashMap.newKeySet()

    suspend fun refreshProviders() {
        refreshMutex.withLock {
            cachedRootView = getRootView()
            childrenCache.clear()
            resolvedLeafProviders.clear()
        }
    }

    private suspend fun awaitRefresh() {
        // If refresh is running, this will suspend until it completes.
        // If not, acquires and immediately releases.
        refreshMutex.withLock {}
    }

    private suspend fun childrenOf(provider: BranchFileTree, keyPath: String): List<DataView> {
        val cached = childrenCache[keyPath]?.takeUnless { it.isExpired }
        if (cached != null) return cached.children
        val fresh = provider.getChildren()
        childrenCache[keyPath] = CachedChildren(fresh, timeNow())
        return fresh
    }

    // Consumes path segments through branch nodes, calling getChildren() (TTL-cached) at each
    // level, stopping at the first leaf or when segments run out. Returns the node reached plus
    // whatever path segments remain unconsumed (the subPath for file ops).
    private suspend fun walk(path: Path): Result<Pair<DataView, Path>> {
        awaitRefresh()
        val normalized = path.normalize()
        // Path.of(".").normalize() collapses to the *empty* path (toString() == "", not "."),
        // and an empty path is still reported as having one (empty-string) name component —
        // check for emptiness directly rather than comparing against ".".
        val segments = if (normalized.toString().isEmpty()) {
            emptyList()
        } else {
            (0 until normalized.nameCount).map { normalized.getName(it).name }
        }

        var node = cachedRootView
        var i = 0
        while (i < segments.size) {
            val branchProvider = node.provider as? BranchFileTree ?: break
            val keyPath = segments.subList(0, i).joinToString("/")
            val next = childrenOf(branchProvider, keyPath).firstOrNull { it.spec.path == listOf(segments[i]) }
                ?: return Result.failure(NoSuchElementException("invalid path: ${segments[i]}"))
            node = next
            i++
        }

        val leafProvider = node.provider as? LeafFileTree
        if (leafProvider != null) {
            resolvedLeafProviders.add(leafProvider)
        }
        val remaining = if (i < segments.size) Path.of(segments.drop(i).joinToString("/")) else Path.of(".")
        return Result.success(node to remaining)
    }

    // The immediate children (one level) of whatever page the path resolves to. Fails if the
    // path doesn't fully resolve to a branch node (e.g. it's a leaf, or doesn't exist).
    suspend fun getChildrenAt(path: Path): Result<List<DataViewSpec>> {
        return walk(path).mapCatching { (node, remaining) ->
            val branchProvider = node.provider as? BranchFileTree
            if (branchProvider == null || remaining.toString() != ".") {
                throw NoSuchElementException("not a branch: $path")
            }
            val normalized = path.normalize()
            val prefix = if (normalized.toString().isEmpty()) {
                emptyList()
            } else {
                (0 until normalized.nameCount).map { normalized.getName(it).name }
            }
            childrenOf(branchProvider, prefix.joinToString("/")).map { it.spec.withPathPrefix(prefix) }
        }
    }

    private suspend fun <T> withProvider(
        path: Path,
        block: suspend (LeafFileTree, Path) -> Result<T>
    ): Result<T> {
        return walk(path).fold(
            onSuccess = { (node, remaining) ->
                val leafProvider = node.provider as? LeafFileTree
                if (leafProvider == null) {
                    Result.failure(NoSuchElementException("no data provider for root view"))
                } else {
                    block(leafProvider, remaining)
                }
            },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun invalidateCache() {
        childrenCache.clear()
        resolvedLeafProviders.forEach { it.invalidateCache() }
    }

    override suspend fun getDirectoryForPath(
        path: Path,
        pageRequest: PageRequest,
        invalidateCache: Boolean
    ): Result<PaginatedResponse<Entry>> {
        if (invalidateCache) refreshProviders()
        val normalized = path.normalize()
        return withProvider(path) { dataProvider, subPath ->
            dataProvider.getDirectoryForPath(subPath, pageRequest, invalidateCache).map { response ->
                response.copy(
                    data = response.data.map { entry ->
                        // Prefix with the full requested path, not just its first segment,
                        // so entries below the top level report their real absolute path.
                        entry.copy(path = "$normalized/${entry.path}")
                    }
                )
            }
        }
    }

    override suspend fun getBytes(filePath: Path): Result<InputStream> {
        return withProvider(filePath) { dataProvider, subPath ->
            dataProvider.getBytes(subPath)
        }
    }

    override suspend fun getThumbnailBytes(filePath: Path, type: EntryType): Result<InputStream> {
        return withProvider(filePath) { dataProvider, subPath ->
            dataProvider.getThumbnailBytes(subPath, type)
        }
    }

    override suspend fun notify(directoryPath: Path) {
        withProvider(directoryPath) { dataProvider, subPath ->
            Result.success(dataProvider.notify(subPath))
        }
    }

    override suspend fun getDirectoryMeta(directoryPath: Path): Result<DirectoryMeta> {
        return withProvider(directoryPath) { dataProvider, subPath ->
            dataProvider.getDirectoryMeta(subPath)
        }
    }

    override suspend fun checkWrite(filePath: Path): WriteCheck {
        return withProvider(filePath) { dataProvider, subPath ->
            Result.success(dataProvider.checkWrite(subPath))
        }.getOrElse { WriteCheck.InvalidPath }
    }

    override suspend fun <T> writeBytes(filePath: Path, block: suspend (OutputStream) -> T): Result<T> {
        return withProvider(filePath) { dataProvider, subPath ->
            dataProvider.writeBytes(subPath, block)
        }.also { if (it.isSuccess) invalidateCache() }
    }

    override suspend fun delete(filePath: Path): Result<Int> {
        return withProvider(filePath) { dataProvider, subPath ->
            dataProvider.delete(subPath)
        }.also { if (it.isSuccess) invalidateCache() }
    }

    override suspend fun rename(filePath: Path, newName: String): Result<Boolean> {
        return withProvider(filePath) { dataProvider, subPath ->
            dataProvider.rename(subPath, newName)
        }.also { if (it.getOrDefault(false)) invalidateCache() }
    }
}

abstract class CachingFileTree(
    private val readOnly: Boolean = false,
    private val showHiddenFiles: Boolean = false,
    private val cacheTtl: Duration = 5.minutes,
    private val timeNow: () -> Instant = { Clock.System.now() }
) : LeafFileTree {

    interface Cacheable {
        val cachedAt: Instant
    }

    protected data class CachedEntries (
        val fileEntries: List<Entry>,
        val directoryEntries: List<Entry>,
        override val cachedAt: Instant
    ): Cacheable

    private data class CachedMeta(
        val meta: Map<String, String>,
        val lastModified: Long,
        override val cachedAt: Instant
    ): Cacheable

    private val Cacheable.isExpired: Boolean get() = timeNow() - cachedAt > cacheTtl

    private val cache = ConcurrentHashMap<String, CachedEntries>()
    private val metaDataCache = ConcurrentHashMap<String, CachedMeta>()

    protected abstract fun listEntries(path: Path): Result<List<Entry>>
    protected abstract fun openRead(filePath: Path): Result<InputStream>
    protected abstract fun openWrite(filePath: Path): Result<WriteTarget>
    protected abstract fun performDelete(filePath: Path): Result<Int>
    protected abstract fun performRename(filePath: Path, newName: String): Result<Boolean>
    protected abstract fun performCheckWrite(filePath: Path): WriteCheck
    abstract override suspend fun getThumbnailBytes(filePath: Path, type: EntryType): Result<InputStream>

    protected open fun getMeta(entry: Entry): Map<String, String>? = null

    override fun appendMeta(entry: Entry): Entry {
        val cachedMeta = metaDataCache[entry.path]?.takeUnless { it.isExpired }
        if (cachedMeta != null && cachedMeta.lastModified == entry.lastModified) {
            return entry.copy(meta = cachedMeta.meta)
        }

        val meta = getMeta(entry)

        return if (meta == null) {
            entry
        } else {
            metaDataCache[entry.path] = CachedMeta(meta, entry.lastModified, Clock.System.now())
            entry.copy(meta = meta)
        }
    }

    override suspend fun checkWrite(filePath: Path): WriteCheck {
        return if (readOnly) WriteCheck.ReadOnly else performCheckWrite(filePath)
    }

    override suspend fun invalidateCache() {
        cache.clear()
        metaDataCache.clear()
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

    override suspend fun getDirectoryMeta(directoryPath: Path): Result<DirectoryMeta> {
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
                SortBy.KIND -> compareBy { it: Entry -> it.type.ordinal }
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

open class FileFileTree(
    protected val readRoots: List<File>,
    protected val writeRoot: File,
    private val defaultThumbnails: Map<EntryType, File>,
    readOnly: Boolean = false,
    showHiddenFiles: Boolean = false,
    cacheTtl: Duration = 5.minutes,
    timeNow: () -> Instant = { Clock.System.now() }
) : CachingFileTree(readOnly, showHiddenFiles, cacheTtl, timeNow) {

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
                // TODO append meta might not be necessary here
                WriteCheck.FileExists(resolved.toEntry(root.toPath(), this::appendMeta))
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

private fun Path.toEntry(rootPath: Path, appendMeta: (Entry) -> Entry): Entry {
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
    ).let(appendMeta)
}
