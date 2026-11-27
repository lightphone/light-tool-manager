@file:OptIn(ExperimentalWasmJsInterop::class)

package com.thelightphone.toolmanager

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import kotlinx.browser.sessionStorage
import kotlinx.browser.window
import org.w3c.dom.url.URLSearchParams
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.toJsString
import androidx.compose.ui.text.platform.Font as SkikoFont

actual fun getBaseUrl(): String = window.location.origin

internal actual fun fontFromBytes(identity: String, data: ByteArray, weight: FontWeight): Font? =
    SkikoFont(identity, data, weight)

private const val RETURN_URL_QUERY_PARAM = "return_url"
private const val BACK_URL_QUERY_PARAM = "back_url"

private const val API_KEY_STORAGE_KEY = "apiKey"
private const val RETURN_URL_STORAGE_KEY = "returnUrl"
private const val BACK_URL_STORAGE_KEY = "backUrl"

private class PageParameters(
    val apiKey: String?,
    val returnUrl: String?,
    val backUrl: String?,
    val initialPath: String?,
)

private val pageParameters: PageParameters by lazy { consumePageParameters() }

// Reads the auth key and deep link path out of the URL fragment once, then strips the key from
// the visible URL (leaving the path, so the address bar stays shareable).
// Also removes the ?return_url and ?back_url query parameters.
private fun consumePageParameters(): PageParameters {
    val parsed = parseLocationHash(window.location.hash)
    val query = URLSearchParams(window.location.search.toJsString())
    val freshReturnUrl = query.get(RETURN_URL_QUERY_PARAM)?.ifEmpty { null }
    val freshBackUrl = query.get(BACK_URL_QUERY_PARAM)?.ifEmpty { null }
    val startsNewSession = parsed.apiKey != null

    val apiKey = if (startsNewSession) {
        sessionStorage.setItem(API_KEY_STORAGE_KEY, parsed.apiKey)
        parsed.apiKey
    } else {
        sessionStorage.getItem(API_KEY_STORAGE_KEY)
    }

    val returnUrl = if (freshReturnUrl != null) {
        sessionStorage.setItem(RETURN_URL_STORAGE_KEY, freshReturnUrl)
        freshReturnUrl
    } else if (startsNewSession) {
        sessionStorage.removeItem(RETURN_URL_STORAGE_KEY)
        null
    } else {
        sessionStorage.getItem(RETURN_URL_STORAGE_KEY)
    }

    val backUrl = if (freshBackUrl != null) {
        sessionStorage.setItem(BACK_URL_STORAGE_KEY, freshBackUrl)
        freshBackUrl
    } else if (startsNewSession) {
        sessionStorage.removeItem(BACK_URL_STORAGE_KEY)
        null
    } else {
        sessionStorage.getItem(BACK_URL_STORAGE_KEY)
    }

    val needsHistoryReplacement = startsNewSession || freshReturnUrl != null || freshBackUrl != null

    if (needsHistoryReplacement) {
        query.delete(RETURN_URL_QUERY_PARAM)
        query.delete(BACK_URL_QUERY_PARAM)
        val remainingQuery = query.toString()
        val search = if (remainingQuery.isEmpty()) "" else "?$remainingQuery"
        window.history.replaceState(
            null,
            "",
            window.location.pathname + search + buildLocationHash(parsed.path)
        )
    }

    return PageParameters(
        apiKey = apiKey,
        returnUrl = returnUrl,
        backUrl = backUrl,
        initialPath = parsed.path,
    )
}

actual fun getApiKey(): String? = pageParameters.apiKey

actual fun getReturnUrl(): String? = pageParameters.returnUrl

actual fun getBackUrl(): String? = pageParameters.backUrl

actual fun navigateToUrl(url: String) {
    window.location.assign(url)
}

// Saves a key obtained by pairing (see PairingScreen) where getApiKey() looks on the next load.
fun storeApiKey(key: String) {
    sessionStorage.setItem(API_KEY_STORAGE_KEY, key)
}

actual fun getInitialDeepLinkPath(): String? = pageParameters.initialPath

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
