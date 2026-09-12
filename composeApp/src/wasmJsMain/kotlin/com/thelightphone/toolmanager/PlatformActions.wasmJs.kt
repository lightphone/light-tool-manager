@file:OptIn(ExperimentalWasmJsInterop::class)

package com.thelightphone.toolmanager

import com.thelightphone.filemanager.Remote
import io.ktor.http.HttpStatusCode
import kotlinx.browser.sessionStorage
import kotlinx.browser.window
import kotlin.js.ExperimentalWasmJsInterop

private const val API_KEY_STORAGE_KEY = "apiKey"

@JsFun("function(url) { var link = document.createElement('a'); link.href = url; document.body.appendChild(link); link.click(); document.body.removeChild(link); }")
private external fun triggerDownloadJs(url: String)

actual fun triggerDownload(url: String) = triggerDownloadJs(url)

actual fun navigateToExternalUrl(url: String) {
    window.open(url, "_blank")
}

// Returns an empty string if either param is absent, otherwise "PATH\nJOBID" - a newline
// separator, since URL query param values can't themselves contain a raw newline. One external JS
// call instead of two separate ones for path/jobId that could observe the URL differently if
// something mutated it in between.
@JsFun(
    """
    function() {
        var params = new URLSearchParams(window.location.search);
        var path = params.get('resumeJob');
        var jobId = params.get('jobId');
        if (!path || !jobId) return '';
        window.history.replaceState(null, '', window.location.pathname + window.location.hash);
        return path + '\n' + jobId;
    }
    """
)
private external fun consumeResumeJobParamsJs(): String

actual fun consumeResumeJobParams(): Pair<String, String>? {
    val raw = consumeResumeJobParamsJs()
    if (raw.isEmpty()) return null
    val (path, jobId) = raw.split('\n', limit = 2)
    return path to jobId
}

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
    multiple: Boolean,
    onFilesSelected: (files: List<Pair<String, ByteArray>>) -> Unit,
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
        if (hash.isNotEmpty()) {
            cachedApiKey = hash
            sessionStorage.setItem(API_KEY_STORAGE_KEY, hash)
            window.history.replaceState(null, "", window.location.pathname)
        } else {
            cachedApiKey = sessionStorage.getItem(API_KEY_STORAGE_KEY)
        }
    }
    return cachedApiKey
}

actual suspend fun platformUploadOctetStream(
    remote: Remote,
    url: String,
    bytes: ByteArray,
    timeoutMillis: Long,
): HttpStatusCode = remote.uploadBytes(url, bytes, timeoutMillis)
