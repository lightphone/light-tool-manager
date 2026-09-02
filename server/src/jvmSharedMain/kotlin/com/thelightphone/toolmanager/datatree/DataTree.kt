package com.thelightphone.toolmanager.datatree

import com.thelightphone.toolmanager.DataView
import com.thelightphone.toolmanager.DirectoryMeta
import com.thelightphone.toolmanager.Entry
import com.thelightphone.toolmanager.EntryType
import com.thelightphone.toolmanager.PageRequest
import com.thelightphone.toolmanager.PaginatedResponse
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path

sealed interface DataTree {
    // Drops any cached state. Distinct DataProvider instances can end up backed by overlapping
    // or identical physical storage (e.g. a directory exposed both as its own page and as one
    // root of a combined page)
    suspend fun invalidateCache() {}
}

interface LeafDataTree : DataTree {
    suspend fun getDirectoryForPath(
        path: Path,
        pageRequest: PageRequest,
        invalidateCache: Boolean = false
    ): Result<PaginatedResponse<Entry>>

    suspend fun getBytes(filePath: Path): Result<InputStream>
    suspend fun getThumbnailBytes(filePath: Path, type: EntryType): Result<InputStream>
    suspend fun checkWrite(filePath: Path): WriteCheck
    suspend fun <T> writeBytes(filePath: Path, block: suspend (OutputStream) -> T): Result<T>
    suspend fun delete(filePath: Path): Result<Int>
    suspend fun rename(filePath: Path, newName: String): Result<Boolean>
    suspend fun getDirectoryMeta(directoryPath: Path): Result<DirectoryMeta>
    suspend fun notify(directoryPath: Path)

    // opportunity for data provider to tack on additional data
    fun appendMeta(entry: Entry): Entry = entry

    fun validateFile(targetPath: Path, tempFile: Path): Boolean = true

    // Kick off some arbitrary async work, which will have if it's status polled by the client
    suspend fun startJob(
        path: Path,
        params: Map<String, String>,
        selfOrigin: String,
        mintCallbackState: (jobId: String) -> String
    ): Result<JobStart> =
        Result.failure(UnsupportedOperationException("Jobs are not supported at this path"))

    suspend fun getJobStatus(path: Path, jobId: String): JobStatus = JobStatus.NotFound

    // Completes a job whose remaining work happened out-of-band (can be used for things like OAuth logins)
    suspend fun completeJob(path: Path, jobId: String, data: Map<String, String>): Result<Unit> =
        Result.failure(UnsupportedOperationException("Jobs are not supported at this path"))
}

interface BranchDataTree : DataTree {
    // Named children this provider wants to expose as their own pages. Each returned DataView's
    // spec must carry only its own local path segment, since RootDataProvider applies all
    // ancestor path-prefixing centrally as it walks.
    suspend fun getChildren(): List<DataView<*>>
}

data class JobStart(val jobId: String, val redirectUrl: String? = null)

sealed interface JobStatus {
    data object NotFound : JobStatus
    data object Pending : JobStatus
    data object Running : JobStatus

    // if present, resultPath is a location within the same provider's own namespace
    data class Succeeded(val resultPath: Path? = null, val message: String? = null) : JobStatus
    data class Failed(val message: String) : JobStatus
}
