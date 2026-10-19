package com.arktosbrowser.mobile.adblock

import com.arktosbrowser.mobile.rust.ArktosCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PORT02 — the hosts-file parser moved to the Rust core
 * (`blocklist.rs`); the Kotlin `HostsFileParser` and `Host` wrapper
 * were deleted. This test keeps the same fixture and expectation,
 * now asserting through the `ArktosCore.parseHostsDomains` seam.
 */
class HostsFileParserTest {

    @Test
    fun `line parsing is valid`() {
        val testInput = """
            127.0.0.1 localhost #comment comment
            0.0.0.0 localhost #comment comment
            ::1 localhost #comment comment
            #
            # comment
            #
            127.0.0.1	fake.domain1.com
            127.0.0.1	fake.domain2.com    # comment
            0.0.0.0	fake.domain3.com    # comment
            # comment
            ::1 example.com
            0.0.0.0 multi1.com multi2.com # comment
            0.0.0.0 close.comment.com#comment
            """

        val domains = ArktosCore.parseHostsDomains(testInput.trimIndent())

        assertEquals(7, domains.size)
        assertTrue(
            domains.containsAll(
                listOf(
                    "fake.domain1.com",
                    "fake.domain2.com",
                    "fake.domain3.com",
                    "example.com",
                    "multi1.com",
                    "multi2.com",
                    "close.comment.com"
                )
            )
        )
    }
}
