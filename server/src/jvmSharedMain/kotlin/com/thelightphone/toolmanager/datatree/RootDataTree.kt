package com.thelightphone.toolmanager.datatree

import com.thelightphone.toolmanager.BranchView
import com.thelightphone.toolmanager.DataView
import com.thelightphone.toolmanager.DataViewSpec
import com.thelightphone.toolmanager.DirectoryMeta
import com.thelightphone.toolmanager.Entry
import com.thelightphone.toolmanager.EntryType
import com.thelightphone.toolmanager.LeafView
import com.thelightphone.toolmanager.PageRequest
import com.thelightphone.toolmanager.PaginatedResponse
import com.thelightphone.toolmanager.RootViewSpec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.name
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class RootDataTree(
    private val childrenCacheTtl: Duration = 5.minutes,
    private val timeNow: () -> Instant = { Clock.System.now() },
    val getRootView: suspend () -> BranchView,
) : LeafDataTree {
    private val refreshMutex = Mutex()

    @Volatile
    private var cachedRootView: BranchView =
        BranchView(
            RootViewSpec("root", ""),
            StaticBranchProvider(emptyList())
        )

    private data class CachedChildren(val children: List<DataView<*>>, val cachedAt: Instant)

    private val CachedChildren.isExpired: Boolean get() = timeNow() - cachedAt > childrenCacheTtl

    // Keyed by the joined path of the branch node whose children these are.
    private val childrenCache = ConcurrentHashMap<String, CachedChildren>()

    // Leaf providers `walk` has actually reached, so invalidateCache() can reach them without
    // forcing resolution of branches nobody has navigated into yet.
    private val resolvedLeafProviders: MutableSet<LeafDataTree> = ConcurrentHashMap.newKeySet()

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

    private suspend fun childrenOf(provider: BranchDataTree, keyPath: String): List<DataView<*>> {
        val cached = childrenCache[keyPath]?.takeUnless { it.isExpired }
        if (cached != null) return cached.children
        val fresh = provider.getChildren()
        childrenCache[keyPath] = CachedChildren(fresh, timeNow())
        return fresh
    }

    // Consumes path segments through branch nodes, calling getChildren() (TTL-cached) at each
    // level, stopping at the first leaf or when segments run out. Returns the node reached plus
    // whatever path segments remain unconsumed (the subPath for file ops). Unless showHidden is
    // true, a segment matching an isHidden node is treated the same as a segment that matches
    // nothing.
    private suspend fun walk(path: Path, showHidden: Boolean = true): Result<Pair<DataView<*>, Path>> {
        awaitRefresh()
        val normalized = path.normalize()
        // Path.of(".").normalize() collapses to empty path
        val segments = if (normalized.toString().isEmpty()) {
            emptyList()
        } else {
            (0 until normalized.nameCount).map { normalized.getName(it).name }
        }

        var node: DataView<*> = cachedRootView
        var i = 0
        while (i < segments.size) {
            val branchNode = node as? BranchView ?: break
            val keyPath = segments.subList(0, i).joinToString("/")
            val next = childrenOf(branchNode.provider, keyPath)
                .filter { showHidden || !it.isHidden }
                .firstOrNull { it.spec.path == segments[i] }
                ?: return Result.failure(NoSuchElementException("invalid path: ${segments[i]}"))
            node = next
            i++
        }

        val leafProvider = (node as? LeafView)?.provider
        if (leafProvider != null) {
            resolvedLeafProviders.add(leafProvider)
        }
        val remaining =
            if (i < segments.size) Path.of(segments.drop(i).joinToString("/")) else Path.of(".")
        return Result.success(node to remaining)
    }

    // The immediate children (one level) of whatever page the path resolves to. Fails if the
    // path doesn't fully resolve to a branch node (e.g. it's a leaf, or doesn't exist). showHidden
    // defaults to false since this is what drives the app's own tree navigation (GET /api/tree) —
    // any other caller that wants hidden branches included can pass showHidden = true explicitly.
    suspend fun getChildrenAt(path: Path, showHidden: Boolean = false): Result<List<DataViewSpec>> {
        return walk(path, showHidden).mapCatching { (node, remaining) ->
            val branchNode = node as? BranchView
            if (branchNode == null || remaining.toString() != ".") {
                throw NoSuchElementException("not a branch: $path")
            }
            val normalized = path.normalize()
            val prefix = if (normalized.toString().isEmpty()) {
                ""
            } else {
                (0 until normalized.nameCount).joinToString("/") { normalized.getName(it).name }
            }
            childrenOf(branchNode.provider, prefix)
                .filter { showHidden || !it.isHidden }
                .map { it.spec.withPathPrefix(prefix) }
        }
    }

    private suspend fun <T> withProvider(
        path: Path,
        block: suspend (LeafDataTree, Path) -> Result<T>
    ): Result<T> {
        return walk(path).fold(
            onSuccess = { (node, remaining) ->
                val leafNode = node as? LeafView
                if (leafNode == null) {
                    Result.failure(NoSuchElementException("no data provider for root view"))
                } else {
                    block(leafNode.provider, remaining)
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
            dataProvider.getDirectoryForPath(subPath, pageRequest, invalidateCache)
                .map { response ->
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

    override suspend fun <T> writeBytes(
        filePath: Path,
        block: suspend (OutputStream) -> T
    ): Result<T> {
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
