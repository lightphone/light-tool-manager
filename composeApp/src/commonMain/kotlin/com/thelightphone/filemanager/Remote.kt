package com.thelightphone.filemanager

import coil3.PlatformContext
import coil3.request.ImageRequest
import com.thelightphone.toolmanager.DataViewSpec
import com.thelightphone.toolmanager.DirectoryMeta
import com.thelightphone.toolmanager.DownloadRequest
import com.thelightphone.toolmanager.DownloadTokenResponse
import com.thelightphone.toolmanager.Entry
import com.thelightphone.toolmanager.JobState
import com.thelightphone.toolmanager.JobStartRequest
import com.thelightphone.toolmanager.JobStartResponse
import com.thelightphone.toolmanager.JobStatusResponse
import com.thelightphone.toolmanager.PaginatedResponse
import com.thelightphone.toolmanager.PaginationInfo
import com.thelightphone.toolmanager.SortBy
import com.thelightphone.toolmanager.SortOrder
import com.thelightphone.toolmanager.SignatureQueryParam
import com.thelightphone.toolmanager.TimestampQueryParam
import com.thelightphone.toolmanager.getBaseUrl
import com.thelightphone.toolmanager.platformUploadOctetStream
import com.thelightphone.toolmanager.signRequest
import com.thelightphone.toolmanager.triggerDownload
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock

// Like runCatching, but re-throws CancellationException instead of wrapping it into a
// Result.failure. runCatching's plain `catch (Throwable)` treats a coroutine being cancelled (e.g.
// because the screen that called this left composition) the same as a real failure, which not
// only breaks structured concurrency's cancellation propagation but means callers that surface
// Result.failure as a user-facing alert (RootScreen, DownloadScreen, etc.) show a spurious "Failed
// to load" every time a request gets interrupted by ordinary navigation - not just genuine errors.
private suspend fun <T> resultCatching(block: suspend () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
}

// Abstraction for fetching data from the server
interface Remote {
    suspend fun ping(): Result<Boolean>
    suspend fun treeAt(path: String): Result<List<DataViewSpec>>
    suspend fun filesAt(
        path: String,
        page: Int,
        size: Int,
        sortBy: SortBy,
        sortOrder: SortOrder
    ): Result<PaginatedResponse<Entry>>
    suspend fun metaAt(path: String): Result<DirectoryMeta>
    suspend fun requestDownloadToken(paths: List<String>): Result<DownloadTokenResponse>
    suspend fun startJob(path: String, params: Map<String, String> = emptyMap()): Result<JobStartResponse>
    suspend fun jobStatus(path: String, jobId: String): Result<JobStatusResponse>
    suspend fun notifyUpload(path: String): Result<Unit>
    suspend fun deleteFile(path: String): Result<Boolean>
    suspend fun uploadBytes(url: String, bytes: ByteArray, timeoutMillis: Long): HttpStatusCode
    suspend fun uploadOctetStream(path: String, fileName: String, bytes: ByteArray, timeoutMillis: Long): HttpStatusCode
    suspend fun downloadFile(token: String)
    fun thumbnailFetcherForEntry(entry: Entry, context: PlatformContext): ImageRequest
}

class HttpRemote(private val client: HttpClient, private val apiKey: String?) : Remote {
    override suspend fun ping(): Result<Boolean> = resultCatching {
        client.get("${getBaseUrl()}/ping").status.isSuccess()
    }

    override suspend fun treeAt(path: String): Result<List<DataViewSpec>> {
        return resultCatching { client.get("${getBaseUrl()}/api/tree/$path").body<List<DataViewSpec>>() }
    }

    override suspend fun filesAt(
        path: String,
        page: Int,
        size: Int,
        sortBy: SortBy,
        sortOrder: SortOrder
    ): Result<PaginatedResponse<Entry>> = resultCatching {
        client.get("${getBaseUrl()}/api/files/$path") {
            parameter("page", page)
            parameter("size", size)
            parameter("sortBy", sortBy)
            parameter("sortOrder", sortOrder)
        }.body()
    }

    override suspend fun metaAt(path: String): Result<DirectoryMeta> = resultCatching {
        client.get("${getBaseUrl()}/api/meta/$path").body()
    }

    override suspend fun requestDownloadToken(paths: List<String>): Result<DownloadTokenResponse> = resultCatching {
        val response = client.post("${getBaseUrl()}/api/download") {
            contentType(ContentType.Application.Json)
            setBody(DownloadRequest(paths = paths))
        }
        check(response.status.isSuccess()) { "Download request failed: ${response.status}" }
        response.body<DownloadTokenResponse>()
    }

