package com.thelightphone.toolmanager

/**
 * Where a computer at the other end of USB tether reaches the tool manager.
 *
 * [mdnsHostname] is the full name to type, e.g. `lightphone-1234.local`. [ipAddress] is the phone's
 * IPv4 address on the tether interface; it's assigned per tethering session, so it's null until
 * tethering is up. [ipv6Address] is the phone's unique-local IPv6 address there: reachable from the
 * computer without DHCP or name resolution, and null until tethering has assigned it. [apiKey] is
 * the server's current key , or null when running without auth.
 */
data class TetherEndpoint(
    val mdnsHostname: String,
    val ipAddress: String?,
    val port: Int,
    val apiKey: String?,
    val ipv6Address: String? = null,
) {
    // Keep the key out of logs.
    override fun toString(): String =
        "TetherEndpoint(mdnsHostname=$mdnsHostname, ipAddress=$ipAddress, ipv6Address=$ipv6Address, port=$port, " +
            "apiKey=${if (apiKey == null) "null" else "<redacted>"})"
}

val TetherEndpoint.mdnsUrl: String get() = "http://$mdnsHostname:$port"

/** null until tethering is up */
val TetherEndpoint.ipUrl: String? get() = ipAddress?.let { "http://$it:$port" }

/** null until tethering has assigned the IPv6 address */
val TetherEndpoint.ipv6Url: String? get() = ipv6Address?.let { "http://[$it]:$port" }

/** [ipv6Url] with the key in the fragment, or null until the IPv6 address is assigned. */
val TetherEndpoint.authenticatedIpv6Url: String? get() = ipv6Url?.let(::withKey)

val TetherEndpoint.authenticatedMdnsUrl: String get() = withKey(mdnsUrl)

/** [ipUrl] with the key in the fragment, or null until tethering is up. */
val TetherEndpoint.authenticatedIpUrl: String? get() = ipUrl?.let(::withKey)

private fun TetherEndpoint.withKey(url: String): String = if (apiKey == null) url else "$url/#$apiKey"
