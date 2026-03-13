package com.thelightphone.filemanager

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform