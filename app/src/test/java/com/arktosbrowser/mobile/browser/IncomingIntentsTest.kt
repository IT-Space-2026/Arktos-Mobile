// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.browser

import android.app.SearchManager
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/**
 * SEC09 — incoming-intent validation on the exported MainActivity:
 * every external URL candidate passes one extraction + allowlist
 * point regardless of whether it arrives on the cold-start or the
 * onNewIntent path.
 */
class IncomingIntentsTest {

    private val searchTemplate = "https://search.example/?q=%s"

    private fun intentOf(
        action: String? = null,
        data: String? = null,
        query: String? = null,
        processText: String? = null
    ): Intent = mock {
        on { this.action } doReturn action
        on { this.dataString } doReturn data
        on { getStringExtra(SearchManager.QUERY) } doReturn query
        on { getStringExtra(Intent.EXTRA_PROCESS_TEXT) } doReturn processText
    }

    @Test
    fun `null intent and intent without url yield null`() {
        assertNull(IncomingIntents.extractUrl(null, searchTemplate))
        assertNull(IncomingIntents.extractUrl(intentOf(Intent.ACTION_VIEW), searchTemplate))
        assertNull(IncomingIntents.extractUrl(intentOf(Intent.ACTION_MAIN), searchTemplate))
    }

    @Test
    fun `view intent data url passes through`() {
        assertEquals(
            "https://example.com/page",
            IncomingIntents.extractUrl(
                intentOf(Intent.ACTION_VIEW, data = "https://example.com/page"),
                searchTemplate
            )
        )
    }

    @Test
    fun `view intent rejects dangerous schemes`() {
        val rejected = mutableListOf<String>()
        val onRejected: (String) -> Unit = { rejected += it }
        for (url in listOf(
            "javascript:alert(document.cookie)",
            "data:text/html,<script>alert(1)</script>",
            "intent://evil.example#Intent;end",
            "tel:+123456789"
        )) {
            assertNull(
                "should reject $url",
                IncomingIntents.extractUrl(intentOf(Intent.ACTION_VIEW, data = url), searchTemplate, onRejected)
            )
        }
        assertEquals(listOf("javascript", "data", "intent", "tel"), rejected)
    }

    @Test
    fun `web search query becomes a search url`() {
        assertEquals(
            "https://search.example/?q=hello+world",
            IncomingIntents.extractUrl(
                intentOf(Intent.ACTION_WEB_SEARCH, query = "hello world"),
                searchTemplate
            )
        )
    }

    @Test
    fun `web search query that is a url stays a url`() {
        assertEquals(
            "https://example.com/",
            IncomingIntents.extractUrl(
                intentOf(Intent.ACTION_WEB_SEARCH, query = "https://example.com/"),
                searchTemplate
            )
        )
    }

    @Test
    fun `blank web search query yields null`() {
        assertNull(
            IncomingIntents.extractUrl(
                intentOf(Intent.ACTION_WEB_SEARCH, query = "   "),
                searchTemplate
            )
        )
    }

    @Test
    fun `process text selection becomes a search url`() {
        // ACTION_PROCESS_TEXT is declared in the manifest but was
        // previously dropped on the floor; the selected text now gets
        // the same treatment as a web-search query.
        assertEquals(
            "https://search.example/?q=some+selected+text",
            IncomingIntents.extractUrl(
                intentOf(Intent.ACTION_PROCESS_TEXT, processText = "some selected text"),
                searchTemplate
            )
        )
    }

    @Test
    fun `process text carrying a url opens the url`() {
        assertEquals(
            "https://example.com/a",
            IncomingIntents.extractUrl(
                intentOf(Intent.ACTION_PROCESS_TEXT, processText = "https://example.com/a"),
                searchTemplate
            )
        )
    }

    @Test
    fun `query extras can not smuggle a schemed url through`() {
        // A hostile app could stuff a javascript: URL into the query
        // extra; smartUrlFilter treats it as a search phrase, and the
        // composed result is allowlisted too — either way the raw
        // javascript: payload can never come out.
        val rejected = mutableListOf<String>()
        val result = IncomingIntents.extractUrl(
            intentOf(Intent.ACTION_WEB_SEARCH, query = "javascript:alert(1)"),
            searchTemplate
        ) { rejected += it }
        assertTrue(result == null || result.startsWith("https://search.example/"))
    }
}
