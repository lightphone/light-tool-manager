package com.thelightphone.toolmanager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeepLinkTest {

    @Test
    fun keyOnly() {
        assertEquals(ParsedHash("abc123", null), parseLocationHash("#abc123"))
    }

    @Test
    fun pathOnly() {
        assertEquals(ParsedHash(null, "Photos/Vacation"), parseLocationHash("#/Photos/Vacation"))
    }

    @Test
    fun keyAndPath() {
        assertEquals(ParsedHash("abc123", "Photos"), parseLocationHash("#abc123/Photos"))
    }

    @Test
    fun rootHashes() {
        assertEquals(ParsedHash(null, null), parseLocationHash(""))
        assertEquals(ParsedHash(null, null), parseLocationHash("#"))
        assertEquals(ParsedHash(null, null), parseLocationHash("#/"))
    }

    @Test
    fun pathRoundTripsWithSpecialCharacters() {
        val path = "Photos/My Trip #1/café"
        val hash = buildLocationHash(path)
        assertEquals(path, parseLocationHash(hash).path)
        assertNull(parseLocationHash(buildLocationHash(null)).path)
    }

    @Test
    fun chainListsAncestorsTopDown() {
        assertEquals(listOf("a", "a/b", "a/b/c"), pathChain("a/b/c"))
    }
}
