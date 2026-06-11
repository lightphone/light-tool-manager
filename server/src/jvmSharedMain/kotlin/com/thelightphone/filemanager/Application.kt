package com.thelightphone.filemanager

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

fun generateApiKey(): String {
    val bytes = ByteArray(32)
    java.security.SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

private const val TAG = "FileManagerServer"

fun Application.module(
    rootDataProvider: RootDataProvider,
    enableLogging: Boolean,
    fileManagerLogger: Logger,
    apiKey: String? = null
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
                        Level.ERROR -> fileManagerLogger.reportError(
                            TAG,
                            throwable?.let { Exception(it) },
                            clean
                        )

                        Level.INFO, Level.DEBUG, Level.TRACE, Level.WARN -> fileManagerLogger.log(
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


    if (apiKey != null) {
        intercept(ApplicationCallPipeline.Plugins) {
            val path = call.request.path()
            if (path.startsWith("/api/")) {
                val auth = call.request.header(HttpHeaders.Authorization)
                val queryKey = call.request.queryParameters["key"]
                if (auth != "Bearer $apiKey" && queryKey != apiKey) {
                    call.respond(HttpStatusCode.Unauthorized)
                    finish()
                    return@intercept
                }
            }
        }
    } else {
        fileManagerLogger.log(TAG, "WARNING: File Manager server running with no api key!")
    }

    val downloadTokenManager = DownloadTokenManager()

    routing {
        staticResources("/", "static") {
            default("index.html")
        }

        get("/ping") {
            call.respond(HttpStatusCode.OK)
        }

        get("/api/root") {
            call.respond(HttpStatusCode.OK, rootDataProvider.getRoot())
        }

        // returns DirectoryMeta
        get("/api/meta/{path...}") {
            val filePath = call.parameters.getAll("path")?.joinToString("/") ?: "."
            rootDataProvider.getMeta(Path.of(filePath)).fold(
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
                    fileManagerLogger.reportError(TAG, t.loggable(), "Error uploading")
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
                        fileManagerLogger
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
        else -> HttpStatusCode.InternalServerError to "INTERNAL_ERROR"
    }
    respond(status, ErrorResponse(code, error.message ?: "Unknown error"))
}

private suspend fun streamFilesToZip(
    output: ByteWriteChannel,
    paths: List<String>,
    dataProvider: DataProvider,
    fileManagerLogger: Logger
) {
    val zipBuffer = ByteArrayOutputStream(32768)
    val zipOutput = ZipOutputStream(zipBuffer)

    try {
        for (filePath in paths) {
            try {
                val filename = filePath.substringAfterLast('/')
                withContext(Dispatchers.IO) {
                    zipOutput.putNextEntry(ZipEntry(filename))
                    dataProvider.getBytes(Path.of(filePath)).getOrThrow()
                        .use { it.transferTo(zipOutput) }
                    zipOutput.closeEntry()
                    zipOutput.flush()
                    if (zipBuffer.size() > 0) {
                        output.writeFully(zipBuffer.toByteArray())
                        zipBuffer.reset()
                    }
                }
            } catch (e: Exception) {
                fileManagerLogger.reportError(TAG, e, "Failed to add path to zip")
            }
        }

        withContext(Dispatchers.IO) {
            zipOutput.finish()
        }
        if (zipBuffer.size() > 0) {
            output.writeFully(zipBuffer.toByteArray())
        }
    } finally {
        withContext(Dispatchers.IO) {
            zipOutput.close()
        }
        output.flushAndClose()
    }
}
