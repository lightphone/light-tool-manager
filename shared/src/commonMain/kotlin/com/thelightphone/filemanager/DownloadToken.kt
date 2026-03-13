package com.thelightphone.filemanager

import kotlinx.serialization.Serializable

@Serializable
data class DownloadTokenResponse(
    val token: String,
    val expiresAt: String // ISO 8601 timestamp
)

@Serializable
data class DownloadSession(
    val paths: List<String>,
    val createdAt: Long,
    val expiresAt: Long
)