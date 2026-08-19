package com.thelightphone.toolmanager

import kotlin.test.Test
import kotlin.test.assertEquals

class RequestSigningTest {

    @Test
    fun signRequestMatchesKnownVector() {
        val key = "bbe2581960e16bd3ca759724c96d3995504b2a55815fcab6eda031bd46ab3ce4"
        val signature = signRequest(key, "GET", "/api/tree/", 1700000000000L)
        assertEquals("17a836c08d1c4ebb10a5b4302f7bdd5dd205deb17d7b369c8376e876301c6ceb", signature)
    }

    @Test
    fun constantTimeEqualsMatchesRegularEquals() {
        assertEquals(true, constantTimeEquals("abc123", "abc123"))
        assertEquals(false, constantTimeEquals("abc123", "abc124"))
        assertEquals(false, constantTimeEquals("abc123", "abc12"))
    }
}
