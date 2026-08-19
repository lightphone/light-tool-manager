package com.thelightphone.toolmanager

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.encodedPath
import kotlin.time.Clock

// Signs every outgoing request with apiKey instead of sending it as-is
// see server/README.md's "HTTPS via local-ip.co" section
fun HttpClientConfig<*>.installRequestSigning(apiKey: String) {
    install(createClientPlugin("RequestSigning") {
        onRequest { request, _ ->
            val timestampMillis = Clock.System.now().toEpochMilliseconds()
            val signature = signRequest(
                apiKey,
                request.method.value,
                request.url.encodedPath,
                timestampMillis
            )
            request.headers.append(TimestampHeader, timestampMillis.toString())
            request.headers.append(SignatureHeader, signature)
        }
    })
}
