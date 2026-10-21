// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.view

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SEC06 — `GM_xmlhttpRequest` must stay inside the script's declared
 * `@connect` set, and the bridge only honors ops from pages the
 * named script would actually run on.
 */
class GmConnectPolicyTest {

    @Test
    fun `no connect permits same host only`() {
        val connects = emptyList<String>()
        assertTrue(GmConnectPolicy.hostAllowed(connects, "example.com", "example.com"))
        assertFalse(GmConnectPolicy.hostAllowed(connects, "example.com", "api.example.com"))
        assertFalse(GmConnectPolicy.hostAllowed(connects, "example.com", "evil.com"))
    }

    @Test
    fun `star connect permits any host`() {
        val connects = listOf("*")
        assertTrue(GmConnectPolicy.hostAllowed(connects, "example.com", "anything.net"))
        assertTrue(GmConnectPolicy.hostAllowed(connects, null, "anything.net"))
    }

    @Test
    fun `self connect resolves to page host`() {
        val connects = listOf("self")
        assertTrue(GmConnectPolicy.hostAllowed(connects, "example.com", "example.com"))
        assertFalse(GmConnectPolicy.hostAllowed(connects, "example.com", "other.com"))
        // No page origin known → self can never match.
        assertFalse(GmConnectPolicy.hostAllowed(connects, null, "example.com"))
    }

    @Test
    fun `bare domain matches domain and subdomains`() {
        val connects = listOf("example.com")
        assertTrue(GmConnectPolicy.hostAllowed(connects, "a.io", "example.com"))
        assertTrue(GmConnectPolicy.hostAllowed(connects, "a.io", "api.example.com"))
        assertTrue(GmConnectPolicy.hostAllowed(connects, "a.io", "deep.api.example.com"))
        assertFalse(GmConnectPolicy.hostAllowed(connects, "a.io", "notexample.com"))
        assertFalse(GmConnectPolicy.hostAllowed(connects, "a.io", "example.com.evil.net"))
    }

    @Test
    fun `wildcard domain matches domain and subdomains`() {
        val connects = listOf("*.example.com")
        assertTrue(GmConnectPolicy.hostAllowed(connects, null, "api.example.com"))
        assertTrue(GmConnectPolicy.hostAllowed(connects, null, "example.com"))
        assertFalse(GmConnectPolicy.hostAllowed(connects, null, "example.org"))
    }

    @Test
    fun `url and port shaped entries normalize to host`() {
        val connects = listOf("https://api.example.com", "example.org:8080", "cdn.example.net/lib")
        assertTrue(GmConnectPolicy.hostAllowed(connects, null, "api.example.com"))
        assertTrue(GmConnectPolicy.hostAllowed(connects, null, "example.org"))
        assertTrue(GmConnectPolicy.hostAllowed(connects, null, "cdn.example.net"))
    }

    @Test
    fun `matching is case insensitive and blank targets fail`() {
        val connects = listOf("EXAMPLE.COM")
        assertTrue(GmConnectPolicy.hostAllowed(connects, null, "example.com"))
        assertFalse(GmConnectPolicy.hostAllowed(connects, "a.com", null))
        assertFalse(GmConnectPolicy.hostAllowed(connects, "a.com", ""))
    }

    @Test
    fun `hostOf extracts host only from absolute urls`() {
        assertEquals("example.com", GmConnectPolicy.hostOf("https://EXAMPLE.com/x"))
        assertNull(GmConnectPolicy.hostOf("/relative/path"))
        assertNull(GmConnectPolicy.hostOf("not a url"))
        assertNull(GmConnectPolicy.hostOf(null))
    }

    @Test
    fun `runsOn mirrors bootstrap include exclude semantics`() {
        // Default include set: match everything.
        assertTrue(GmConnectPolicy.runsOn(emptyList(), emptyList(), "https://a.com/x"))
        // Stored patterns are anchored regexes — same strings the JS
        // runtime wraps in ^(?:…)$.
        assertTrue(GmConnectPolicy.runsOn(
            listOf("^\\w+://example.com/*"), emptyList(), "https://example.com"))
        assertFalse(GmConnectPolicy.runsOn(
            listOf("^\\w+://example.com/*"), emptyList(), "https://other.com"))
        // Excludes veto even a matching include.
        assertFalse(GmConnectPolicy.runsOn(
            listOf(".*"), listOf("^\\w+://evil\\.com.*"), "https://evil.com/x"))
        // Malformed pattern counts as no match.
        assertFalse(GmConnectPolicy.runsOn(listOf("(["), emptyList(), "https://a.com"))
    }
}
