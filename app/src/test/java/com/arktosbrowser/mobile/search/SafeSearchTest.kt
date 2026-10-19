package com.arktosbrowser.mobile.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards upstream issue #327: when the parental-controls SafeSearch
 * switch is on, navigations to a known engine's results endpoint must
 * carry its strict-filtering parameter — including URLs the user edited
 * or opened from a bookmark/link, which is why enforcement lives at the
 * load layer rather than in the composed search template.
 */
class SafeSearchTest {

    @Test
    fun `enforcement off leaves every url untouched`() {
        listOf(
            "https://www.google.com/search?q=cats",
            "https://www.bing.com/search?q=cats",
            "https://duckduckgo.com/?q=cats",
            "https://yandex.ru/yandsearch?text=cats",
            "https://example.com/"
        ).forEach { assertEquals(it, SafeSearch.enforcedUrl(it, false)) }
    }

    @Test
    fun `google search urls get safe=active`() {
        assertEquals(
            "https://www.google.com/search?q=cats&safe=active",
            SafeSearch.enforcedUrl("https://www.google.com/search?q=cats", true)
        )
        // Country domains and http are covered too.
        assertEquals(
            "https://google.co.uk/search?client=x&q=cats&safe=active",
            SafeSearch.enforcedUrl("https://google.co.uk/search?client=x&q=cats", true)
        )
        assertEquals(
            "http://www.google.de/search?q=cats&safe=active",
            SafeSearch.enforcedUrl("http://www.google.de/search?q=cats", true)
        )
    }

    @Test
    fun `bing duckduckgo and yandex get their strict params`() {
        assertEquals(
            "https://www.bing.com/search?q=cats&adlt=strict",
            SafeSearch.enforcedUrl("https://www.bing.com/search?q=cats", true)
        )
        assertEquals(
            "https://duckduckgo.com/?q=cats&kp=1",
            SafeSearch.enforcedUrl("https://duckduckgo.com/?q=cats", true)
        )
        assertEquals(
            "https://lite.duckduckgo.com/lite/?q=cats&kp=1",
            SafeSearch.enforcedUrl("https://lite.duckduckgo.com/lite/?q=cats", true)
        )
        assertEquals(
            "https://yandex.ru/yandsearch?lr=21411&text=cats&family=1",
            SafeSearch.enforcedUrl("https://yandex.ru/yandsearch?lr=21411&text=cats", true)
        )
        assertEquals(
            "https://yandex.com/search/?text=cats&family=1",
            SafeSearch.enforcedUrl("https://yandex.com/search/?text=cats", true)
        )
    }

    @Test
    fun `suffix survives url edits and non-strict values are replaced`() {
        // The user strips the parameter from the URL bar — it comes back.
        assertEquals(
            "https://www.google.com/search?q=cats&safe=active",
            SafeSearch.enforcedUrl("https://www.google.com/search?q=cats", true)
        )
        // The user relaxes the parameter — it is forced back to strict.
        assertEquals(
            "https://www.google.com/search?q=cats&safe=active",
            SafeSearch.enforcedUrl("https://www.google.com/search?q=cats&safe=off", true)
        )
        assertEquals(
            "https://www.bing.com/search?q=cats&adlt=strict",
            SafeSearch.enforcedUrl("https://www.bing.com/search?adlt=off&q=cats", true)
        )
        assertEquals(
            "https://duckduckgo.com/?q=cats&kp=1",
            SafeSearch.enforcedUrl("https://duckduckgo.com/?kp=-2&q=cats", true)
        )
    }

    @Test
    fun `already-strict urls pass through unchanged`() {
        // Idempotent — otherwise the onPageStarted backstop would loop.
        listOf(
            "https://www.google.com/search?q=cats&safe=active",
            "https://www.bing.com/search?q=cats&adlt=strict",
            "https://duckduckgo.com/?q=cats&kp=1",
            "https://yandex.ru/yandsearch?text=cats&family=1"
        ).forEach { assertEquals(it, SafeSearch.enforcedUrl(it, true)) }
    }

    @Test
    fun `unrelated urls and non-search pages are not rewritten`() {
        listOf(
            "https://example.com/?q=cats",
            "https://www.google.com/",
            "https://accounts.google.com/signin",
            "https://www.bing.com/images?q=cats",   // image search has no adlt contract here
            "https://duckduckgo.com/settings",
            "https://duckduckgo.com/",              // no query yet — nothing to filter
            "https://notbing.com/search?q=cats",
            "https://yandex.ru/",
            "https://startpage.com/sp/search?query=cats"
        ).forEach { assertEquals(it, SafeSearch.enforcedUrl(it, true)) }
    }

    @Test
    fun `non-network urls are never touched`() {
        listOf(
            "data:text/html;base64,AAAA",
            "file:///android_asset/homepage.html",
            "about:blank",
            "javascript:alert(1)",
            "content://x/y?q=z"
        ).forEach { assertEquals(it, SafeSearch.enforcedUrl(it, true)) }
    }

    @Test
    fun `fragments and empty queries survive the rewrite`() {
        assertEquals(
            "https://www.google.com/search?q=cats&safe=active#result",
            SafeSearch.enforcedUrl("https://www.google.com/search?q=cats#result", true)
        )
        assertEquals(
            "https://www.bing.com/search?adlt=strict",
            SafeSearch.enforcedUrl("https://www.bing.com/search?", true)
        )
    }
}
