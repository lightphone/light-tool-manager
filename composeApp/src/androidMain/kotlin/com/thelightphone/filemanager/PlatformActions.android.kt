package com.thelightphone.filemanager

// ANDROID NOT CURRENTLY SUPPORTED
// WE JUST NEED AN ACTIVE BUILD TO GET COMPOSE PREVIEWS TO SHOW
actual fun getBaseUrl(): String = "http://10.0.2.2:8080"

actual fun triggerDownload(url: String) {}

actual fun pushBrowserState(path: String?) {}

actual fun onBrowserBack(handler: (path: String?) -> Unit) {}
actual fun triggerFilePicker(onFileSelected: (fileName: String, bytes: ByteArray) -> Unit) {}