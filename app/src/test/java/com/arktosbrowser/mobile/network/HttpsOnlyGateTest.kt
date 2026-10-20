package com.arktosbrowser.mobile.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SEC04 — strict HTTPS-only mode: cleartext `http://` navigations get
 * a warning interstitial instead of a silent upgrade; cleartext
 * consent is host-scoped and only ever granted through the
 * interstitial's proceed marker.
 */
class HttpsOnlyGateTest {

    private val allowed = setOf("allowed.example")

    @Test
    fun `cleartext navigation warns only in strict mode`() {
        assertTrue(
            HttpsOnlyGate.isCleartextNavigation("http://example.com/", strict = true, allowed)
        )
        assertFalse(
            HttpsOnlyGate.isCleartextNavigation("http://example.com/", strict = false, allowed)
        )
    }

    @Test
    fun `https and non-network urls never warn`() {
        for (url in listOf(
            "https://example.com/",
            "data:text/html,x",
            "file:///android_asset/x.html",
            "about:blank",
            "arktos://changelog",
            "javascript:void(0)"
        )) {
            assertFalse(url, HttpsOnlyGate.isCleartextNavigation(url, strict = true, allowed))
        }
    }

    @Test
    fun `consented host stops warning, other hosts still warn`() {
        assertFalse(
            HttpsOnlyGate.isCleartextNavigation("http://allowed.example/page", strict = true, allowed)
        )
        assertTrue(
            HttpsOnlyGate.isCleartextNavigation("http://other.example/page", strict = true, allowed)
        )
        // Consent is host-scoped: a different port on the same host is
        // still covered.
        assertFalse(
            HttpsOnlyGate.isCleartextNavigation("http://allowed.example:8080/", strict = true, allowed)
        )
    }

    @Test
    fun `upgrade preserves port path query and fragment`() {
        assertEquals(
            "https://example.com:8080/a/b?c=1&d=2#frag",
            HttpsOnlyGate.upgradedUrl("http://example.com:8080/a/b?c=1&d=2#frag")
        )
        assertEquals("https://example.com/", HttpsOnlyGate.upgradedUrl("http://example.com/"))
    }

    @Test
    fun `proceed marker round-trips the cleartext url`() {
        val url = "http://example.com/a?b=1&c=2"
        assertEquals(url, HttpsOnlyGate.parseProceedUrl(HttpsOnlyGate.proceedUrl(url)))
    }

    @Test
    fun `proceed marker rejects anything but cleartext targets`() {
        // Not a marker at all.
        assertNull(HttpsOnlyGate.parseProceedUrl("http://example.com/"))
        assertNull(HttpsOnlyGate.parseProceedUrl("arktos://changelog"))
        // A marker must never become a redirector to another scheme —
        // https or javascript targets decode but are refused.
        assertNull(
            HttpsOnlyGate.parseProceedUrl(
                HttpsOnlyGate.PROCEED_URL_PREFIX + "https%3A%2F%2Fevil.example%2F"
            )
        )
        assertNull(
            HttpsOnlyGate.parseProceedUrl(
                HttpsOnlyGate.PROCEED_URL_PREFIX + "javascript%3Aalert(1)"
            )
        )
    }

    @Test
    fun `hostOf strips userinfo port and case`() {
        assertEquals("example.com", HttpsOnlyGate.hostOf("http://User@Example.COM:8080/a"))
        assertEquals("example.com", HttpsOnlyGate.hostOf("https://example.com"))
        assertEquals("", HttpsOnlyGate.hostOf("about:blank"))
    }
}
