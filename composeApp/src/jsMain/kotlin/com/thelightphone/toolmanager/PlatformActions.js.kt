package com.thelightphone.toolmanager

import com.thelightphone.filemanager.Remote
import io.ktor.http.HttpStatusCode
import kotlinx.browser.document
import kotlinx.browser.sessionStorage
import kotlinx.browser.window
import kotlinx.coroutines.await

private const val API_KEY_STORAGE_KEY = "apiKey"

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

actual fun triggerDownload(url: String) {
    val link = document.createElement("a")
    link.setAttribute("href", url)
    document.body?.appendChild(link)
    link.asDynamic().click()
    document.body?.removeChild(link)
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
    val input = document.createElement("input")
    input.setAttribute("type", "file")
    if (multiple) input.setAttribute("multiple", "true")

    // Set synchronously the instant `change` fires — i.e. the moment files are chosen, well
    // before any FileReader finishes reading their contents. The focus-based cancel check below
    // must gate on this, not on the reads having finished: for a large file that read can take
    // longer than the cancel-detection delay, so gating on "reads finished" would misreport an
    // in-progress large read as a cancellation (and drop the real selection on the floor, since
    // onCancelled would already have fired by the time onload runs).
    var fileWasChosen = false

    input.addEventListener("change", { event ->
        val files = event.target.asDynamic().files
        val fileCount = files.length as Int
        if (fileCount == 0) {
            fileWasChosen = true
            onCancelled()
            return@addEventListener
        }
        fileWasChosen = true

        // Files are read in parallel (local disk reads are cheap; it's the upload over the
        // network that needs throttling, done by the caller) but reported back as a single
        // ordered list once every read has finished, so callers can decide upload order/pacing
        // without juggling partial results themselves.
        val results = arrayOfNulls<Pair<String, ByteArray>>(fileCount)
        var remaining = fileCount
        for (i in 0 until fileCount) {
            val file = files[i]
            val fileName = file.name as String
            val reader = js("new FileReader()")
            reader.onload = { e: dynamic ->
                val arrayBuffer = e.target.result as org.khronos.webgl.ArrayBuffer
                // Kotlin/JS's ByteArray is backed by Int8Array at runtime, so this reinterprets
                // the raw bytes directly instead of copying element-by-element through a boxed
                // loop — for a large file (~100MB+), the boxed-loop version blocks the single JS
                // thread for a long time before the upload even starts, which looks like the
                // upload hanging.
                val byteArray = org.khronos.webgl.Int8Array(arrayBuffer).unsafeCast<ByteArray>()
                results[i] = fileName to byteArray
                remaining--
                if (remaining == 0) {
                    onFilesSelected(results.filterNotNull())
                }
            }
            reader.readAsArrayBuffer(file)
        }
    })

    // <input type="file"> has no universally-supported "cancelled" event, so cancellation is
    // detected by the window regaining focus (the native picker is modal) without `change` ever
    // having fired. The short delay only needs to cover dialog-close-to-change-event latency
    // (independent of file size), since `fileWasChosen` — not the reads completing — is what
    // gates whether this actually reports a cancel.
    lateinit var onWindowFocus: (org.w3c.dom.events.Event) -> Unit
    onWindowFocus = {
        window.removeEventListener("focus", onWindowFocus)
        window.setTimeout({ if (!fileWasChosen) onCancelled() }, 300)
    }
    window.addEventListener("focus", onWindowFocus)

    input.asDynamic().click()
}

// ktor-client-js's HttpClient always materializes the outgoing body by copying it into a boxed
// plain JS Array (`[].slice.call(int8Array)`) before re-wrapping it as a Uint8Array — this
// happens internally regardless of how setBody() is called. For a ~100MB+ file that intermediate
// copy is what throws "invalid array length" in the browser (confirmed: this exact conversion
// OOMs under constrained heap in a direct V8 test). Calling fetch() directly here sidesteps that
// entirely — native fetch accepts a typed array as the body with no such copy.
actual suspend fun platformUploadOctetStream(
    remote: Remote,
    url: String,
    bytes: ByteArray,
    timeoutMillis: Long,
): HttpStatusCode {
    val body = bytes.unsafeCast<org.khronos.webgl.Int8Array>()
    val controller = js("new AbortController()")
    val timeoutId = window.setTimeout({ controller.abort() }, timeoutMillis.toInt())

    val headers = js("({})")
    headers["Content-Type"] = "application/octet-stream"
    // The Remote's own HttpClient (with its `defaultRequest { header(Authorization, ...) }`, see
    // App.kt) never runs here since this bypasses HttpClient entirely — has to be attached by hand.
    getApiKey()?.let { headers["Authorization"] = "Bearer $it" }
    val init = js("({})")
    init.method = "POST"
    init.body = body
    init.headers = headers
    init.signal = controller.signal

    try {
        val response = (window.asDynamic().fetch(url, init) as kotlin.js.Promise<dynamic>).await()
        return HttpStatusCode.fromValue(response.status as Int)
    } finally {
        window.clearTimeout(timeoutId)
    }
}
