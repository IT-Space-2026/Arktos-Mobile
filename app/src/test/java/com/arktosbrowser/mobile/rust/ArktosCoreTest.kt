package com.arktosbrowser.mobile.rust

import com.arktosbrowser.mobile.uniffi.RequestVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM contract for the Rust seam: unit tests run with
 * `jna.library.path` pointed at the host-built `libarktos.so`
 * (rust/target/debug), so these exercise the real Rust core end to
 * end through the generated UniFFI bindings.
 */
class ArktosCoreTest {

    @Test
    fun `seam object initializes`() {
        assertNotNull(ArktosCore)
    }

    @Test
    fun `ping answers through the native library`() {
        assertTrue(ArktosCore.ping())
        assertTrue(ArktosCore.coreVersion().isNotBlank())
    }

    @Test
    fun `verdict allows on an empty engine`() {
        assertEquals(
            RequestVerdict.ALLOW,
            ArktosCore.requestVerdict("https://example.com", "", isMainFrame = true)
        )
    }

    @Test
    fun `store opens and round-trips a bookmark`() {
        val dir = java.nio.file.Files.createTempDirectory("arktos-store-test")
        val store = ArktosCore.openStore("$dir/test.db", TEST_STORE_KEY)
        assertTrue(store.bookmarkCount() == 0uL)
        store.close()
    }

    @Test
    fun `netscape import parses`() {
        val bookmarks = ArktosCore.importNetscapeBookmarks(
            "<DL><DT><A HREF=\"https://x.com\">X</A></DL>"
        )
        assertEquals(1, bookmarks.size)
        assertEquals("https://x.com", bookmarks[0].url)
    }
}
