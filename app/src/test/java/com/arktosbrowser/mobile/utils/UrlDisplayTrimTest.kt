// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers upstream issue #278: an option trims the protocol and a leading
 * `www.` from the URL bar. The transform must be display-only — paths,
 * queries and non-`www` host prefixes survive untouched.
 */
class UrlDisplayTrimTest {

    @Test
    fun `https scheme and www prefix are removed`() {
        assertEquals(
            "example.com/path?q=1",
            "https://www.example.com/path?q=1".trimmedForDisplay()
        )
    }

    @Test
    fun `scheme alone is removed`() {
        assertEquals("example.com", "http://example.com".trimmedForDisplay())
        assertEquals("example.com", "https://example.com".trimmedForDisplay())
    }

    @Test
    fun `www prefix is removed without a scheme`() {
        assertEquals("example.com", "www.example.com".trimmedForDisplay())
    }

    @Test
    fun `scheme and www match is case insensitive`() {
        assertEquals("Example.COM", "HTTPS://WWW.Example.COM".trimmedForDisplay())
    }

    @Test
    fun `www inside path or authority is preserved`() {
        assertEquals(
            "example.com/www.page",
            "https://example.com/www.page".trimmedForDisplay()
        )
        assertEquals(
            "user@www.example.com",
            "https://user@www.example.com".trimmedForDisplay()
        )
        assertEquals(
            "www2.example.com",
            "https://www2.example.com".trimmedForDisplay()
        )
    }

    @Test
    fun `urls without an authority separator are unchanged`() {
        assertEquals("about:blank", "about:blank".trimmedForDisplay())
        assertEquals(
            "data:text/html;base64,aGVsbG8=",
            "data:text/html;base64,aGVsbG8=".trimmedForDisplay()
        )
    }
}
