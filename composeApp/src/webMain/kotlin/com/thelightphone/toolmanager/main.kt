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
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders

@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoilApi::class)
fun main() {
    val apiKey = getApiKey()
    val coilClient = if (apiKey != null) {
        HttpClient {
            defaultRequest {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }
        }
    } else {
        HttpClient()
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