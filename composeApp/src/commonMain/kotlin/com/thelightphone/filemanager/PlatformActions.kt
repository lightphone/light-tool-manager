package com.thelightphone.filemanager

expect fun getBaseUrl(): String

expect fun triggerDownload(url: String)

// path is null for root, non-null for a directory
expect fun pushBrowserState(path: String?)

expect fun onBrowserBack(handler: (path: String?) -> Unit)

expect fun triggerFilePicker(onFileSelected: (fileName: String, bytes: ByteArray) -> Unit)
