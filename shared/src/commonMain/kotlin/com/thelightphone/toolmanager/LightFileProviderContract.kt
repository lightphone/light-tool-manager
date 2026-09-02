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

// Generic Map<String, String> <-> String codec for call()/Bundle payloads that aren't entry
// metadata specifically (e.g. job params) - same encoding as encodeEntryMeta/decodeEntryMeta,
// just under a name that doesn't imply it's about directory entries.
fun encodeStringMap(map: Map<String, String>): String = Json.encodeToString(map)
fun decodeStringMap(raw: String): Map<String, String> = Json.decodeFromString(raw)

// <provider> <meta-data> key a LightFileProvider-compatible provider must declare (value
// "true")
const val META_DATA_TOOL_MANAGER_PROVIDER = "com.thelightphone.toolmanager.TOOL_MANAGER_PROVIDER"

// ContentProvider.call() method name used to fetch a client's serialized ClientToolManifest
const val METHOD_GET_MANIFEST = "get_manifest"
const val RESULT_MANIFEST = "manifest"

// ContentProvider.call() methods bridging LeafDataTree's job support (see JobDataTree /
// DataTree.kt's startJob/getJobStatus/completeJob) to a third-party tool's own LightFileProvider.
// For all three, `arg` is the DataTree path (relative to the provider's own root, same convention
// as every other call in this file) and results are JSON-encoded via kotlinx.serialization,
// reusing the same JobStartResponse/JobStatusResponse DTOs the HTTP API itself uses - this is the
// same shape of information, just carried over Binder instead of HTTP.
const val METHOD_START_JOB = "start_job"
const val METHOD_JOB_STATUS = "job_status"
const val METHOD_COMPLETE_JOB = "complete_job"

// call() extras (request side)
const val EXTRA_JOB_ID = "job_id"
const val EXTRA_PARAMS = "params" // JSON-encoded Map<String, String>, via encodeStringMap
const val EXTRA_CALLBACK_URL = "callback_url"
const val EXTRA_DATA = "data" // JSON-encoded Map<String, String>, via encodeStringMap

// call() result Bundle keys (response side)
const val RESULT_JOB_START = "job_start" // JSON-encoded JobStartResponse
const val RESULT_JOB_STATUS = "job_status" // JSON-encoded JobStatusResponse
const val RESULT_COMPLETE_JOB_SUCCESS = "complete_job_success" // boolean
