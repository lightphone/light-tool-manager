package com.thelightphone.toolmanager

import io.ktor.http.decodeURLPart
import io.ktor.http.encodeURLPath

// #<apiKey>            first visit with credentials, e.g. the link the server prints
// #/Photos/Vacation    deep link to a node, auth key (if any) comes from session storage
// #<apiKey>/Photos     both
// Paths are a spec.path (slash separated, no leading slash), percent-encoded per segment.
data class ParsedHash(val apiKey: String?, val path: String?)

fun parseLocationHash(hash: String): ParsedHash {
    val raw = hash.removePrefix("#")
    val slash = raw.indexOf('/')
    val key = (if (slash < 0) raw else raw.substring(0, slash)).ifEmpty { null }
    val path = if (slash < 0) null else raw.substring(slash + 1).decodeURLPart().trim('/').ifEmpty { null }
    return ParsedHash(key, path)
}

fun buildLocationHash(path: String?): String =
    if (path.isNullOrEmpty()) "#" else "#/" + path.encodeURLPath()

fun pathChain(path: String): List<String> {
    val segments = path.split('/').filter { it.isNotEmpty() }
    return segments.indices.map { segments.take(it + 1).joinToString("/") }
}
