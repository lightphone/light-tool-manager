package com.thelightphone.filemanager

fun Throwable?.loggable() = this?.let { Exception(it) }