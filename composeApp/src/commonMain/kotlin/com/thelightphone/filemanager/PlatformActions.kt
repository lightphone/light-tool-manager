package com.thelightphone.filemanager

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode

expect fun getBaseUrl(): String

expect fun triggerDownload(url: String)

// path is null for root, non-null for a directory
expect fun pushBrowserState(path: String?)

expect fun onBrowserBack(handler: (path: String?) -> Unit)

expect fun triggerFilePicker(
    multiple: Boolean = true,
    onFilesSelected: (files: List<Pair<String, ByteArray>>) -> Unit,
    onCancelled: () -> Unit = {}
)

expect fun getApiKey(): String?

// Bypasses HttpClient.post/setBody on purpose: ktor-client-js unconditionally routes every
// outgoing request body through a conversion that copies the whole payload into a boxed plain
// JS Array before re-wrapping it as a Uint8Array, no matter how setBody was called. For large
// (~100MB+) uploads that intermediate allocation is what throws "invalid array length" in the
// browser. Platform actuals that don't have this problem (JVM/Android) can just delegate to
// client.post internally.
expect suspend fun uploadOctetStream(
    client: HttpClient,
    url: String,
    bytes: ByteArray,
    timeoutMillis: Long,
): HttpStatusCode

// Constructing a Font straight from raw bytes is only available on skiko-backed targets (JS,
// wasmJs) — the real androidx Android artifact has no such factory, only resource-ID-based
// fonts. Returns null there, which is fine: Android isn't a currently-supported target anyway.
internal expect fun fontFromBytes(identity: String, data: ByteArray, weight: FontWeight): Font?
