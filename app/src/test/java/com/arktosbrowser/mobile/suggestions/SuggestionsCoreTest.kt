// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.suggestions

import com.arktosbrowser.mobile.rust.ArktosCore
import com.arktosbrowser.mobile.uniffi.SuggestionProvider
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PORT01 — suggestion URL building + response parsing live in the
 * Rust core; these exercise the seam end to end (the host-built
 * libarktos.so is on `jna.library.path` for unit tests).
 */
class SuggestionsCoreTest {

    @Test
    fun `query urls per provider`() {
        assertEquals(
            "https://suggestqueries.google.com/complete/search?output=toolbar&q=abc",
            ArktosCore.suggestionQueryUrl(SuggestionProvider.GOOGLE, "abc", "en")
        )
        assertEquals(
            "https://duckduckgo.com/ac/?q=abc",
            ArktosCore.suggestionQueryUrl(SuggestionProvider.DUCK, "abc", "en")
        )
        assertEquals(
            "https://www.startpage.com/osuggestions?q=abc",
            ArktosCore.suggestionQueryUrl(SuggestionProvider.STARTPAGE, "abc", "en")
        )
        assertEquals(
            "https://api.qwant.com/v3/suggest?q=abc&locale=en_US&version=2",
            ArktosCore.suggestionQueryUrl(SuggestionProvider.QWANT, "abc", "en")
        )
    }

    @Test
    fun `google xml body parses`() {
        val body = """<toplevel><CompleteSuggestion><suggestion data="hello"/></CompleteSuggestion></toplevel>"""
        assertEquals(listOf("hello"), ArktosCore.parseSuggestions(SuggestionProvider.GOOGLE, body.toByteArray()))
    }

    @Test
    fun `duck json body parses`() {
        val body = """[{"phrase":"a"},{"phrase":"b"}]"""
        assertEquals(listOf("a", "b"), ArktosCore.parseSuggestions(SuggestionProvider.DUCK, body.toByteArray()))
    }

    @Test
    fun `startpage opensearch body parses`() {
        val body = """["q",["one","two"]]"""
        assertEquals(listOf("one", "two"), ArktosCore.parseSuggestions(SuggestionProvider.STARTPAGE, body.toByteArray()))
    }

    @Test
    fun `qwant json body parses`() {
        val body = """{"status":"success","data":{"items":[{"value":"a"},{"value":"b"}]}}"""
        assertEquals(listOf("a", "b"), ArktosCore.parseSuggestions(SuggestionProvider.QWANT, body.toByteArray()))
    }

    @Test
    fun `malformed bodies return empty`() {
        for (provider in SuggestionProvider.values()) {
            assertEquals(emptyList<String>(), ArktosCore.parseSuggestions(provider, "{bad".toByteArray()))
        }
    }
}
