package com.thelightphone.toolmanager

import org.kotlincrypto.macs.hmac.sha2.HmacSHA256

// HMAC-SHA256 request signing. See server/README.md's "HTTPS via local-ip.co" section

const val SignatureHeader = "X-Tm-Signature"
const val TimestampHeader = "X-Tm-Timestamp"
const val SignatureQueryParam = "sig"
const val TimestampQueryParam = "ts"

const val SignatureToleranceMillis = 5 * 60 * 1000L

@OptIn(ExperimentalStdlibApi::class)
fun hmacSha256Hex(keyHex: String, message: String): String =
    HmacSHA256(keyHex.hexToByteArray()).doFinal(message.encodeToByteArray()).toHexString()

fun signingCanonicalString(method: String, path: String, timestampMillis: Long): String =
    "$method\n$path\n$timestampMillis"

fun signRequest(keyHex: String, method: String, path: String, timestampMillis: Long): String =
    hmacSha256Hex(keyHex, signingCanonicalString(method, path, timestampMillis))

// Same-length, early-exit-free comparison so verifying a guessed signature doesn't leak timing
// information about how many leading hex characters it got right.
fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
    return diff == 0
}
