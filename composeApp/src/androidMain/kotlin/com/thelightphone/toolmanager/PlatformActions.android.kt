package com.thelightphone.toolmanager

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import com.thelightphone.filemanager.Remote
import io.ktor.http.HttpStatusCode

// ANDROID NOT CURRENTLY SUPPORTED
// WE JUST NEED AN ACTIVE BUILD TO GET COMPOSE PREVIEWS TO SHOW
actual fun getBaseUrl(): String = "http://10.0.2.2:8080"

actual fun triggerDownload(url: String) {}

actual fun pushBrowserState(path: String?) {}

actual fun onBrowserBack(handler: (path: String?) -> Unit) {}
actual fun triggerFilePicker(
    multiple: Boolean,
    onFilesSelected: (files: List<Pair<String, ByteArray>>) -> Unit,
    onCancelled: () -> Unit
) {}
actual fun getApiKey(): String? = null

actual suspend fun platformUploadOctetStream(
    remote: Remote,
    url: String,
    bytes: ByteArray,
    timeoutMillis: Long,
): HttpStatusCode = remote.uploadBytes(url, bytes, timeoutMillis)

internal actual fun fontFromBytes(identity: String, data: ByteArray, weight: FontWeight): Font? = null