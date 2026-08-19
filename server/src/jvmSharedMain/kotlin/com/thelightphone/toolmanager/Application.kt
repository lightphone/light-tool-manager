package com.thelightphone.toolmanager

import com.thelightphone.toolmanager.datatree.LeafDataTree
import com.thelightphone.toolmanager.datatree.RootDataTree
import com.thelightphone.toolmanager.datatree.WriteCheck
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.AbstractLogger
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Serializable
data class ErrorResponse(val error: String, val message: String)

@Serializable
data class WriteCheckResponse(
    val status: String, // "safe", "file_exists", "directory_exists", "invalid_path"
    val existing: Entry? = null
)

@Serializable
data class RenameRequest(val newName: String)

@Serializable
data class DeleteResponse(val deleted: Int)

@Serializable
data class RenameResponse(val renamed: Boolean)

@Serializable
data class PairRequest(val code: String)

@Serializable
data class PairResponse(val key: String)

private const val TAG = "ToolManagerServer"
private const val PairPath = "/api/pair"

fun Application.module(
    rootDataProvider: RootDataTree,
    enableLogging: Boolean,
    toolManagerLogger: Logger,
    auth: ToolManagerAuth? = null
) {

    install(ContentNegotiation) {
        json()
    }

    install(CallLogging) {
        logger = object : AbstractLogger() {

            override fun getFullyQualifiedCallerName(): String = "ktor"

            override fun handleNormalizedLoggingCall(
                level: Level?,
                marker: Marker?,
                message: String?,
                arguments: Array<out Any?>?,
                throwable: Throwable?
            ) {
                if (message != null && level != null) {
                    // Strip ANSI escape codes (e.g. color codes like [31m)
                    val clean = message.replace(Regex("\u001B\\[[;\\d]*m"), "")
                    when (level) {
                        Level.ERROR -> toolManagerLogger.reportError(
                            TAG,
                            throwable?.let { Exception(it) },
                            clean
                        )

                        Level.INFO, Level.DEBUG, Level.TRACE, Level.WARN -> toolManagerLogger.log(
                            TAG,
                            clean
                        )
                    }
                }
            }

            override fun isTraceEnabled(): Boolean = enableLogging
            override fun isTraceEnabled(p0: Marker?): Boolean = enableLogging
            override fun isDebugEnabled(): Boolean = enableLogging
            override fun isDebugEnabled(p0: Marker?): Boolean = enableLogging
            override fun isInfoEnabled(): Boolean = enableLogging
            override fun isInfoEnabled(p0: Marker?): Boolean = enableLogging
            override fun isWarnEnabled(): Boolean = enableLogging
            override fun isWarnEnabled(p0: Marker?): Boolean = enableLogging
            override fun isErrorEnabled(): Boolean = true
            override fun isErrorEnabled(p0: Marker?): Boolean = true
        }
        level = Level.TRACE
    }


    if (auth != null) {
        intercept(ApplicationCallPipeline.Plugins) {
            val path = call.request.path()
            // /api/pair is deliberately excluded: a device pairing via a totp code has no key
            // yet, so it has to be reachable without one.
            if (path.startsWith("/api/") && path != PairPath) {
                val signature = call.request.header(SignatureHeader)
                    ?: call.request.queryParameters[SignatureQueryParam]
                val timestamp = (call.request.header(TimestampHeader)
                    ?: call.request.queryParameters[TimestampQueryParam])?.toLongOrNull()

                val valid = signature != null && timestamp != null &&
                    auth.verifySignature(call.request.httpMethod.value, path, timestamp, signature)

                if (!valid) {
                    call.respond(HttpStatusCode.Unauthorized)
                    finish()
                    return@intercept
                }
            }
        }
    } else {
        toolManagerLogger.log(TAG, "WARNING: Tool Manager server running with no auth!")
    }

    val downloadTokenManager = DownloadTokenManager()

    routing {
        staticResources("/", "static") {
            default("index.html")
        }

        get("/ping") {
            call.respond(HttpStatusCode.OK)
        }

        if (auth != null) {
            post(PairPath) {
                val pairRequest = call.receive<PairRequest>()
                val newKey = auth.mintKey(pairRequest.code)
                if (newKey != null) {
                    call.respond(HttpStatusCode.OK, PairResponse(newKey))
                } else {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ErrorResponse("INVALID_CODE", "Invalid or expired code")
                    )
                }
            }
        }

        // No-op past the auth interceptor
        get("/api/validate") {
            call.respond(HttpStatusCode.OK)
        }

        // Returns the immediate children (one level) of the page at this path; empty path
        // ("." by the same convention as every other route below) means the top-level pages.
        get("/api/tree/{path...}") {
            val filePath = call.parameters.getAll("path")?.joinToString("/") ?: "."
            val showHidden = call.request.queryParameters["showHidden"] == "true"
            rootDataProvider.getChildrenAt(Path.of(filePath), showHidden = showHidden).fold(
                onSuccess = { call.respond(HttpStatusCode.OK, it) },
                onFailure = { call.respondError(it) }
            )
        }

        // returns DirectoryMeta
        get("/api/meta/{path...}") {
            val filePath = call.parameters.getAll("path")?.joinToString("/") ?: "."
            rootDataProvider.getDirectoryMeta(Path.of(filePath)).fold(
                onSuccess = { call.respond(HttpStatusCode.OK, it) },
                onFailure = { call.respondError(it) }
            )
        }

        get("/api/files/{path...}") {
            val filePath = call.parameters.getAll("path")?.joinToString("/") ?: "."
            val pageRequestResult = createPageRequest(
                page = call.request.queryParameters["page"],
                size = call.request.queryParameters["size"],
                sortBy = call.request.queryParameters["sortBy"],
                sortOrder = call.request.queryParameters["sortOrder"]
            )

            pageRequestResult.fold(
                onSuccess = { pageRequest ->
                    val invalidateCache = call.request.queryParameters["refresh"] == "true"
                    rootDataProvider.getDirectoryForPath(
                        Path.of(filePath), pageRequest, invalidateCache
                    ).fold(
                        onSuccess = { call.respond(HttpStatusCode.OK, it) },
                        onFailure = { call.respondError(it) }
                    )
                },
                onFailure = {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("INVALID_PARAMETERS", it.message ?: "Invalid parameters")
                    )
                }
            )
        }

        get("/api/download/{path...}") {
            val filePath = call.parameters.getAll("path")?.joinToString("/")
            if (filePath == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("MISSING_PATH", "Missing path")
                )
                return@get
            }

            rootDataProvider.getBytes(Path.of(filePath)).fold(
                onSuccess = { inputStream ->
                    val filename = filePath.substringAfterLast('/')
                    call.response.headers.append(
                        HttpHeaders.ContentDisposition,
                        "attachment; filename=\"$filename\""
                    )
                    call.respondOutputStream(contentType = ContentType.Application.OctetStream) {
                        inputStream.use { it.transferTo(this) }
                    }
                },
                onFailure = { call.respondError(it) }
            )
        }

        // Like /api/download/{path...}, but serves the bytes inline (no Content-Disposition
        // header) instead of prompting a file download.
        get("/api/data/{path...}") {
            val filePath = call.parameters.getAll("path")?.joinToString("/")
            if (filePath == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("MISSING_PATH", "Missing path")
                )
                return@get
            }

            rootDataProvider.getBytes(Path.of(filePath)).fold(
                onSuccess = { inputStream ->
                    call.respondOutputStream(contentType = ContentType.Application.OctetStream) {
                        inputStream.use { it.transferTo(this) }
                    }
                },
                onFailure = { call.respondError(it) }
            )
        }

        get("/api/thumbnail/{path...}") {
            val filePath = call.parameters.getAll("path")?.joinToString("/")
            if (filePath == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("MISSING_PATH", "Missing path")
                )
                return@get
            }
            val type = call.request.queryParameters["type"]
                ?.let { runCatching { EntryType.valueOf(it) }.getOrNull() }
                ?: EntryType.Image

            rootDataProvider.getThumbnailBytes(Path.of(filePath), type).fold(
                onSuccess = { inputStream ->
                    call.respondOutputStream(contentType = ContentType.Application.OctetStream) {
                        inputStream.use { it.transferTo(this) }
                    }
                },
                onFailure = { call.respondError(it) }
            )
        }

        get("/api/writecheck/{path...}") {
            val filePath = call.parameters.getAll("path")?.joinToString("/")
            if (filePath == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("MISSING_PATH", "Missing path")
                )
                return@get
            }

            val response = when (val check = rootDataProvider.checkWrite(Path.of(filePath))) {
                WriteCheck.Safe -> WriteCheckResponse(status = "safe")
                is WriteCheck.FileExists -> WriteCheckResponse(
                    status = "file_exists",
                    existing = check.existing
                )

                WriteCheck.DirectoryExists -> WriteCheckResponse(status = "directory_exists")
                WriteCheck.InvalidPath -> WriteCheckResponse(status = "invalid_path")
                WriteCheck.ReadOnly -> WriteCheckResponse(status = "read_only")
            }
            call.respond(HttpStatusCode.OK, response)
        }

        post("/api/upload/{path...}") {
            val filePath = call.parameters.getAll("path")?.joinToString("/")
            if (filePath == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("MISSING_PATH", "Missing path")
                )
                return@post
            }

            rootDataProvider.writeBytes(Path.of(filePath)) { outputStream ->
                call.receiveChannel().toInputStream().use { it.transferTo(outputStream) }
            }.fold(
                onSuccess = { call.respond(HttpStatusCode.OK) },
                onFailure = { t ->
                    toolManagerLogger.reportError(TAG, t.loggable(), "Error uploading")
                    call.respondError(t)
                }
            )
        }

        post("/api/notify/{path...}") {
            val filePath = call.parameters.getAll("path")?.joinToString("/") ?: "."
            rootDataProvider.notify(Path.of(filePath))
            call.respond(HttpStatusCode.OK)
        }

        delete("/api/files/{path...}") {
            val filePath = call.parameters.getAll("path")?.joinToString("/")
            if (filePath == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("MISSING_PATH", "Missing path")
                )
                return@delete
            }

            rootDataProvider.delete(Path.of(filePath)).fold(
                onSuccess = { count -> call.respond(HttpStatusCode.OK, DeleteResponse(count)) },
                onFailure = { call.respondError(it) }
            )
        }

        post("/api/rename/{path...}") {
            val filePath = call.parameters.getAll("path")?.joinToString("/")
            if (filePath == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("MISSING_PATH", "Missing path")
                )
                return@post
            }

            val renameRequest = call.receive<RenameRequest>()
            rootDataProvider.rename(Path.of(filePath), renameRequest.newName).fold(
                onSuccess = { success -> call.respond(HttpStatusCode.OK, RenameResponse(success)) },
                onFailure = { call.respondError(it) }
            )
        }

        // Bulk download: create token
        post("/api/download") {
            val downloadRequest = call.receive<DownloadRequest>()
            if (downloadRequest.paths.isEmpty()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("EMPTY_SELECTION", "No files selected for download")
                )
                return@post
            }
            val tokenResponse = downloadTokenManager.createToken(downloadRequest.paths)
            call.respond(HttpStatusCode.OK, tokenResponse)
        }

        // Bulk download: stream zip by token
        get("/api/download-zip/{token}") {
            val token = call.parameters["token"]
            if (token == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("MISSING_TOKEN", "Missing token")
                )
                return@get
            }

            val session = downloadTokenManager.consumeToken(token)
            if (session == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse("INVALID_TOKEN", "Download token not found or expired")
                )
                return@get
            }

            val timestamp =
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
            val filename = "files_$timestamp.zip"

            call.response.headers.append(HttpHeaders.ContentType, "application/octet-stream")
            call.response.headers.append(
                HttpHeaders.ContentDisposition,
                "attachment; filename=\"$filename\""
            )

            call.respondBytesWriter {
                withContext(Dispatchers.IO) {
                    streamFilesToZip(
                        this@respondBytesWriter,
                        session.paths,
                        rootDataProvider,
                        toolManagerLogger
                    )
                }
            }
        }
    }
}

