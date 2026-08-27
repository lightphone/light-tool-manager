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
                basePath = resolveBasePath(node),
                readOnly = node.readOnly,
                showHiddenFiles = node.showHiddenFiles
            )
        )

        is ClientBranchNode -> BranchView(
            node.spec,
            StaticBranchProvider(node.children.map { buildDataViewChildren(it, authority) })
        )
    }

    // effectiveBasePath() (shared with the client, which uses it to know which directories to
    // pre-create) already enforces "never the provider's bare root" — this just adds a log when
    // that fallback actually kicks in, so a misconfigured manifest is visible somewhere.
    private fun resolveBasePath(node: ClientLeafNode): Path {
        val resolved = node.effectiveBasePath()
        val declaredNormalized = Paths.get(node.basePath.ifEmpty { "." }).normalize().toString()
        if (node.basePath.isNotEmpty() && resolved.toString() != declaredNormalized) {
            logger.reportError(
                TAG,
                null,
                "Leaf '${node.spec.path}' declared basePath '${node.basePath}' which resolves " +
                    "to the provider root or outside it; falling back to '${node.spec.path}'"
            )
        }
        return resolved
    }

    companion object {
        private const val TAG = "DiscoveredToolsBranchProvider"
        private val FETCH_TIMEOUT = 5.seconds
    }
}
