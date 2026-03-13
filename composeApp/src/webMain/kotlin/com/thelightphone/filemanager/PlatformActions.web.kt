package com.thelightphone.filemanager

import kotlinx.browser.window

actual fun getBaseUrl(): String = window.location.origin
