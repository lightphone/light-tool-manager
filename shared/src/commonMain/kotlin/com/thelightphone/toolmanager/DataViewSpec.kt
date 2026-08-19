package com.thelightphone.toolmanager

import kotlinx.serialization.Serializable
private fun joinPath(prefix: String, segment: String) = if (prefix.isEmpty()) segment else "$prefix/$segment"

@Serializable
sealed class DataViewSpec {
    abstract val label: String
    // Whoever implements DataProvider.getChildren() must return specs whose `path` is just their
    // own local segment, never pre-prefixed with ancestry. the server prefixes centrally with
    // the full accumulated path when serving each level.
    abstract val path: String
    abstract val headerText: String?

    abstract fun withPathPrefix(prefix: String): DataViewSpec
}

@Serializable
sealed class BranchViewSpec : DataViewSpec()

@Serializable
sealed class LeafViewSpec : DataViewSpec()

@Serializable
data class RootViewSpec(
    override val label: String,
    override val path: String = label,
    override val headerText: String? = null
) : BranchViewSpec() {
    override fun withPathPrefix(prefix: String) = RootViewSpec(label, joinPath(prefix, path), headerText)
}

@Serializable
data class FileBrowserSpec(
    override val label: String,
    override val path: String,
    override val headerText: String? = null,
) :
    LeafViewSpec() {
    override fun withPathPrefix(prefix: String) = FileBrowserSpec(label, joinPath(prefix, path), headerText)
}

@Serializable
data class DropboxSpec(
    override val label: String,
    override val path: String,
    override val headerText: String? = null,
    val buttonText: String
) :
    LeafViewSpec() {
    override fun withPathPrefix(prefix: String) = DropboxSpec(label, joinPath(prefix, path), headerText, buttonText)
}

@Serializable
data class ExportSpec(
    override val label: String,
    override val path: String,
    val resourceSubPath: String, // the thing to get downloaded
    override val headerText: String? = null,
    val buttonText: String
) :
    LeafViewSpec() {
    override fun withPathPrefix(prefix: String) = ExportSpec(label, joinPath(prefix, path), resourceSubPath, headerText, buttonText)

    val resourceFullPath get() = joinPath(path, resourceSubPath)
}

@Serializable
data class CustomSpec(
    override val label: String,
    override val path: String,
) : LeafViewSpec() {

    override val headerText: String? = null
    override fun withPathPrefix(prefix: String) = CustomSpec(label, joinPath(prefix, path))
}
