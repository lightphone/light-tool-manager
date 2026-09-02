package com.thelightphone.toolmanager

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class JobStartResponse(val jobId: String, val redirectUrl: String? = null) {
    // encode()/decode() so consumers (e.g. the client module's LightFileProvider, over the
    // ContentProvider call() bridge) never need direct kotlinx.serialization.json.Json access of
    // their own - same pattern as ClientToolManifest.
    fun encode(): String = Json.encodeToString(serializer(), this)

    companion object {
        fun decode(raw: String): JobStartResponse = Json.decodeFromString(serializer(), raw)
    }
}

@Serializable
data class JobStartRequest(val params: Map<String, String> = emptyMap())

@Serializable
enum class JobState { PENDING, RUNNING, SUCCEEDED, FAILED }

@Serializable
data class JobStatusResponse(
    val jobId: String,
    val status: JobState,
    val resultPath: String? = null,
    val message: String? = null
) {
    fun encode(): String = Json.encodeToString(serializer(), this)

    companion object {
        fun decode(raw: String): JobStatusResponse = Json.decodeFromString(serializer(), raw)
    }
}
