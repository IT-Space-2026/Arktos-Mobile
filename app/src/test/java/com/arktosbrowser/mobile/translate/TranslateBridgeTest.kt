package com.arktosbrowser.mobile.translate

import com.arktosbrowser.mobile.rust.ArktosCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The bridge's request validation lives in Rust
 * (`translate_parse_request`) — these exercise it through the UniFFI
 * seam the way the page walker does.
 */
class TranslateBridgeTest {

    @Test
    fun `parseRequest accepts a valid walker request`() {
        val request = ArktosCore.translateParseRequest(
            """{"texts":["Hello","World"],"target":"hu"}"""
        )!!
        assertEquals(listOf("Hello", "World"), request.texts)
        assertEquals("hu", request.target)
    }

    @Test
    fun `parseRequest accepts region and script subtags`() {
        val request = ArktosCore.translateParseRequest(
            """{"texts":["x"],"target":"zh-Hant-TW"}"""
        )!!
        assertEquals("zh-Hant-TW", request.target)
    }

    @Test
    fun `parseRequest defaults missing target to en`() {
        val request = ArktosCore.translateParseRequest("""{"texts":["x"]}""")!!
        assertEquals("en", request.target)
    }

    @Test
    fun `parseRequest rejects malformed json`() {
        assertNull(ArktosCore.translateParseRequest("not json"))
        assertNull(ArktosCore.translateParseRequest("""{"texts":{}}"""))
        assertNull(ArktosCore.translateParseRequest("""{"other":["x"]}"""))
    }

    @Test
    fun `parseRequest rejects empty texts`() {
        assertNull(ArktosCore.translateParseRequest("""{"texts":[],"target":"hu"}"""))
    }

    @Test
    fun `parseRequest rejects non-language targets`() {
        // Page-controlled input must not reach the request builder as a
        // target — long garbage and punctuation are not BCP-47 tags.
        assertNull(
            ArktosCore.translateParseRequest(
                """{"texts":["x"],"target":"hu'; DROP TABLE--"}"""
            )
        )
        assertNull(
            ArktosCore.translateParseRequest(
                """{"texts":["x"],"target":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}"""
            )
        )
    }
}
