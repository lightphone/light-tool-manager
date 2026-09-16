package com.thelightphone.toolmanager.datatree

import com.thelightphone.toolmanager.DirectoryMeta
import com.thelightphone.toolmanager.Entry
import com.thelightphone.toolmanager.EntryType
import com.thelightphone.toolmanager.PageRequest
import com.thelightphone.toolmanager.PaginatedResponse
import com.thelightphone.toolmanager.PaginationInfo
import com.thelightphone.toolmanager.SortBy
import com.thelightphone.toolmanager.SortOrder
import io.ktor.server.application.InvalidBodyException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

abstract class CachingDataTree(
    private val readOnly: Boolean = false,
    private val showHiddenFiles: Boolean = false,
    private val cacheTtl: Duration = 5.minutes,
    private val timeNow: () -> Instant = { Clock.System.now() },
) : LeafDataTree {

    interface Cacheable {
        val cachedAt: Instant
    }

    protected data class CachedEntries(
        val fileEntries: List<Entry>,
        val directoryEntries: List<Entry>,
        override val cachedAt: Instant
    ) : Cacheable

    private data class CachedMeta(
        val meta: Map<String, String>,
        val lastModified: Long,
        override val cachedAt: Instant
    ) : Cacheable

    // no entries should be directories!
    private data class CachedFlatEntries(
        val entries: List<Entry>,
        override val cachedAt: Instant
    ) : Cacheable

    private val Cacheable.isExpired: Boolean get() = timeNow() - cachedAt > cacheTtl

    private val cache = ConcurrentHashMap<String, CachedEntries>()
    private val flattenCache = ConcurrentHashMap<String, CachedFlatEntries>()
    private val metaDataCache = ConcurrentHashMap<String, CachedMeta>()

    protected abstract fun listEntries(path: Path): Result<List<Entry>>
    protected abstract fun openRead(filePath: Path): Result<InputStream>
    protected abstract fun openWrite(filePath: Path): Result<WriteTarget>
    protected abstract fun performDelete(filePath: Path): Result<Int>
    protected abstract fun performRename(filePath: Path, newName: String): Result<Boolean>
    protected abstract fun performCheckWrite(filePath: Path): WriteCheck
    abstract override suspend fun getThumbnailBytes(
        filePath: Path,
        type: EntryType
    ): Result<InputStream>

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
        flattenCache.clear()
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
        // hard to tell if something in the flattenCache is affected, so just clear it
        // we don't expect to use this too often?
        flattenCache.clear()
    }

    override suspend fun getDirectoryMeta(directoryPath: Path): Result<DirectoryMeta> {
        // subclasses can override if subdirectory fidelity needed
        return Result.success(DirectoryMeta(readOnly))
    }

    // Cache-populate-or-hit for a single directory level
    private fun getOrFetchEntries(path: Path, invalidateCache: Boolean): Result<CachedEntries> {
        val cacheKey = normalizeCacheKey(path)
        if (invalidateCache) {
            cache.remove(cacheKey)
        }

        val cached = cache[cacheKey]?.takeUnless { it.isExpired }
        return cached?.let { Result.success(it) }
            ?: listEntries(path).map { entries ->
                val filtered = entries.filter { showHiddenFiles || !it.title.startsWith(".") }
                val grouped = filtered.groupBy { it.type == EntryType.Directory }
                CachedEntries(
                    fileEntries = grouped[false] ?: emptyList(),
                    directoryEntries = grouped[true] ?: emptyList(),
                    cachedAt = timeNow()
                ).also { cache[cacheKey] = it }
            }
    }

    // Recursively walks `path`, returning every file beneath it (directories themselves are never included)
    private fun getFlattenedEntries(path: Path, invalidateCache: Boolean): Result<List<Entry>> {
        val cacheKey = normalizeCacheKey(path)
        if (invalidateCache) {
            flattenCache.remove(cacheKey)
        }

        val cached = flattenCache[cacheKey]?.takeUnless { it.isExpired }
        if (cached != null) {
            return Result.success(cached.entries)
        }

        return getOrFetchEntries(path, invalidateCache).mapCatching { entries ->
            entries.fileEntries + entries.directoryEntries.flatMap { dir ->
                getFlattenedEntries(Path.of(dir.path), invalidateCache).getOrThrow()
            }
        }.onSuccess { flat ->
            flattenCache[cacheKey] = CachedFlatEntries(flat, cachedAt = timeNow())
        }
    }

    override suspend fun getDirectoryForPath(
        path: Path,
        pageRequest: PageRequest,
        invalidateCache: Boolean
    ): Result<PaginatedResponse<Entry>> {
        val comparator = when (pageRequest.sortBy) {
            SortBy.DATE -> compareBy { it: Entry -> it.lastModified }
            SortBy.SIZE -> compareBy { it: Entry -> it.size }
            SortBy.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it: Entry -> it.title }
            SortBy.KIND -> compareBy { it: Entry -> it.type.ordinal }
        }.let { if (pageRequest.sortOrder == SortOrder.DESC) it.reversed() else it }

        val allEntries: Result<List<Entry>> = if (pageRequest.flatten) {
            getFlattenedEntries(path, invalidateCache).map { it.sortedWith(comparator) }
        } else {
            getOrFetchEntries(path, invalidateCache).map { entries ->
                entries.directoryEntries.sortedWith(comparator) +
                        entries.fileEntries.sortedWith(comparator)
            }
        }

        return allEntries.map { sorted ->
            val totalItems = sorted.size
            val totalPages =
                if (totalItems == 0) 1 else (totalItems + pageRequest.size - 1) / pageRequest.size
            val startIndex = pageRequest.offset
            val endIndex = minOf(startIndex + pageRequest.size, totalItems)
            val pageData =
                if (startIndex < totalItems) sorted.subList(startIndex, endIndex) else emptyList()

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

    override suspend fun <T> writeBytes(
        filePath: Path,
        block: suspend (OutputStream) -> T
    ): Result<T> {
        return when (checkWrite(filePath)) {
            WriteCheck.InvalidPath -> Result.failure(SecurityException("Invalid path: $filePath"))
            WriteCheck.DirectoryExists -> Result.failure(IllegalArgumentException("Cannot overwrite directory: $filePath"))
            WriteCheck.ReadOnly -> Result.failure(SecurityException("Path is read-only: $filePath"))
            WriteCheck.Safe, is WriteCheck.FileExists -> {
                invalidateParentCache(filePath)
                openWrite(filePath).fold(
                    onSuccess = { target ->
                        runCatching { target.outputStream.use { block(it) } }
                            .fold(onSuccess = {
                                if (target.tryCommit()) {
                                    Result.success(it)
                                } else {
                                    Result.failure(InvalidBodyException("This file failed validation"))
                                }
                            }, onFailure = {
                                target.rollback()
                                Result.failure(it)
                            })
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
