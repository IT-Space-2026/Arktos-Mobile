package com.arktosbrowser.mobile.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * SEC02 — encrypted backup archive round-trip tests. PBKDF2 + AES-GCM
 * run on the JVM, so the whole seal/open path is unit-testable.
 */
class BackupCryptoTest {

    private val payload = """{"v":1,"settings":"{}","bookmarks":"{}"}"""
        .toByteArray(Charsets.UTF_8)

    @Test
    fun `round trip restores the payload`() {
        val blob = BackupCrypto.encrypt(payload, "hunter2")
        assertTrue(BackupCrypto.isBackupBlob(blob))
        assertArrayEquals(payload, BackupCrypto.decrypt(blob, "hunter2"))
    }

    @Test
    fun `wrong password is rejected`() {
        val blob = BackupCrypto.encrypt(payload, "right")
        try {
            BackupCrypto.decrypt(blob, "wrong")
            fail("expected BackupCryptoException")
        } catch (expected: BackupCrypto.BackupCryptoException) {
        }
    }

    @Test
    fun `truncated blob is rejected`() {
        val blob = BackupCrypto.encrypt(payload, "pw")
        try {
            BackupCrypto.decrypt(blob.copyOf(blob.size - 4), "pw")
            fail("expected BackupCryptoException")
        } catch (expected: BackupCrypto.BackupCryptoException) {
        }
    }

    @Test
    fun `foreign file is rejected`() {
        assertFalse(BackupCrypto.isBackupBlob("plain text".toByteArray()))
        try {
            BackupCrypto.decrypt("plain text".toByteArray(), "pw")
            fail("expected BackupCryptoException")
        } catch (expected: BackupCrypto.BackupCryptoException) {
        }
    }

    @Test
    fun `tampering breaks the tag`() {
        val blob = BackupCrypto.encrypt(payload, "pw")
        blob[blob.size - 1] = (blob[blob.size - 1] + 1).toByte()
        try {
            BackupCrypto.decrypt(blob, "pw")
            fail("expected BackupCryptoException")
        } catch (expected: BackupCrypto.BackupCryptoException) {
        }
    }

    @Test
    fun `salt and iv are random per archive`() {
        val a = BackupCrypto.encrypt(payload, "pw")
        val b = BackupCrypto.encrypt(payload, "pw")
        assertNotEquals(a.toList(), b.toList())
    }
}