private suspend fun ApplicationCall.respondError(error: Throwable) {
    val (status, code) = when (error) {
        is NoSuchElementException -> HttpStatusCode.NotFound to "NOT_FOUND"
        is SecurityException -> HttpStatusCode.Forbidden to "FORBIDDEN"
        is IllegalArgumentException -> HttpStatusCode.BadRequest to "BAD_REQUEST"
        is UnsupportedOperationException -> HttpStatusCode.NotImplemented to "NOT_SUPPORTED"
        else -> HttpStatusCode.InternalServerError to "INTERNAL_ERROR"
    }
    respond(status, ErrorResponse(code, error.message ?: "Unknown error"))
}

private const val ZIP_STREAM_CHUNK_SIZE = 32768

private suspend fun streamFilesToZip(
    output: ByteWriteChannel,
    paths: List<String>,
    dataProvider: LeafDataTree,
    toolManagerLogger: Logger
) {
    val zipBuffer = ByteArrayOutputStream(ZIP_STREAM_CHUNK_SIZE)
    val zipOutput = ZipOutputStream(zipBuffer)
    val readBuffer = ByteArray(ZIP_STREAM_CHUNK_SIZE)

    suspend fun flushZipBuffer() {
        if (zipBuffer.size() > 0) {
            output.writeFully(zipBuffer.toByteArray())
            zipBuffer.reset()
        }
    }

    try {
        for (filePath in paths) {
            try {
                val filename = filePath.substringAfterLast('/')
                dataProvider.getBytes(Path.of(filePath)).getOrThrow().use { input ->
                    withContext(Dispatchers.IO) { zipOutput.putNextEntry(ZipEntry(filename)) }
                    // Read+compress+flush one chunk at a time and hand it to the network right
                    // away, instead of copying the whole file into zipOutput
                    while (true) {
                        val bytesRead = withContext(Dispatchers.IO) {
                            val n = input.read(readBuffer)
                            if (n != -1) {
                                zipOutput.write(readBuffer, 0, n)
                                zipOutput.flush()
                            }
                            n
                        }
                        if (bytesRead == -1) break
                        flushZipBuffer()
                    }
                    withContext(Dispatchers.IO) {
                        zipOutput.closeEntry()
                        zipOutput.flush()
                    }
                }
                flushZipBuffer()
            } catch (e: Exception) {
                toolManagerLogger.reportError(TAG, e, "Failed to add path to zip")
            }
        }

        withContext(Dispatchers.IO) {
            zipOutput.finish()
        }
        flushZipBuffer()
    } finally {
        withContext(Dispatchers.IO) {
            zipOutput.close()
        }
        output.flushAndClose()
    }
}
