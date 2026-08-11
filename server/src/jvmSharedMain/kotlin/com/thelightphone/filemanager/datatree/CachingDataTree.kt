package com.thelightphone.filemanager.datatree

import com.thelightphone.filemanager.DirectoryMeta
import com.thelightphone.filemanager.Entry
import com.thelightphone.filemanager.EntryType
import com.thelightphone.filemanager.PageRequest
import com.thelightphone.filemanager.PaginatedResponse
import com.thelightphone.filemanager.PaginationInfo
import com.thelightphone.filemanager.SortBy
import com.thelightphone.filemanager.SortOrder
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

    private val Cacheable.isExpired: Boolean get() = timeNow() - cachedAt > cacheTtl

    private val cache = ConcurrentHashMap<String, CachedEntries>()
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
