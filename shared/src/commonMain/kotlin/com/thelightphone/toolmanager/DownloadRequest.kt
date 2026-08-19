package com.thelightphone.toolmanager

import kotlinx.serialization.Serializable

@Serializable
data class DownloadRequest(
    val paths: List<String>
)