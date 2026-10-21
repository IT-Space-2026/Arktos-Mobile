// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.rust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PORT01 — seam tests for userscript parsing, the legacy bookmark
 * importer, and readability extraction. Runs against the real
 * host-built libarktos.so via `jna.library.path`.
 */
class Port01SeamTest {

    @Test
    fun `userscript metadata parses`() {
        val meta = ArktosCore.parseUserScript(
            "// ==UserScript==\n" +
                "// @name Test\n" +
                "// @version 1.0\n" +
                "// @require https://cdn.example.com/lib.js\n" +
                "// @include example.com/*\n" +
                "// ==/UserScript==\n" +
                "console.log('x');"
        )
        assertTrue(meta.hasMetadataHeader)
        assertEquals("Test", meta.name)
        assertEquals("1.0", meta.version)
        assertEquals(listOf("https://cdn.example.com/lib.js"), meta.requires)
        assertEquals(listOf("^\\w+://example.com/*"), meta.includes)
        assertTrue(meta.code.contains("console.log"))
    }

    @Test
    fun `userscript connect domains are captured`() {
        val meta = ArktosCore.parseUserScript(
            "// ==UserScript==\n" +
                "// @name Test\n" +
                "// @connect api.example.com\n" +
                "// @connect *\n" +
                "// ==/UserScript==\n" +
                "x();"
        )
        assertEquals(listOf("api.example.com", "*"), meta.connects)
    }

    @Test
    fun `userscript without name reports it`() {
        val meta = ArktosCore.parseUserScript("console.log('x');")
        assertNull(meta.name)
    }

    @Test
    fun `legacy bookmark import is strict`() {
        val entries = ArktosCore.importLegacyBookmarks(
            "{\"url\":\"https://a.com\",\"title\":\"A\",\"folder\":\"Docs\",\"order\":0}\n"
        )
        assertNotNull(entries)
        assertEquals(1, entries!!.size)
        assertEquals("Docs", entries[0].folder)

        assertNull(ArktosCore.importLegacyBookmarks("not json\n"))
    }

    @Test
    fun `default bookmark parse is lenient`() {
        val entries = ArktosCore.parseDefaultBookmarks(
            "garbage line\n{\"url\":\"https://a.com\",\"title\":\"A\",\"folder\":\"\",\"order\":0}\n"
        )
        assertEquals(1, entries.size)
    }

    @Test
    fun `readability extracts an article`() {
        val article = ArktosCore.extractReadableArticle(
            "https://example.com/post",
            "<!DOCTYPE html><html><head><title>T</title></head><body>" +
                "<article><h1>Title</h1>" +
                "<p>Enough paragraph text for readability to score this node " +
                "as a candidate, with several sentences of real prose.</p>" +
                "<p>A second paragraph adds more readable content here.</p>" +
                "</article></body></html>"
        )
        assertNotNull(article)
        assertTrue(article!!.content.isNotEmpty())
        assertTrue(article.textContent.contains("paragraph"))
    }
}
