// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.database

import java.io.File
import java.nio.file.Files
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SEC15 — the store-key provider persists a Keystore-wrapped random
 * key. Host tests substitute a plain JCE AES/GCM wrapper for the
 * AndroidKeyStore one — the envelope codec and load/generate logic
 * are identical.
 */
class DatabaseKeyProviderTest {

    /** In-memory stand-in for `AndroidKeystoreDbKeyWrapper`. */
    private class JceDbKeyWrapper : DbKeyWrapper {
        private val key = KeyGenerator.getInstance("AES")
            .apply { init(256) }
            .generateKey()

        var wrapCalls = 0
            private set

        override fun wrap(raw: ByteArray): ByteArray {
            wrapCalls++
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            return DbKeyEnvelope.encode(cipher.iv, cipher.doFinal(raw))
        }

        override fun unwrap(envelope: ByteArray): ByteArray? {
            val (iv, ciphertext) = DbKeyEnvelope.decode(envelope) ?: return null
            return try {
                Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                }.doFinal(ciphertext)
            } catch (e: GeneralSecurityException) {
                null
            }
        }
    }

    private fun tempDir(tag: String): File =
        Files.createTempDirectory("arktos-key-$tag").toFile()

    @Test
    fun `first load generates and persists a 32-byte key`() {
        val dir = tempDir("fresh")
        val file = File(dir, "store.key")
        val wrapper = JceDbKeyWrapper()

        val key = DatabaseKeyProvider(file, wrapper).loadOrCreate()

        assertEquals(32, key.size)
        assertTrue(file.isFile)
        // The blob on disk is the wrapped form — never the raw key.
        assertTrue(file.readBytes().size > key.size)
        assertArrayEquals(key, wrapper.unwrap(file.readBytes()))
    }

    @Test
    fun `second provider instance unwraps the same key`() {
        val dir = tempDir("persist")
        val file = File(dir, "store.key")
        val wrapper = JceDbKeyWrapper()

        val first = DatabaseKeyProvider(file, wrapper).loadOrCreate()
        val wrapCalls = wrapper.wrapCalls
        val second = DatabaseKeyProvider(file, wrapper).loadOrCreate()

        assertArrayEquals(first, second)
        // No re-wrap happened on the warm path.
        assertEquals(wrapCalls, wrapper.wrapCalls)
    }

    @Test
    fun `undecryptable blob regenerates a fresh key`() {
        val dir = tempDir("regen")
        val file = File(dir, "store.key")
        file.writeBytes("garbage".toByteArray())

        val wrapper = JceDbKeyWrapper()
        val key = DatabaseKeyProvider(file, wrapper).loadOrCreate()

        assertEquals(32, key.size)
        // The rewritten blob unwraps to what was returned.
        assertArrayEquals(key, wrapper.unwrap(file.readBytes()))
    }

    @Test
    fun `tampered ciphertext regenerates`() {
        val dir = tempDir("tampered")
        val file = File(dir, "store.key")
        val wrapper = JceDbKeyWrapper()
        val original = DatabaseKeyProvider(file, wrapper).loadOrCreate()

        val blob = file.readBytes()
        blob[blob.size - 1] = (blob[blob.size - 1] + 1).toByte()
        file.writeBytes(blob)

        val regenerated = DatabaseKeyProvider(file, wrapper).loadOrCreate()
        assertEquals(32, regenerated.size)
        assertTrue("GCM tag check must reject the tampered blob", !original.contentEquals(regenerated))
    }

    @Test
    fun `envelope decode rejects malformed blobs`() {
        assertNull(DbKeyEnvelope.decode(byteArrayOf()))
        assertNull(DbKeyEnvelope.decode(byteArrayOf(1)))
        assertNull(DbKeyEnvelope.decode(byteArrayOf(9, 12) + ByteArray(20))) // bad version
        assertNull(DbKeyEnvelope.decode(byteArrayOf(1, 0))) // empty iv
        assertNull(DbKeyEnvelope.decode(byteArrayOf(1, 12) + ByteArray(5))) // truncated iv
        assertNull(DbKeyEnvelope.decode(byteArrayOf(1, 12) + ByteArray(12))) // no ciphertext
    }

    @Test
    fun `envelope roundtrips`() {
        val iv = ByteArray(12) { it.toByte() }
        val ct = ByteArray(48) { (it * 3).toByte() }
        val decoded = DbKeyEnvelope.decode(DbKeyEnvelope.encode(iv, ct))
        assertNotNull(decoded)
        assertArrayEquals(iv, decoded!!.first)
        assertArrayEquals(ct, decoded.second)
    }
}
