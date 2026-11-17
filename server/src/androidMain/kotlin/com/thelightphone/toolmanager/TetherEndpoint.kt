package com.thelightphone.toolmanager

/**
 * Where a computer at the other end of USB tether reaches the tool manager.
 *
 * [mdnsHostname] is the full name to type, e.g. `lightphone-1234.local`. [ipAddress] is the phone's
 * IPv4 address on the tether interface; it's assigned per tethering session, so it's null until
 * tethering is up. [apiKey] is the server's current key , or null when running without auth.
 */
data class TetherEndpoint(
    val mdnsHostname: String,
    val ipAddress: String?,
    val port: Int,
    val apiKey: String?,
) {
    // Keep the key out of logs.
    override fun toString(): String =
        "TetherEndpoint(mdnsHostname=$mdnsHostname, ipAddress=$ipAddress, port=$port, " +
            "apiKey=${if (apiKey == null) "null" else "<redacted>"})"
}

val TetherEndpoint.mdnsUrl: String get() = "http://$mdnsHostname:$port"

/** null until tethering is up */
val TetherEndpoint.ipUrl: String? get() = ipAddress?.let { "http://$it:$port" }

val TetherEndpoint.authenticatedMdnsUrl: String get() = withKey(mdnsUrl)

/** [ipUrl] with the key in the fragment, or null until tethering is up. */
val TetherEndpoint.authenticatedIpUrl: String? get() = ipUrl?.let(::withKey)

private fun TetherEndpoint.withKey(url: String): String = if (apiKey == null) url else "$url/#$apiKey"
