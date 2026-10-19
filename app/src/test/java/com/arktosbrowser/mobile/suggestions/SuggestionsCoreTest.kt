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
            "http://suggestion.baidu.com/s?wd=abc&action=opensearch",
            ArktosCore.suggestionQueryUrl(SuggestionProvider.BAIDU, "abc", "en")
        )
        assertEquals(
            "https://ac.search.naver.com/nx/ac?q=abc&q_enc=UTF-8&st=100&frm=nv" +
                "&r_format=json&r_enc=UTF-8&r_unicode=0&t_koreng=1&ans=2&run=2&rev=4&con=1",
            ArktosCore.suggestionQueryUrl(SuggestionProvider.NAVER, "abc", "en")
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
    fun `baidu json body parses`() {
        val body = """["q",["one","two"]]"""
        assertEquals(listOf("one", "two"), ArktosCore.parseSuggestions(SuggestionProvider.BAIDU, body.toByteArray()))
    }

    @Test
    fun `naver json body parses`() {
        val body = """{"items":[[["soup","0"],["sugar","0"]]]}"""
        assertEquals(listOf("soup", "sugar"), ArktosCore.parseSuggestions(SuggestionProvider.NAVER, body.toByteArray()))
    }

    @Test
    fun `malformed bodies return empty`() {
        for (provider in SuggestionProvider.values()) {
            assertEquals(emptyList<String>(), ArktosCore.parseSuggestions(provider, "{bad".toByteArray()))
        }
    }
}
