package com.thelightphone.toolmanager

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import com.thelightphone.toolmanager.datatree.BranchDataTree
import com.thelightphone.toolmanager.datatree.StaticBranchProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.time.Duration.Companion.seconds

// Server should add this to its tree, automatically searches installed tools for
// compatible remote DataTrees
class DiscoveredToolsBranchProvider(
    private val context: Context,
    private val logger: Logger
) : BranchDataTree {

    override suspend fun getChildren(): List<DataView<*>> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val authorities = runCatching {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA)
                .flatMap { it.providers?.toList().orEmpty() }
                .filter { it.metaData?.getBoolean(META_DATA_TOOL_MANAGER_PROVIDER, false) == true }
                .mapNotNull { it.authority }
        }.getOrElse {
            logger.reportError(TAG, it, "Failed to query installed tool providers")
            emptyList()
        }

        authorities.map { authority ->
            async {
                runCatching { withTimeoutOrNull(FETCH_TIMEOUT) { fetchManifest(authority) } }
                    .onFailure { logger.reportError(TAG, it, "Failed to load tool manifest for $authority") }
                    .getOrNull()
                    ?.let { manifest -> buildDataView(manifest, authority) }
            }
        }.awaitAll().filterNotNull()
    }

    private fun fetchManifest(authority: String): ClientToolManifest? {
        // The (String authority, ...) overload of call() isn't available until API 29; the
        // Uri-based one works all the way back to API 11, so build a bare authority Uri instead.
        val uri = Uri.Builder().scheme("content").authority(authority).build()
        val result = context.contentResolver.call(uri, METHOD_GET_MANIFEST, null, null)
        val raw = result?.getString(RESULT_MANIFEST) ?: return null
        return ClientToolManifest.decode(raw)
    }

    private fun buildDataView(manifest: ClientToolManifest, authority: String): DataView<*> {
        return BranchView(
            RootViewSpec(manifest.title, path = authority),
            StaticBranchProvider(listOf(buildDataViewChildren(manifest.root, authority)))
        )
    }

    private fun buildDataViewChildren(node: ClientTreeNode, authority: String): DataView<*> = when (node) {
        is ClientLeafNode -> LeafView(
            node.spec,
            ContentResolverDataTree(
                contentResolver = context.contentResolver,
                authority = authority,
                basePath = node.resolveBasePath(),
                readOnly = node.readOnly,
                showHiddenFiles = node.showHiddenFiles
            )
        )

        is ClientBranchNode -> BranchView(
            node.spec,
            StaticBranchProvider(node.children.map { buildDataViewChildren(it, authority) })
        )
    }

    // Ensure that a node is only ever writing one level below the root ("files/shared")
    private fun ClientLeafNode.resolveBasePath(): Path {
        val candidate = basePath.ifEmpty { spec.path }
        val normalized = Paths.get(candidate).normalize()
        val normalizedStr = normalized.toString()
        if (normalizedStr == "." || normalizedStr.startsWith("..")) {
            logger.reportError(
                TAG,
                null,
                "Leaf declared invalid basePath '${basePath}, falling back to '${spec.path}'"
            )
            return Paths.get(spec.path).normalize()
        }
        return normalized
    }

    companion object {
        private const val TAG = "DiscoveredToolsBranchProvider"
        private val FETCH_TIMEOUT = 5.seconds
    }
}
