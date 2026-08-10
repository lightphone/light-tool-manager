package com.thelightphone.filemanager

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import kotlinx.browser.window
import androidx.compose.ui.text.platform.Font as SkikoFont

actual fun getBaseUrl(): String = window.location.origin

internal actual fun fontFromBytes(identity: String, data: ByteArray, weight: FontWeight): Font? =
    SkikoFont(identity, data, weight)
