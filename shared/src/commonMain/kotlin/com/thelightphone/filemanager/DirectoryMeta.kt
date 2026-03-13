package com.thelightphone.filemanager

import kotlinx.serialization.Serializable

@Serializable
data class DirectoryMeta(
    val readOnly: Boolean
)