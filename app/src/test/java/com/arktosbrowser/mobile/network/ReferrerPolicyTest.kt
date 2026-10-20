package com.arktosbrowser.mobile.network

import com.arktosbrowser.mobile.network.ReferrerPolicy.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SEC13 — referrer control on top-level navigations: the WebView
 * attaches `Referer` itself to navigations initiated from page
 * content, so the policy works by vetoing the navigation and
 * re-issuing it through `loadUrl` with an explicit header map. A null
 * result from [ReferrerPolicy.headersFor] means "let it through
 * untouched".
 */
class ReferrerPolicyTest {

    private val source = "https://news.example/article/1"
    private val sameOrigin = "https://news.example/article/2"
    private val crossOrigin = "https://shop.example/cart"
    private val noHeaders = emptyMap<String, String>()

    @Test
    fun `default mode never takes over`() {
        assertNull(ReferrerPolicy.headersFor(crossOrigin, source, Mode.DEFAULT, noHeaders))
        assertNull(ReferrerPolicy.headersFor(sameOrigin, source, Mode.DEFAULT, noHeaders))
    }

    @Test
    fun `strip mode drops referer on every navigation`() {
        val cross = ReferrerPolicy.headersFor(crossOrigin, source, Mode.STRIP, noHeaders)
        val same = ReferrerPolicy.headersFor(sameOrigin, source, Mode.STRIP, noHeaders)
        assertEquals(noHeaders, cross)
        assertEquals(noHeaders, same)
    }

    @Test
    fun `same-origin mode keeps referer only within the origin`() {
        val same = ReferrerPolicy.headersFor(sameOrigin, source, Mode.SAME_ORIGIN, noHeaders)
        assertEquals(source, same?.get(ReferrerPolicy.HEADER))

        val cross = ReferrerPolicy.headersFor(crossOrigin, source, Mode.SAME_ORIGIN, noHeaders)
        assertEquals(noHeaders, cross)
    }

    @Test
    fun `subdomain port and scheme changes are cross-origin`() {
        for (target in listOf(
            "https://www.news.example/article/2",      // subdomain
            "https://news.example:8443/article/2",     // different port
            "http://news.example/article/2"            // downgrade
        )) {
            assertEquals(
                target,
                noHeaders,
                ReferrerPolicy.headersFor(target, source, Mode.SAME_ORIGIN, noHeaders)
            )
        }
    }

    @Test
    fun `default ports are normalized for same-origin`() {
        assertTrue(ReferrerPolicy.sameOrigin("https://a.example/x", "https://a.example:443/y"))
        assertTrue(ReferrerPolicy.sameOrigin("http://a.example/x", "http://a.example:80/y"))
        assertFalse(ReferrerPolicy.sameOrigin("https://a.example/x", "http://a.example/x"))
        // userinfo does not affect the origin
        assertTrue(ReferrerPolicy.sameOrigin("https://u:p@a.example/x", "https://a.example/y"))
    }

    @Test
    fun `same-document fragment navigation is left alone`() {
        for (mode in listOf(Mode.SAME_ORIGIN, Mode.STRIP)) {
            assertNull(
                mode.name,
                ReferrerPolicy.headersFor("$source#section", source, mode, noHeaders)
            )
            assertNull(
                mode.name,
                ReferrerPolicy.headersFor(source, "$source#section", mode, noHeaders)
            )
        }
    }

    @Test
    fun `exact same-url click is a real navigation and gets trimmed`() {
        assertFalse(ReferrerPolicy.isSameDocumentNavigation(source, source))
        val headers = ReferrerPolicy.headersFor(source, source, Mode.STRIP, noHeaders)
        assertEquals(noHeaders, headers)
    }

    @Test
    fun `non-web targets are never taken over`() {
        for (target in listOf(
            "mailto:a@b.example",
            "tel:+123",
            "intent://x/#Intent;end",
            "javascript:void(0)",
            "data:text/html,x",
            "about:blank",
            "arktos://changelog",
            "file:///sdcard/x.html"
        )) {
            assertNull(
                target,
                ReferrerPolicy.headersFor(target, source, Mode.STRIP, noHeaders)
            )
        }
    }

    @Test
    fun `non-web source never counts as same-origin`() {
        val headers = ReferrerPolicy.headersFor(
            sameOrigin, "data:text/html,x", Mode.SAME_ORIGIN, noHeaders
        )
        assertEquals(noHeaders, headers)
        assertNull(
            ReferrerPolicy.headersFor(sameOrigin, null, Mode.SAME_ORIGIN, noHeaders)
                ?.get(ReferrerPolicy.HEADER)
        )
    }

    @Test
    fun `base headers are preserved and any stale referer is replaced`() {
        val base = mapOf("DNT" to "1", "Referer" to "https://tracker.example/")
        val same = ReferrerPolicy.headersFor(sameOrigin, source, Mode.SAME_ORIGIN, base)
        assertEquals(mapOf("DNT" to "1", "Referer" to source), same)

        val stripped = ReferrerPolicy.headersFor(crossOrigin, source, Mode.STRIP, base)
        assertEquals(mapOf("DNT" to "1"), stripped)
    }
}
