package com.arktosbrowser.mobile.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers upstream issue #281: after a connection error, the WebView commits the
 * generated error page as a `data:` URI, which then leaked into the tab's
 * reported/persisted URL and was restored on restart as
 * `data:text/html;charset=utf-8;base64,...`. [resolveTabUrl] must keep
 * reporting the original failed URL whenever a generated page is shown, and
 * [isGeneratedPageUrl] must only flag synthetic pages.
 */
class GeneratedPageUrlTest {

    private val failingUrl = "https://ads.example.com/blocked"
    private val errorPageUri = "data:text/html;charset=utf-8;base64,PGh0bWw+"

    @Test
    fun `data uri error page resolves to the original url`() {
        assertEquals(failingUrl, resolveTabUrl(errorPageUri, failingUrl))
    }

    @Test
    fun `error origin page resolves to the original url`() {
        assertEquals(failingUrl, resolveTabUrl(ERROR_PAGE_ORIGIN, failingUrl))
        assertEquals(failingUrl, resolveTabUrl("$ERROR_PAGE_ORIGIN/", failingUrl))
    }

    @Test
    fun `about blank during error load resolves to the original url`() {
        assertEquals(failingUrl, resolveTabUrl("about:blank", failingUrl))
    }

    @Test
    fun `real navigations are returned unchanged`() {
        assertEquals(
            "https://example.com/page",
            resolveTabUrl("https://example.com/page", failingUrl)
        )
        assertEquals(
            failingUrl,
            resolveTabUrl(failingUrl, failingUrl)
        )
    }

    @Test
    fun `genuine data navigation is untouched when no error page is tracked`() {
        assertEquals(errorPageUri, resolveTabUrl(errorPageUri, null))
    }

    @Test
    fun `missing committed url resolves to empty`() {
        assertEquals("", resolveTabUrl(null, null))
        assertEquals("", resolveTabUrl(null, failingUrl))
    }

    @Test
    fun `generated page detection`() {
        assertTrue(errorPageUri.isGeneratedPageUrl())
        assertTrue("data:text/plain,hi".isGeneratedPageUrl())
        assertTrue("about:blank".isGeneratedPageUrl())
        assertTrue(ERROR_PAGE_ORIGIN.isGeneratedPageUrl())
        assertFalse("https://example.com".isGeneratedPageUrl())
        assertFalse("database".isGeneratedPageUrl())
        assertFalse("about:blank-page".isGeneratedPageUrl())
        val nullUrl: String? = null
        assertFalse(nullUrl.isGeneratedPageUrl())
    }
}
