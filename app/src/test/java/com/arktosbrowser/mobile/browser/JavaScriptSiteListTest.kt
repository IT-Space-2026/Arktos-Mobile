package com.arktosbrowser.mobile.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FIXB09 — parity tests for [JavaScriptSiteList], the folded form of
 * the inline BLACKLIST/WHITELIST logic that lived in
 * `ArktosCookieWebClient.onPageStarted`. The enum names are inverted
 * upstream: BLACKLIST is "only allow listed", WHITELIST is "block
 * listed" (matching the `listed_javascript`/`unlisted_javascript`
 * settings strings).
 */
class JavaScriptSiteListTest {

    @Test
    fun `NONE allows javascript everywhere`() {
        assertTrue(JavaScriptSiteList.allowsJavaScript(JavaScriptChoice.NONE, "a.com", "https://b.com/"))
    }

    @Test
    fun `empty list allows javascript under either mode`() {
        assertTrue(JavaScriptSiteList.allowsJavaScript(JavaScriptChoice.BLACKLIST, "", "https://a.com/"))
        assertTrue(JavaScriptSiteList.allowsJavaScript(JavaScriptChoice.BLACKLIST, " ", "https://a.com/"))
        assertTrue(JavaScriptSiteList.allowsJavaScript(JavaScriptChoice.WHITELIST, "", "https://a.com/"))
    }

    @Test
    fun `BLACKLIST only allows listed hosts`() {
        val list = "allowed.example, also-allowed.example"
        assertTrue(JavaScriptSiteList.allowsJavaScript(JavaScriptChoice.BLACKLIST, list, "https://allowed.example/"))
        assertFalse(JavaScriptSiteList.allowsJavaScript(JavaScriptChoice.BLACKLIST, list, "https://denied.example/"))
    }

    @Test
    fun `WHITELIST blocks listed hosts`() {
        val list = "blocked.example"
        assertFalse(JavaScriptSiteList.allowsJavaScript(JavaScriptChoice.WHITELIST, list, "https://blocked.example/page"))
        assertTrue(JavaScriptSiteList.allowsJavaScript(JavaScriptChoice.WHITELIST, list, "https://fine.example/"))
    }

    @Test
    fun `generated pages always allow javascript`() {
        assertTrue(
            JavaScriptSiteList.allowsJavaScript(
                JavaScriptChoice.BLACKLIST, "a.com", "file:///android_asset/homepage.html"
            )
        )
        assertTrue(
            JavaScriptSiteList.allowsJavaScript(
                JavaScriptChoice.BLACKLIST, "a.com", "about:blank"
            )
        )
    }
}
