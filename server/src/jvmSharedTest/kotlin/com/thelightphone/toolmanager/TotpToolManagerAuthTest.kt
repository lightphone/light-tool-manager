package com.thelightphone.toolmanager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class TotpToolManagerAuthTest {
    private var nowMillis = 1_700_000_012_345L
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(nowMillis)
    }

    @Test
    fun currentCodeReportsItsWindow() {
        val auth = TotpToolManagerAuth(clock = clock)

        val code = auth.currentCode()
        assertEquals(6, code.code.length)
        assertEquals(30_000L, code.durationMillis)
        assertEquals(nowMillis - nowMillis % 30_000, code.issuedAtMillis)
        assertTrue(nowMillis in code.issuedAtMillis until code.expiresAtMillis)
        assertNotNull(auth.mintKey(code.code))

        // The next code's window starts exactly where this one's ends.
        nowMillis = code.expiresAtMillis
        val next = auth.currentCode()
        assertEquals(code.expiresAtMillis, next.issuedAtMillis)
        assertNotNull(auth.mintKey(next.code))
    }
}
