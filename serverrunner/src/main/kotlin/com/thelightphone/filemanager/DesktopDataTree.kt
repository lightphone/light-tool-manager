package com.thelightphone.filemanager

import com.thelightphone.filemanager.datatree.FileDataTree
import java.io.File

class DesktopDataTree(
    readRoots: List<File>,
    writeRoot: File,
    defaultThumbnails: Map<EntryType, File> = emptyMap(),
) : FileDataTree(readRoots, writeRoot, defaultThumbnails) {
    constructor(readRoot: File, defaultThumbnails: Map<EntryType, File> = emptyMap()) : this(
        listOf(readRoot), readRoot, defaultThumbnails
    )

    override fun getMeta(entry: Entry): Map<String, String>? {
        return when (entry.type) {
            EntryType.Video -> mapOf(MetaKeys.DURATION to "HH:MM:SS")
            else -> emptyMap()
        }
    }
}