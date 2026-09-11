package com.thelightphone.toolmanager

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.collections.orEmpty
import kotlin.time.Duration.Companion.seconds

private const val TAG = "ToolManagerDiscovery"
private val FETCH_TIMEOUT = 5.seconds

data class ToolManagerTool(val packageName: String, val authority: String, val manifest: ClientToolManifest)

suspend fun Context.discoverToolManagerEnabledTools(
    logger: Logger,
    isPackageAllowed: suspend (packageName: String) -> Boolean
): List<ToolManagerTool> = withContext(Dispatchers.IO) {
        val authorities = runCatching {
            @Suppress("DEPRECATION")
            packageManager.getInstalledPackages(PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA)
                .filter { isPackageAllowed(it.packageName) }
                .flatMap { it.providers?.toList().orEmpty() }
                .filter { it.metaData?.getBoolean(META_DATA_TOOL_MANAGER_PROVIDER, false) == true }
                .mapNotNull { it.authority to it.packageName }
        }.getOrElse {
            logger.reportError(TAG, it, "Failed to query installed tool providers")
            emptyList()
        }

        authorities.map { (authority, packageName) ->
            async {
                runCatching { withTimeoutOrNull(FETCH_TIMEOUT) { fetchManifest(authority) } }
                    .onFailure {
                        logger.reportError(
                            TAG,
                            it,
                            "Failed to load tool manifest for $authority"
                        )
                    }
                    .getOrNull()
                    ?.let { manifest -> ToolManagerTool(packageName, authority, manifest) }
            }
        }.awaitAll().filterNotNull()
    }

private fun Context.fetchManifest(authority: String): ClientToolManifest? {
    val uri = Uri.Builder().scheme("content").authority(authority).build()
    val result = contentResolver.call(uri, METHOD_GET_MANIFEST, null, null)
    val raw = result?.getString(RESULT_MANIFEST) ?: return null
    return ClientToolManifest.decode(raw)
}