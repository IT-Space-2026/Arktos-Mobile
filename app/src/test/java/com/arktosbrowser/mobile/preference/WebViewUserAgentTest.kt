// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.preference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Covers upstream issue #309: after backgrounding and returning, the first
 * tapped link or entered URL was ignored and the current page reloaded
 * instead.
 *
 * Root cause: `ArktosCookieWebClient.onPageStarted` rewrote the WebView's
 * `userAgentString` mid-navigation to strip the `; wv` marker, while
 * `initializePreferences` (run on every `onResume` via `resumeAll`) restored
 * the default user agent which contains `; wv`. Changing the user-agent while
 * a page is loading forces WebView to load again (see the
 * `WebSettings.setUserAgentString` documentation, crbug.com/315891), and the
 * reload is issued against the last committed entry — cancelling the pending
 * navigation. Result: exactly one navigation was eaten per resume cycle.
 *
 * The marker is now removed once, up front, in [UserPreferences.userAgent] so
 * the applied value is stable and `setUserAgentString` becomes a no-op.
 */
class WebViewUserAgentTest {

    private val defaultWebViewUa =
        "Mozilla/5.0 (Linux; Android 10; K; wv) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Version/4.0 Chrome/125.0.0.0 Mobile Safari/537.36"

    @Test
    fun `strips the webview marker from the default user agent`() {
        assertFalse(stripWebViewMarker(defaultWebViewUa).contains(WEBVIEW_UA_MARKER))
        assertEquals(
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Version/4.0 Chrome/125.0.0.0 Mobile Safari/537.36",
            stripWebViewMarker(defaultWebViewUa)
        )
    }

    @Test
    fun `leaves non webview user agents untouched`() {
        listOf(
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_14_6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/76.0.3809.132 Safari/537.36",
            "Mozilla/5.0 (Linux; Android 10.0; OnePlus 7 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/79.0.3945.136 Mobile Safari/537.36",
            " "
        ).forEach { assertEquals(it, stripWebViewMarker(it)) }
    }

    @Test
    fun `strips the marker anywhere it appears and is idempotent`() {
        val custom = "Custom; wv Agent/1.0"
        assertEquals("Custom Agent/1.0", stripWebViewMarker(custom))
        assertEquals(
            stripWebViewMarker(defaultWebViewUa),
            stripWebViewMarker(stripWebViewMarker(defaultWebViewUa))
        )
    }

    @Test
    fun `userAgent applies the marker strip for every choice`() {
        val source = loadSource(
            "src/main/java/com/arktosbrowser/mobile/preference/UserPreferencesExtensions.kt",
            "app/src/main/java/com/arktosbrowser/mobile/preference/UserPreferencesExtensions.kt"
        )
        assertTrue(
            "userAgent() must return a marker-free UA so setUserAgentString is a no-op",
            source.contains("stripWebViewMarker(")
        )
    }

    @Test
    fun `onPageStarted never mutates the user agent mid navigation`() {
        val source = loadSource(
            "src/main/java/com/arktosbrowser/mobile/view/ArktosCookieWebClient.kt",
            "app/src/main/java/com/arktosbrowser/mobile/view/ArktosCookieWebClient.kt"
        )
        val onPageStarted = source.substringAfter("override fun onPageStarted")
            .substringBefore("override fun onReceivedHttpAuthRequest")
        assertFalse(
            "Mutating userAgentString during onPageStarted reloads the old page " +
                "and eats the navigation (upstream #309)",
            onPageStarted.contains("userAgentString =")
        )
    }

    private fun loadSource(vararg candidates: String): String =
        candidates.map(::File).first { it.isFile }.readText()
}
