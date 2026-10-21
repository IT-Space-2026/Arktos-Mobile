// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FIXB09 — per-site JS/cookie quick-toggles (upstream #305). Overrides
 * are keyed on the page host, flip the effective value on each toggle,
 * and fall back to inherited state when the flipped value matches the
 * global default.
 */
class SiteOverridesTest {

    @Test
    fun `siteHost extracts the lowercased host`() {
        assertEquals("example.com", SiteOverrides.siteHost("https://example.com/path?q=1"))
        assertEquals("sub.example.com", SiteOverrides.siteHost("http://Sub.Example.COM:8080/x"))
        assertEquals("example.com", SiteOverrides.siteHost("example.com/path"))
        assertEquals("10.0.0.1", SiteOverrides.siteHost("http://10.0.0.1:8080/"))
    }

    @Test
    fun `siteHost is null for urls without a host`() {
        assertNull(SiteOverrides.siteHost("about:blank"))
        assertNull(SiteOverrides.siteHost("data:text/html;base64,aGk="))
        assertNull(SiteOverrides.siteHost("file:///android_asset/homepage.html"))
        assertNull(SiteOverrides.siteHost(""))
        assertNull(SiteOverrides.siteHost(null))
    }

    @Test
    fun `toggle flips the effective state away from the default`() {
        val overrides = SiteOverrides()
        assertFalse(overrides.toggleJavaScript("https://example.com/", default = true))
        assertEquals(false, overrides.javaScriptOverride("https://example.com/other"))
        assertTrue(overrides.toggleJavaScript("https://example.com/", default = false))
        assertEquals(true, overrides.javaScriptOverride("https://example.com/"))
    }

    @Test
    fun `toggling back to the default drops the override`() {
        val overrides = SiteOverrides()
        overrides.toggleJavaScript("https://example.com/", default = true)
        assertTrue(overrides.toggleJavaScript("https://example.com/", default = true))
        assertNull(overrides.javaScriptOverride("https://example.com/"))
        assertEquals("", overrides.serializedJavaScript())
    }

    @Test
    fun `overrides are isolated per host`() {
        val overrides = SiteOverrides()
        overrides.toggleJavaScript("https://a.example.com/", default = true)
        assertNull(overrides.javaScriptOverride("https://b.example.com/"))
        assertNull(overrides.javaScriptOverride("https://example.com/"))
    }

    @Test
    fun `javascript and cookie overrides are independent`() {
        val overrides = SiteOverrides()
        overrides.toggleJavaScript("https://example.com/", default = true)
        assertNull(overrides.cookieOverride("https://example.com/"))
        assertEquals("", overrides.serializedCookies())
    }

    @Test
    fun `override-less urls keep the passed default`() {
        val overrides = SiteOverrides()
        assertTrue(overrides.toggleJavaScript("about:blank", default = true))
        assertNull(overrides.javaScriptOverride("about:blank"))
        assertEquals("", overrides.serializedJavaScript())
    }

    @Test
    fun `serialization round-trips the override sets`() {
        val overrides = SiteOverrides()
        overrides.toggleJavaScript("https://blocked.example/", default = true)
        overrides.toggleCookies("https://allowed.example/", default = false)

        assertEquals("blocked.example=0", overrides.serializedJavaScript())
        assertEquals("allowed.example=1", overrides.serializedCookies())

        val restored = SiteOverrides(
            javaScriptSerialized = overrides.serializedJavaScript(),
            cookiesSerialized = overrides.serializedCookies()
        )
        assertEquals(false, restored.javaScriptOverride("https://blocked.example/"))
        assertEquals(true, restored.cookieOverride("https://allowed.example/"))
        assertNull(restored.javaScriptOverride("https://other.example/"))
    }

    @Test
    fun `decode tolerates malformed entries`() {
        val overrides = SiteOverrides(javaScriptSerialized = "good.example=1,,noequals,=0,bad=")
        assertEquals(true, overrides.javaScriptOverride("https://good.example/"))
        assertNull(overrides.javaScriptOverride("https://noequals/"))
        assertNull(overrides.javaScriptOverride("https://bad/"))
    }
}
