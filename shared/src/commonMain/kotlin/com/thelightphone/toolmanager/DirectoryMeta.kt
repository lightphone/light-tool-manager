package com.thelightphone.toolmanager

import kotlinx.serialization.Serializable

@Serializable
data class DirectoryMeta(
    val readOnly: Boolean
)