    override suspend fun startJob(path: String, params: Map<String, String>): Result<JobStartResponse> = resultCatching {
        val response = client.post("${getBaseUrl()}/api/job/$path") {
            contentType(ContentType.Application.Json)
            setBody(JobStartRequest(params))
        }
        check(response.status.isSuccess()) { "Job start failed: ${response.status}" }
        response.body<JobStartResponse>()
    }

    override suspend fun jobStatus(path: String, jobId: String): Result<JobStatusResponse> = resultCatching {
        val response = client.get("${getBaseUrl()}/api/job/$path") {
            parameter("jobId", jobId)
        }
        check(response.status.isSuccess()) { "Job status check failed: ${response.status}" }
        response.body<JobStatusResponse>()
    }

    override suspend fun notifyUpload(path: String): Result<Unit> = resultCatching {
        client.post("${getBaseUrl()}/api/notify/$path")
        Unit
    }

    override suspend fun deleteFile(path: String): Result<Boolean> = resultCatching {
        client.delete("${getBaseUrl()}/api/files/$path").status.isSuccess()
    }

    override suspend fun uploadBytes(url: String, bytes: ByteArray, timeoutMillis: Long): HttpStatusCode {
        val response = client.post(url) {
            contentType(ContentType.Application.OctetStream)
            setBody(bytes)
            timeout { requestTimeoutMillis = timeoutMillis }
        }
        return response.status
    }

    override suspend fun downloadFile(token: String) {
        val path = "/api/download-zip/$token"
        val query = apiKey?.let { key ->
            val timestampMillis = Clock.System.now().toEpochMilliseconds()
            val signature = signRequest(key, "GET", path, timestampMillis)
            "?$SignatureQueryParam=$signature&$TimestampQueryParam=$timestampMillis"
        }.orEmpty()
        triggerDownload("${getBaseUrl()}$path$query")
    }

    override suspend fun uploadOctetStream(
        path: String,
        fileName: String,
        bytes: ByteArray,
        timeoutMillis: Long
    ): HttpStatusCode = platformUploadOctetStream(
        this,
        "${getBaseUrl()}/api/upload/$path/${fileName.encodeURLPathPart()}",
        bytes,
        timeoutMillis
    )

    override fun thumbnailFetcherForEntry(entry: Entry, context: PlatformContext): ImageRequest =
        ImageRequest.Builder(context)
            .data("${getBaseUrl()}/api/thumbnail/${entry.path}?type=${entry.type}")
            .build()
}

// No-op Remote for @Preview composables, which have no server to talk to.
object PreviewRemote : Remote {
    override suspend fun ping(): Result<Boolean> = Result.success(true)

    override suspend fun treeAt(path: String): Result<List<DataViewSpec>> = Result.success(emptyList())

    override suspend fun filesAt(
        path: String,
        page: Int,
        size: Int,
        sortBy: SortBy,
        sortOrder: SortOrder
    ): Result<PaginatedResponse<Entry>> = Result.success(
        PaginatedResponse(
            data = emptyList(),
            pagination = PaginationInfo(
                currentPage = page,
                totalPages = 1,
                pageSize = size,
                totalItems = 0,
                hasNext = false,
                hasPrevious = false
            )
        )
    )

    override suspend fun metaAt(path: String): Result<DirectoryMeta> = Result.success(DirectoryMeta(readOnly = false))

    override suspend fun requestDownloadToken(paths: List<String>): Result<DownloadTokenResponse> =
        Result.success(DownloadTokenResponse(token = "preview-token", expiresAt = ""))

    override suspend fun startJob(path: String, params: Map<String, String>): Result<JobStartResponse> =
        Result.success(JobStartResponse(jobId = "preview-job"))

    override suspend fun jobStatus(path: String, jobId: String): Result<JobStatusResponse> =
        Result.success(JobStatusResponse(jobId = jobId, status = JobState.SUCCEEDED))

    override suspend fun notifyUpload(path: String): Result<Unit> = Result.success(Unit)

    override suspend fun deleteFile(path: String): Result<Boolean> = Result.success(true)

    override suspend fun uploadBytes(url: String, bytes: ByteArray, timeoutMillis: Long): HttpStatusCode =
        HttpStatusCode.OK

    override suspend fun uploadOctetStream(
        path: String,
        fileName: String,
        bytes: ByteArray,
        timeoutMillis: Long
    ): HttpStatusCode = HttpStatusCode.OK

    override suspend fun downloadFile(token: String) {}

    override fun thumbnailFetcherForEntry(entry: Entry, context: PlatformContext): ImageRequest =
        ImageRequest.Builder(context).build()
}