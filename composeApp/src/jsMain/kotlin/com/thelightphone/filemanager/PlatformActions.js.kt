package com.thelightphone.filemanager

import kotlinx.browser.document
import kotlinx.browser.window

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

actual fun triggerFilePicker(onFileSelected: (fileName: String, bytes: ByteArray) -> Unit) {
    val input = document.createElement("input")
    input.setAttribute("type", "file")
    input.addEventListener("change", { event ->
        val files = event.target.asDynamic().files
        val file = files[0] ?: return@addEventListener
        val reader = js("new FileReader()")
        reader.onload = { e: dynamic ->
            val arrayBuffer = e.target.result
            val uint8Array = js("new Uint8Array(arrayBuffer)")
            val length = uint8Array.length as Int
            val byteArray = ByteArray(length) { i -> (uint8Array[i] as Number).toByte() }
            onFileSelected(file.name as String, byteArray)
        }
        reader.readAsArrayBuffer(file)
    })
    input.asDynamic().click()
}
