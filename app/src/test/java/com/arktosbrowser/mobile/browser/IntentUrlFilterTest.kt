package com.arktosbrowser.mobile.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentUrlFilterTest {

    @Test
    fun `web schemes are allowed`() {
        assertTrue(BrowserPresenter.isIntentUrlAllowed("https://example.com"))
        assertTrue(BrowserPresenter.isIntentUrlAllowed("http://example.com"))
        assertTrue(BrowserPresenter.isIntentUrlAllowed("HTTPS://example.com"))
    }

    @Test
    fun `local and about schemes are allowed`() {
        assertTrue(BrowserPresenter.isIntentUrlAllowed("file:///sdcard/page.html"))
        assertTrue(BrowserPresenter.isIntentUrlAllowed("about:blank"))
        assertTrue(BrowserPresenter.isIntentUrlAllowed("inline:app/homepage"))
        assertTrue(BrowserPresenter.isIntentUrlAllowed("content://media/external/file/1"))
    }

    @Test
    fun `javascript scheme is rejected`() {
        assertFalse(BrowserPresenter.isIntentUrlAllowed("javascript:alert(document.cookie)"))
        assertFalse(BrowserPresenter.isIntentUrlAllowed("JavaScript:alert(1)"))
    }

    @Test
    fun `data and other exotic schemes are rejected`() {
        assertFalse(BrowserPresenter.isIntentUrlAllowed("data:text/html,<script>alert(1)</script>"))
        assertFalse(BrowserPresenter.isIntentUrlAllowed("vbscript:msgbox(1)"))
        assertFalse(BrowserPresenter.isIntentUrlAllowed("intent://malicious#Intent;end"))
        assertFalse(BrowserPresenter.isIntentUrlAllowed("tel:+1234"))
    }

    @Test
    fun `url without scheme is rejected`() {
        assertFalse(BrowserPresenter.isIntentUrlAllowed("example.com/path"))
        assertFalse(BrowserPresenter.isIntentUrlAllowed("just-text"))
    }
}
