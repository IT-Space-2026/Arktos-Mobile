package com.arktosbrowser.mobile.rust

import com.arktosbrowser.mobile.uniffi.StorageException
import com.arktosbrowser.mobile.uniffi.StoreBookmark
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SEC15 — the on-disk `arktos.db` is SQLCipher ciphertext under the
 * 32-byte page key. Runs against the real host `libarktos.so` like
 * the other `rust/` tests.
 */
class EncryptedStoreTest {

    private fun tempDir(tag: String): File =
        Files.createTempDirectory("arktos-store-$tag").toFile()

    private fun otherKey() = ByteArray(32) { 0x11 }

    @Test
    fun `database file is ciphertext not sqlite`() {
        val dir = tempDir("enc")
        val path = "$dir/test.db"
        ArktosCore.openStore(path, TEST_STORE_KEY).use { store ->
            store.addBookmarkIfNotExists(StoreBookmark("https://a.com", "A", "", 0u, 0uL))
        }
        val header = File(path).readBytes().copyOfRange(0, 16)
        assertFalse(
            "arktos.db must not carry the plaintext SQLite magic",
            String(header).startsWith("SQLite format 3")
        )
    }

    @Test
    fun `reopen with same key reads rows`() {
        val dir = tempDir("reopen")
        val path = "$dir/test.db"
        ArktosCore.openStore(path, TEST_STORE_KEY).use { store ->
            store.addBookmarkIfNotExists(StoreBookmark("https://keep.me", "Keep", "", 0u, 0uL))
        }
        ArktosCore.openStore(path, TEST_STORE_KEY).use { store ->
            assertTrue(store.isBookmark("https://keep.me"))
            assertEquals("Keep", store.findBookmarkForUrl("https://keep.me")?.title)
        }
    }

    @Test
    fun `wrong key fails to open`() {
        val dir = tempDir("wrongkey")
        val path = "$dir/test.db"
        ArktosCore.openStore(path, TEST_STORE_KEY).close()
        assertThrows(StorageException::class.java) {
            ArktosCore.openStore(path, otherKey())
        }
    }

    @Test
    fun `short key rejected`() {
        val dir = tempDir("shortkey")
        assertThrows(StorageException::class.java) {
            ArktosCore.openStore("$dir/test.db", ByteArray(16))
        }
    }

    @Test
    fun `key provider output opens store`() {
        // The production path feeds DatabaseKeyProvider.loadOrCreate()
        // straight into openStore — pin the 32-byte contract here.
        val dir = tempDir("provider")
        assertEquals(32, com.arktosbrowser.mobile.database.DatabaseKeyProvider.KEY_BYTES)
        ArktosCore.openStore("$dir/test.db", ByteArray(32) { it.toByte() }).close()
    }
}
