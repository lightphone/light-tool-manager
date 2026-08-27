package com.thelightphone.toolmanager

import java.nio.file.Path
import java.nio.file.Paths

fun ClientLeafNode.effectiveBasePath(): Path {
    val candidate = basePath.ifEmpty { spec.path }
    val normalized = Paths.get(candidate).normalize()
    val normalizedStr = normalized.toString()
    return if (normalizedStr == "." || normalizedStr.startsWith("..")) {
        Paths.get(spec.path).normalize()
    } else {
        normalized
    }
}

fun ClientTreeNode.leafBasePaths(): List<Path> = when (this) {
    is ClientLeafNode -> listOf(effectiveBasePath())
    is ClientBranchNode -> children.flatMap { it.leafBasePaths() }
}
