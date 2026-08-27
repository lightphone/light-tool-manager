package com.thelightphone.toolmanager

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Cursor column names used by LightFileProvider-compatible ContentProviders (see the
// client module's LightFileProvider) and read by ContentResolverFileTree on the server
// side. Shared here so the two sides of that contract can't drift apart.
const val COLUMN_IS_DIRECTORY = "is_directory"
const val COLUMN_LAST_MODIFIED = "last_modified"

// Optional, JSON-encoded Map<String, String>
const val COLUMN_META = "meta"

fun encodeEntryMeta(meta: Map<String, String>): String = Json.encodeToString(meta)
fun decodeEntryMeta(raw: String): Map<String, String> = Json.decodeFromString(raw)

// <provider> <meta-data> key a LightFileProvider-compatible provider must declare (value
// "true")
const val META_DATA_TOOL_MANAGER_PROVIDER = "com.thelightphone.toolmanager.TOOL_MANAGER_PROVIDER"

// ContentProvider.call() method name used to fetch a client's serialized ClientToolManifest
const val METHOD_GET_MANIFEST = "get_manifest"
const val RESULT_MANIFEST = "manifest"
