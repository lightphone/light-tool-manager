package com.thelightphone.toolmanager.datatree

import com.thelightphone.toolmanager.DirectoryMeta
import com.thelightphone.toolmanager.Entry
import com.thelightphone.toolmanager.EntryType
import com.thelightphone.toolmanager.PageRequest
import com.thelightphone.toolmanager.PaginatedResponse
import com.thelightphone.toolmanager.PaginationInfo
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import kotlin.time.Clock

abstract class CustomDataTree : LeafDataTree {
    override suspend fun getDirectoryForPath(
        path: Path,
        pageRequest: PageRequest,
        invalidateCache: Boolean
    ): Result<PaginatedResponse<Entry>> = Result.success(
        PaginatedResponse(
            listOf(
                Entry(
                    EntryType.Text,
                    "data",
                    "data",
                    Clock.System.now().toEpochMilliseconds()
                )
            ),
            PaginationInfo(1, 1, 1, 1, hasNext = false, hasPrevious = false)
        )
    )

    override suspend fun getThumbnailBytes(
        filePath: Path,
        type: EntryType
    ): Result<InputStream> = Result.failure(UnsupportedOperationException("No thumbnails available for custom data tree"))

    override suspend fun checkWrite(filePath: Path): WriteCheck = WriteCheck.ReadOnly

    override suspend fun <T> writeBytes(
        filePath: Path,
        block: suspend (OutputStream) -> T
    ): Result<T> = Result.failure(SecurityException("Cannot write to custom data tree"))

    override suspend fun delete(filePath: Path): Result<Int> = Result.failure(SecurityException("Cannot delete from custom data tree"))

    override suspend fun rename(
        filePath: Path,
        newName: String
    ): Result<Boolean> = Result.failure(SecurityException("Cannot rename anything in custom data tree"))

    override suspend fun getDirectoryMeta(directoryPath: Path): Result<DirectoryMeta> = Result.success(
        DirectoryMeta(true)
    )

    override suspend fun notify(directoryPath: Path) {
        /* no-op by default */
    }
}