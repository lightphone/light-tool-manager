package com.thelightphone.filemanager

import com.thelightphone.toolmanager.DataViewSpec
import com.thelightphone.toolmanager.DirectoryMeta
import com.thelightphone.toolmanager.DownloadRequest
import com.thelightphone.toolmanager.DownloadTokenResponse
import com.thelightphone.toolmanager.Entry
import com.thelightphone.toolmanager.PaginatedResponse
import com.thelightphone.toolmanager.SortBy
import com.thelightphone.toolmanager.SortOrder
import com.thelightphone.toolmanager.getBaseUrl
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
import io.ktor.http.isSuccess
import kotlin.jvm.JvmInline

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
    suspend fun notifyUpload(path: String): Result<Unit>
    suspend fun deleteFile(path: String): Result<Boolean>
    suspend fun uploadBytes(url: String, bytes: ByteArray, timeoutMillis: Long): HttpStatusCode
}

@JvmInline
value class HttpRemote(private val client: HttpClient) : Remote {
    override suspend fun ping(): Result<Boolean> {
        return try {
            Result.success(client.get("${getBaseUrl()}/ping").status.isSuccess())
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    override suspend fun treeAt(path: String): Result<List<DataViewSpec>> {
        return runCatching { client.get("${getBaseUrl()}/api/tree/$path").body<List<DataViewSpec>>() }
    }

    override suspend fun filesAt(
        path: String,
        page: Int,
        size: Int,
        sortBy: SortBy,
        sortOrder: SortOrder
    ): Result<PaginatedResponse<Entry>> = runCatching {
        client.get("${getBaseUrl()}/api/files/$path") {
            parameter("page", page)
            parameter("size", size)
            parameter("sortBy", sortBy)
            parameter("sortOrder", sortOrder)
        }.body()
    }

    override suspend fun metaAt(path: String): Result<DirectoryMeta> = runCatching {
        client.get("${getBaseUrl()}/api/meta/$path").body()
    }

    override suspend fun requestDownloadToken(paths: List<String>): Result<DownloadTokenResponse> = runCatching {
        val response = client.post("${getBaseUrl()}/api/download") {
            contentType(ContentType.Application.Json)
            setBody(DownloadRequest(paths = paths))
        }
        check(response.status.isSuccess()) { "Download request failed: ${response.status}" }
        response.body<DownloadTokenResponse>()
    }

    override suspend fun notifyUpload(path: String): Result<Unit> = runCatching {
        client.post("${getBaseUrl()}/api/notify/$path")
        Unit
    }

    override suspend fun deleteFile(path: String): Result<Boolean> = runCatching {
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
}