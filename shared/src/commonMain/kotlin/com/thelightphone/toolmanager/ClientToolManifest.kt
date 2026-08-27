package com.thelightphone.toolmanager

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
sealed class ClientTreeNode {
    abstract val spec: DataViewSpec
}

@Serializable
data class ClientBranchNode(
    override val spec: BranchViewSpec,
    val children: List<ClientTreeNode>
) : ClientTreeNode()

@Serializable
data class ClientLeafNode(
    override val spec: LeafViewSpec,
    // Subpath, relative to the provider's own root, this leaf's paths resolve against. Defaults
    // to spec.path when left blank
    val basePath: String = "",
    val readOnly: Boolean = false,
    val showHiddenFiles: Boolean = false
) : ClientTreeNode()

@Serializable
data class ClientToolManifest(
    val title: String,
    val roots: List<ClientTreeNode>
) {
    fun encode(): String = Json.encodeToString(serializer(), this)

    companion object {
        fun decode(raw: String): ClientToolManifest = Json.decodeFromString(serializer(), raw)
    }
}
