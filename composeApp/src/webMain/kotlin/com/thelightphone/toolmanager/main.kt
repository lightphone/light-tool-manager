package com.thelightphone.toolmanager

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import coil3.util.DebugLogger
import io.ktor.client.HttpClient

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoilApi::class)
fun main() {
    val apiKey = getApiKey()
    val coilClient = HttpClient {
        if (apiKey != null) {
            installRequestSigning(apiKey)
        }
    }

    SingletonImageLoader.setSafe {
        ImageLoader.Builder(PlatformContext.INSTANCE)
            .components {
                add(KtorNetworkFetcherFactory(httpClient = coilClient))
            }
            .crossfade(true)
            .logger(DebugLogger())
            .build()
    }

    ComposeViewport {
        App()
    }
}