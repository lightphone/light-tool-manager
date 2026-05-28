@file:OptIn(ExperimentalWasmJsInterop::class)

package com.thelightphone.filemanager

import kotlinx.browser.window
import kotlin.js.ExperimentalWasmJsInterop

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

actual fun triggerFilePicker(onFileSelected: (fileName: String, bytes: ByteArray) -> Unit) {
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
            window.history.replaceState(null, "", window.location.pathname)
        }
    }
    return cachedApiKey
}
