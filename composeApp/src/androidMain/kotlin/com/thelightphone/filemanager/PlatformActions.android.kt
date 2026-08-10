package com.thelightphone.filemanager

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType

// ANDROID NOT CURRENTLY SUPPORTED
// WE JUST NEED AN ACTIVE BUILD TO GET COMPOSE PREVIEWS TO SHOW
actual fun getBaseUrl(): String = "http://10.0.2.2:8080"

actual fun triggerDownload(url: String) {}

actual fun pushBrowserState(path: String?) {}

actual fun onBrowserBack(handler: (path: String?) -> Unit) {}
actual fun triggerFilePicker(
    onFileSelected: (fileName: String, bytes: ByteArray) -> Unit,
    onCancelled: () -> Unit
) {}
actual fun getApiKey(): String? = null

actual suspend fun uploadOctetStream(
    client: HttpClient,
    url: String,
    bytes: ByteArray,
    timeoutMillis: Long,
): HttpStatusCode {
    val response = client.post(url) {
        contentType(ContentType.Application.OctetStream)
        setBody(bytes)
        timeout { requestTimeoutMillis = timeoutMillis }
    }
    return response.status
}

internal actual fun fontFromBytes(identity: String, data: ByteArray, weight: FontWeight): Font? = null