package com.thelightphone.filemanager

interface Logger {
    fun log(tag: String, message: String)
    fun reportError(tag: String, exception: Exception?,  message: String)
}