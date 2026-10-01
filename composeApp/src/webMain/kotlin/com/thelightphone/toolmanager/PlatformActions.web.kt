@file:OptIn(ExperimentalWasmJsInterop::class)

package com.thelightphone.toolmanager

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import kotlinx.browser.sessionStorage
import kotlinx.browser.window
import kotlin.js.ExperimentalWasmJsInterop
import androidx.compose.ui.text.platform.Font as SkikoFont

actual fun getBaseUrl(): String = window.location.origin

internal actual fun fontFromBytes(identity: String, data: ByteArray, weight: FontWeight): Font? =
    SkikoFont(identity, data, weight)

private const val API_KEY_STORAGE_KEY = "apiKey"

private var cachedApiKey: String? = null
private var initialPath: String? = null
private var locationExtracted = false

// Reads the auth key and deep link path out of the URL fragment once, then strips the key from
// the visible URL (leaving the path, so the address bar stays shareable).
private fun extractFromLocation() {
    if (locationExtracted) return
    locationExtracted = true
    val parsed = parseLocationHash(window.location.hash)
    initialPath = parsed.path
    if (parsed.apiKey != null) {
        cachedApiKey = parsed.apiKey
        sessionStorage.setItem(API_KEY_STORAGE_KEY, parsed.apiKey)
        window.history.replaceState(null, "", window.location.pathname + buildLocationHash(parsed.path))
    } else {
        cachedApiKey = sessionStorage.getItem(API_KEY_STORAGE_KEY)
    }
}

actual fun getApiKey(): String? {
    extractFromLocation()
    return cachedApiKey
}

actual fun getInitialDeepLinkPath(): String? {
    extractFromLocation()
    return initialPath
}

actual fun pushBrowserState(path: String?) {
    window.history.pushState(null, "", buildLocationHash(path))
}

actual fun replaceBrowserState(path: String?) {
    window.history.replaceState(null, "", buildLocationHash(path))
}

actual fun onBrowserBack(handler: (path: String?) -> Unit) {
    window.onpopstate = {
        handler(parseLocationHash(window.location.hash).path)
    }
}
