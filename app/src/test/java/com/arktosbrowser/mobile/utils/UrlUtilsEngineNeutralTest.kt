// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ENG02 — engine-neutral replacements for the platform `URLUtil` /
 * `MimeTypeMap` helpers so non-adapter code never needs the WebView
 * package for URL classification.
 */
class UrlUtilsEngineNeutralTest {

    @Test
    fun `file urls detected case-insensitively`() {
        assertTrue("file:///sdcard/a.png".isFileUrl())
        assertTrue("FILE:///sdcard/a.png".isFileUrl())
        assertFalse("https://example.com/file".isFileUrl())
        assertFalse(null.isFileUrl())
        assertFalse("".isFileUrl())
    }

    @Test
    fun `about urls detected case-insensitively`() {
        assertTrue("about:blank".isAboutUrl())
        assertTrue("ABOUT:blank".isAboutUrl())
        assertFalse("https://example.com/about".isAboutUrl())
        assertFalse(null.isAboutUrl())
    }

    @Test
    fun `valid url accepts scheme and bare dotted hosts`() {
        assertTrue(isValidUrl("https://example.com/a?b=c"))
        assertTrue(isValidUrl("http://localhost:8080/x"))
        assertTrue(isValidUrl("example.com"))
        assertTrue(isValidUrl("data:text/html,hi"))
        assertTrue(isValidUrl("file:///sdcard/a"))
        assertTrue(isValidUrl("about:blank"))
        assertTrue(isValidUrl("javascript:alert(1)"))
    }

    @Test
    fun `valid url rejects junk and spaced javascript`() {
        assertFalse(isValidUrl(null))
        assertFalse(isValidUrl(""))
        assertFalse(isValidUrl("not a url"))
        assertFalse(isValidUrl("nodotshost"))
        assertFalse(isValidUrl("javascript:alert (1)"))
        assertFalse(isValidUrl("://missing-scheme"))
    }

    @Test
    fun `file extension comes from last path segment without query or fragment`() {
        assertEquals("png", fileExtensionFromUrl("https://e.com/a/b.PNG"))
        assertEquals("jpg", fileExtensionFromUrl("https://e.com/a.jpg?x=1#frag"))
        assertEquals("bin", fileExtensionFromUrl("https://e.com/x.bin?a=b.png"))
        assertEquals("", fileExtensionFromUrl("https://e.com/noext"))
        assertEquals("", fileExtensionFromUrl("https://e.com/dir/"))
        assertEquals("", fileExtensionFromUrl("https://e.com/.hidden"))
        assertEquals("", fileExtensionFromUrl("https://e.com/trailing."))
    }
}
