@file:OptIn(ExperimentalWasmJsInterop::class)

package com.thelightphone.filemanager

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.browser.sessionStorage
import kotlinx.browser.window
import kotlin.js.ExperimentalWasmJsInterop

private const val API_KEY_STORAGE_KEY = "apiKey"

@JsFun("function(url) { var link = document.createElement('a'); link.href = url; document.body.appendChild(link); link.click(); document.body.removeChild(link); }")
private external fun triggerDownloadJs(url: String)

actual fun triggerDownload(url: String) = triggerDownloadJs(url)

actual fun pushBrowserState(path: String?) {
    val hash = if (path != null) "#$path" else "#"
    window.history.pushState(null, "", hash)
}

actual fun onBrowserBack(handler: (path: String?) -> Unit) {
    window.onpopstate = {
        val hash = window.location.hash.removePrefix("#")
        handler(hash.ifEmpty { null })
    }
}

actual fun triggerFilePicker(
    onFileSelected: (fileName: String, bytes: ByteArray) -> Unit,
    onCancelled: () -> Unit
) {
    // TODO: implement for wasmJs if needed
}

private var cachedApiKey: String? = null
private var apiKeyExtracted = false

actual fun getApiKey(): String? {
    if (!apiKeyExtracted) {
        apiKeyExtracted = true
        val hash = window.location.hash.removePrefix("#")
        if (hash.length == 64 && hash.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            cachedApiKey = hash
            // Persist before stripping the hash below, so a later refresh (which has no hash to
            // read) can still recover the key.
            sessionStorage.setItem(API_KEY_STORAGE_KEY, hash)
            window.history.replaceState(null, "", window.location.pathname)
        } else {
            cachedApiKey = sessionStorage.getItem(API_KEY_STORAGE_KEY)
        }
    }
    return cachedApiKey
}

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
