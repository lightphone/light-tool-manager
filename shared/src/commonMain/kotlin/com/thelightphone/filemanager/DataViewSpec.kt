package com.thelightphone.filemanager

import kotlinx.serialization.Serializable

@Serializable
sealed class DataViewSpec {
    abstract val label: String
    // Whoever implements DataProvider.getChildren() must return specs whose `path` is just
    // their own local segment (length 1), never pre-prefixed with ancestry — the server prefixes
    // centrally with the full accumulated path when serving each level.
    abstract val path: List<String>
    abstract val headerText: String?

    abstract fun withPathPrefix(prefix: List<String>): DataViewSpec
}

@Serializable
data class RootViewSpec(
    override val label: String,
    override val path: List<String> = listOf(label),
    override val headerText: String? = null
) : DataViewSpec() {
    override fun withPathPrefix(prefix: List<String>) = RootViewSpec(label, prefix + path, headerText)
}

@Serializable
data class FileBrowserSpec(
    override val label: String,
    override val path: List<String>,
    override val headerText: String? = null,
) :
    DataViewSpec() {
    override fun withPathPrefix(prefix: List<String>) = FileBrowserSpec(label, prefix + path, headerText)
}

@Serializable
data class DropboxSpec(
    override val label: String,
    override val path: List<String>,
    override val headerText: String? = null,
    val buttonText: String
) :
    DataViewSpec() {
    override fun withPathPrefix(prefix: List<String>) = DropboxSpec(label, prefix + path, headerText, buttonText)
}

@Serializable
data class ConfiguratorSpec(
    override val label: String,
    override val path: List<String>,
    override val headerText: String? = null
) :
    DataViewSpec() {
    override fun withPathPrefix(prefix: List<String>) = ConfiguratorSpec(label, prefix + path, headerText)
}