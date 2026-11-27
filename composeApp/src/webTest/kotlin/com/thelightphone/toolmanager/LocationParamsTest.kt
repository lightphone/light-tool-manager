package com.thelightphone.toolmanager

import kotlinx.browser.sessionStorage
import kotlinx.browser.window
import kotlin.test.Test
import kotlin.test.assertEquals

class LocationParamsTest {
    @Test
    fun returnAndBackUrlsAreReadAndStrippedFromTheAddressBar() {
        val original = window.location.href
        sessionStorage.clear()
        window.history.replaceState(
            null,
            "",
            window.location.pathname +
                "?resumeJob=a%2Fb" +
                "&return_url=https%3A%2F%2Fdash.example%2Fdevices%2F1%2Fconnect%3Fpath%3Dalbum%26lost%3Dtrue" +
                "&back_url=https%3A%2F%2Fdash.example%2Fdevices%2F1%2Ftools" +
                "#k3y/album"
        )
        try {
            assertEquals("https://dash.example/devices/1/connect?path=album&lost=true", getReturnUrl())
            assertEquals("https://dash.example/devices/1/tools", getBackUrl())
            assertEquals("k3y", getApiKey())
            assertEquals("album", getInitialDeepLinkPath())
            assertEquals("?resumeJob=a%2Fb", window.location.search)
            assertEquals("#/album", window.location.hash)
        } finally {
            window.history.replaceState(null, "", original)
            sessionStorage.clear()
        }
    }
}
