package com.thelightphone.toolmanager

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import com.thelightphone.filemanager.Remote
import io.ktor.http.HttpStatusCode

expect fun getBaseUrl(): String

expect fun triggerDownload(url: String)

// Full-page navigation to an external URL (e.g. a job's OAuth-style redirectUrl) - not a download,
// not an in-app route change.
expect fun navigateToExternalUrl(url: String)

// Reads and clears the one-shot ?resumeJob=<path>&jobId=<id> query params a job-callback redirect
// (see JobCallbackPath in Application.kt) lands the browser on, returning (path, jobId) if
// present. Not the URL hash: the app's pairing bootstrap already overloads a non-empty hash on
// first load to mean "this is a pairing key" (see getApiKey), so reusing it here would clobber an
// already-paired session's key on every callback-driven reload.
expect fun consumeResumeJobParams(): Pair<String, String>?

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
// (~100MB+) uploads, that intermediate allocation throws "invalid array length" in the
// browser. Platform actuals that don't have this problem (JVM/Android) can just delegate to
// Remote.uploadBytes internally.
expect suspend fun platformUploadOctetStream(
    remote: Remote,
    url: String,
    bytes: ByteArray,
    timeoutMillis: Long,
): HttpStatusCode

// Constructing a Font straight from raw bytes is only available on skiko-backed targets (JS,
// wasmJs)
internal expect fun fontFromBytes(identity: String, data: ByteArray, weight: FontWeight): Font?
