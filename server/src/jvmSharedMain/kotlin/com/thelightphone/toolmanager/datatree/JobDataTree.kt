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

// Base for a LeafDataTree that exists to run a job rather than browse/hold files - pairs with the
// UI's JobSpec (a single button that kicks a job off, then polls until it succeeds or fails; see
// composeApp/.../JobScreen.kt). Every non-job LeafDataTree method gets a sensible "there's nothing
// here" default, matching CustomDataTree's shape. startJob and getJobStatus are re-declared
// abstract here (LeafDataTree's own defaults silently report "unsupported", which would defeat
// the point of extending this class); completeJob is left with LeafDataTree's default, since only
// jobs with a remote/callback leg need it - override it if yours does.
abstract class JobDataTree : LeafDataTree {
    override suspend fun getDirectoryForPath(
        path: Path,
        pageRequest: PageRequest,
        invalidateCache: Boolean
    ): Result<PaginatedResponse<Entry>> = Result.success(
        PaginatedResponse(
            emptyList(),
            PaginationInfo(1, 1, 1, 0, hasNext = false, hasPrevious = false)
        )
    )

    override suspend fun getThumbnailBytes(
        filePath: Path,
        type: EntryType
    ): Result<InputStream> = Result.failure(UnsupportedOperationException("No thumbnails available for job data tree"))

    override suspend fun checkWrite(filePath: Path): WriteCheck = WriteCheck.ReadOnly

    override suspend fun <T> writeBytes(
        filePath: Path,
        block: suspend (OutputStream) -> T
    ): Result<T> = Result.failure(SecurityException("Cannot write to job data tree"))

    override suspend fun delete(filePath: Path): Result<Int> =
        Result.failure(SecurityException("Cannot delete from job data tree"))

    override suspend fun rename(
        filePath: Path,
        newName: String
    ): Result<Boolean> = Result.failure(SecurityException("Cannot rename anything in job data tree"))

    override suspend fun getDirectoryMeta(directoryPath: Path): Result<DirectoryMeta> = Result.success(
        DirectoryMeta(true)
    )

    override suspend fun notify(directoryPath: Path) {
        /* no-op by default */
    }

    abstract override suspend fun startJob(
        path: Path,
        params: Map<String, String>,
        selfOrigin: String,
        mintCallbackState: (jobId: String) -> String
    ): Result<JobStart>

    abstract override suspend fun getJobStatus(path: Path, jobId: String): JobStatus
}
