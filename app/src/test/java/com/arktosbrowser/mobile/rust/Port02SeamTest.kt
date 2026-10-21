// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.rust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PORT02 — seam tests for the second-sweep ports: smartUrlFilter /
 * special-page checks, the error/block page builders, the color math,
 * and the argon2id password migration. Runs against the real
 * host-built libarktos.so via `jna.library.path`.
 */
class Port02SeamTest {

    @Test
    fun `smartUrlFilter resolves urls and searches`() {
        assertEquals(
            "http://example.com/x",
            ArktosCore.smartUrlFilter("  example.com/x  ", true, "https://s/?q=%s")
        )
        assertEquals(
            "http://A.com/",
            ArktosCore.smartUrlFilter("HTTP://A.com/", true, "https://s/?q=%s")
        )
        assertEquals(
            "https://s/?q=two+words",
            ArktosCore.smartUrlFilter("two words", true, "https://s/?q=%s")
        )
        assertEquals("", ArktosCore.smartUrlFilter("two words", false, "https://s/?q=%s"))
    }

    @Test
    fun `special page checks`() {
        assertTrue(ArktosCore.isSpecialUrl("file:///data/bookmark.html"))
        assertTrue(ArktosCore.isBookmarkUrl("file:///data/bookmark.html"))
        assertFalse(ArktosCore.isBookmarkUrl("file:///data/history.html"))
        assertTrue(ArktosCore.isHistoryUrl("file:///x/history.html"))
        assertTrue(ArktosCore.isDownloadsUrl("file:///x/downloads.html"))
        assertTrue(ArktosCore.isStartPageUrl("file:///x/homepage.html"))
        assertFalse(ArktosCore.isSpecialUrl("https://a.com/bookmark.html"))
    }

    @Test
    fun `domainName parity`() {
        assertEquals("example.com", ArktosCore.domainName("http://www.example.com/p"))
        assertEquals("https://www.example.com", ArktosCore.domainName("https://www.example.com/p"))
        assertEquals("notaurl", ArktosCore.domainName("notaurl"))
        assertEquals("", ArktosCore.domainName(""))
    }

    @Test
    fun `error page builds html`() {
        val html = ArktosCore.buildErrorPage(
            "<style>x</style>", "Title", "NET::ERR_X", "hint", "Reload", true
        )
        assertTrue(html.contains("<h1>Title</h1>"))
        assertTrue(html.contains("NET::ERR_X"))
        assertTrue(html.contains(">Reload</button>"))
        assertTrue(html.endsWith("<style>x</style>"))
    }

    @Test
    fun `error page has desktop card structure and dark variant`() {
        // UI01 — the Arktos desktop interstitial look: rounded card,
        // gradient header band with the logo floated right, error
        // under a thin divider and a small suggestion line.
        val light = ArktosCore.buildErrorPage(
            "", "Title", "NET::ERR_X", "hint", "Reload", true
        )
        for (marker in listOf(
            "<div id=\"box\">",
            "<div id=\"header\">",
            "<div id=\"content\">",
            "<div id=\"buttons\">",
            "data:image/webp;base64,",
            "<li>hint</li>",
            "border-radius: 0.8em",
            "#228 0%, #66a 90%"
        )) {
            assertTrue("missing $marker", light.contains(marker))
        }
        assertTrue(light.startsWith("<html>"))

        // A non-empty color fragment is the callers' dark-theme
        // signal — it must tag <html> like the desktop's
        // decorateInternalPage.
        val dark = ArktosCore.buildErrorPage(
            "<style>x</style>", "Title", "NET::ERR_X", "hint", "Reload", true
        )
        assertTrue(dark.startsWith("<html class=\"arktos-dark\">"))
        assertTrue(dark.contains("html.arktos-dark body"))
    }

    @Test
    fun `block page builds buttons`() {
        val html = ArktosCore.buildBlockPage(
            "", "Blocked", "reason", "https://ads.example/", "hint", "Proceed", "Back", true
        )
        assertTrue(html.contains("location.href = 'https://ads.example/';"))
        assertTrue(html.contains(">Proceed</button>"))
        assertTrue(html.contains(">Back</button>"))
        assertTrue(html.contains("<li>https://ads.example/</li>"))
    }

    @Test
    fun `color math parity`() {
        assertTrue(ArktosCore.isColorTooDark(0xff101010.toInt()))
        assertFalse(ArktosCore.isColorTooDark(0xffffffff.toInt()))
        assertEquals(0xff7f7f7f.toInt(), ArktosCore.mixTwoColors(-1, 0xff000000.toInt(), 0.5f))
        assertEquals(0x33445566, ArktosCore.mixColorFraction(0.5f, 0x11223344, 0x55667788))
    }

    @Test
    fun `fileExtension parity`() {
        assertEquals("zip", ArktosCore.fileExtension("a/b.zip"))
        assertNull(ArktosCore.fileExtension("noext"))
        assertNull(ArktosCore.fileExtension("trailing."))
    }

    @Test
    fun `password hashing roundtrip and migration`() {
        val stored = ArktosCore.hashPassword("s3cret")
        assertTrue(stored.startsWith("arktos:v1:"))
        assertTrue(ArktosCore.verifyPassword(stored, "s3cret").matched)
        assertFalse(ArktosCore.verifyPassword(stored, "wrong").matched)

        // Legacy plaintext pref: match upgrades to an argon2id hash.
        val legacy = ArktosCore.verifyPassword("hunter2", "hunter2")
        assertTrue(legacy.matched)
        val upgraded = legacy.upgradedHash
        assertNotNull(upgraded)
        assertTrue(upgraded!!.startsWith("arktos:v1:"))
        assertTrue(ArktosCore.verifyPassword(upgraded, "hunter2").matched)
        assertFalse(ArktosCore.verifyPassword("hunter2", "nope").matched)
    }

    @Test
    fun `legacy bookmark export writes json lines`() {
        val rows = listOf(
            com.arktosbrowser.mobile.uniffi.StoreBookmark(
                url = "https://a.com/",
                title = "A",
                folder = "Docs",
                position = 0u,
                createdAt = 0uL
            )
        )
        assertEquals(
            "{\"title\":\"A\",\"url\":\"https://a.com/\",\"folder\":\"Docs\",\"order\":0}\n",
            ArktosCore.exportLegacyBookmarks(rows)
        )
    }

    @Test
    fun `intent scheme allowlist`() {
        assertTrue(ArktosCore.isIntentUrlAllowed("https://a.com"))
        assertTrue(ArktosCore.isIntentUrlAllowed("content://x"))
        assertFalse(ArktosCore.isIntentUrlAllowed("javascript:alert(1)"))
        assertFalse(ArktosCore.isIntentUrlAllowed("example.com"))
    }
}
