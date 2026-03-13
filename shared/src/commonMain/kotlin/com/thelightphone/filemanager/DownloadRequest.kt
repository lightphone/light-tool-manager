package com.thelightphone.filemanager

import kotlinx.serialization.Serializable

@Serializable
data class DownloadRequest(
    val paths: List<String>
)