package com.thelightphone.toolmanager

import kotlinx.serialization.Serializable

@Serializable
enum class EntryType{
    Directory, GenericFile, Audio, Image, Video, Text
}

@Serializable
data class Entry(
    val type: EntryType,
    val title: String,
    val path: String,
    val lastModified: Long,
    val size: Long? = 0L,
    // whether the file actually sits on the server, or needs to be proxied through it (light notes, for example)
    val remote: Boolean = false,
    val meta: Map<String, String>? = null
) {}
