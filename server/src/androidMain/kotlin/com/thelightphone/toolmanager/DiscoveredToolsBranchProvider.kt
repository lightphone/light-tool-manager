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
    private val logger: Logger,
    private val isPackageAllowed: (String) -> Boolean
) : BranchDataTree {

    override suspend fun getChildren(): List<DataView<*>> = withContext(Dispatchers.IO) {
        context.discoverToolManagerEnabledTools(logger, isPackageAllowed).map { (_, authority, manifest) ->
            buildDataView(manifest, authority)
        }
    }

    private fun buildDataView(manifest: ClientToolManifest, authority: String): DataView<*> {
        return BranchView(
            RootViewSpec(manifest.title, path = authority),
            StaticBranchProvider(manifest.roots.map { buildDataViewChildren(it, authority) })
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
    }
}
