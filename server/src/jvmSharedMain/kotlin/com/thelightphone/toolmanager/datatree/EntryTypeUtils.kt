package com.thelightphone.toolmanager.datatree

import com.thelightphone.toolmanager.EntryType

val extensionTypeMap = listOf(
    setOf(
        "jpg",
        "jpeg",
        "png",
        "gif",
        "bmp",
        "webp",
        "heic",
        "heif",
        "tiff",
        "tif"
    ) to EntryType.Image,
    setOf("mp4", "mov", "avi", "mkv", "webm", "m4v") to EntryType.Video,
    setOf("mp3", "wav", "aac", "flac", "ogg", "m4a", "wma") to EntryType.Audio,
    setOf(
        "txt",
        "md",
        "csv",
        "json",
        "xml",
        "html",
        "css",
        "js",
        "kt",
        "java",
        "py",
        "sh",
        "yml",
        "yaml",
        "toml",
        "log"
    ) to EntryType.Text
).fold(mutableMapOf<String, EntryType>()) { acc, (extensions, entryType) ->
    acc.apply { putAll(extensions.associateWith { entryType }) }
}

fun entryTypeForName(name: String): EntryType {
    val ext = name.substringAfterLast('.', "").lowercase()
    return extensionTypeMap[ext] ?: EntryType.GenericFile
}
