package com.thelightphone.toolmanager

fun Throwable?.loggable() = this?.let { Exception(it) }