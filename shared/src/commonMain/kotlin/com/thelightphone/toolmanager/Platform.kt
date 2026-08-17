package com.thelightphone.toolmanager

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform