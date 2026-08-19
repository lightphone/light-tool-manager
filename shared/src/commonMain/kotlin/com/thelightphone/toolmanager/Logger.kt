package com.thelightphone.toolmanager

interface Logger {
    fun log(tag: String, message: String)
    fun reportError(tag: String, exception: Throwable?,  message: String)
}