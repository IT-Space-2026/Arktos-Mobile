package com.arktosbrowser.mobile.rust

import com.arktosbrowser.mobile.database.Bookmark
import com.arktosbrowser.mobile.database.bookmark.toBookmarkEntry
import com.arktosbrowser.mobile.database.downloads.DownloadEntry
import com.arktosbrowser.mobile.database.bookmark.toStoreBookmark
import com.arktosbrowser.mobile.database.downloads.toDownloadEntry
import com.arktosbrowser.mobile.database.downloads.toStoreDownloadEntry
import com.arktosbrowser.mobile.database.history.toHistoryEntry
import com.arktosbrowser.mobile.database.javascript.JavaScriptEntry
import com.arktosbrowser.mobile.database.javascript.toJavaScriptEntry
import com.arktosbrowser.mobile.database.javascript.toStoreEntry
import com.arktosbrowser.mobile.uniffi.StoreBookmark
import com.arktosbrowser.mobile.uniffi.StoreDownloadEntry
import com.arktosbrowser.mobile.uniffi.StoreHistoryEntry
import com.arktosbrowser.mobile.uniffi.StoreJavaScriptEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-side coverage for the STORE01 record mappings. The generated
 * UniFFI records are plain data classes — constructible without
 * `libarktos.so`, unlike the `ArktosStore` object itself.
 */
class StoreMappingTest {

    @Test
    fun `bookmark entry to store record`() {
        val entry = Bookmark.Entry(
            url = "https://a.com",
            title = "A",
            position = 2,
            folder = Bookmark.Folder.Entry(url = "folder://Dev", title = "Dev")
        )

        val record = entry.toStoreBookmark("Untitled")

        assertEquals("https://a.com", record.url)
        assertEquals("A", record.title)
        assertEquals("Dev", record.folder)
        assertEquals(2u, record.position)
        assertEquals(0uL, record.createdAt)
    }

    @Test
    fun `blank bookmark title falls back to default`() {
        val entry = Bookmark.Entry("https://a.com", "  ", 0, Bookmark.Folder.Root)
        assertEquals("Untitled", entry.toStoreBookmark("Untitled").title)
    }

    @Test
    fun `store record to bookmark entry maps root folder`() {
        val root = StoreBookmark("https://a.com", "A", "", 0u, 5u).toBookmarkEntry()
        assertEquals(Bookmark.Folder.Root, root.folder)

        val nested = StoreBookmark("https://a.com", "A", "Dev/Rust", 0u, 5u).toBookmarkEntry()
        assertEquals("Dev/Rust", nested.folder.title)
        assertEquals("folder://Dev/Rust", nested.folder.url)
    }

    @Test
    fun `history record maps epoch millis`() {
        val entry = StoreHistoryEntry("https://a.com", "A", 123u).toHistoryEntry()
        assertEquals(123L, entry.lastTimeVisited)
    }

    @Test
    fun `download entry roundtrips`() {
        val entry = DownloadEntry(
            url = "https://f.com/x.zip",
            title = "x.zip",
            contentSize = "1 MB"
        )
        assertEquals(entry, entry.toStoreDownloadEntry().toDownloadEntry())
    }

    @Test
    fun `javascript entry roundtrips nullables`() {
        val entry = JavaScriptEntry(
            name = "s",
            namespace = null,
            version = "1.0",
            author = "me",
            include = "*",
            exclude = null,
            time = null,
            permissions = null,
            code = "code()",
            requirements = "req"
        )
        val store = entry.toStoreEntry()
        assertEquals("s", store.name)
        assertEquals(null, store.namespace)
        assertEquals("code()", store.code)
        assertEquals(entry, store.toJavaScriptEntry())
    }

    @Test
    fun `javascript entry from store tolerates null code`() {
        val store = StoreJavaScriptEntry(
            name = "s",
            namespace = null,
            version = null,
            author = null,
            include = null,
            exclude = null,
            time = null,
            permissions = null,
            code = "",
            requirements = null
        )
        assertTrue(store.toJavaScriptEntry().code.isEmpty())
    }
}
