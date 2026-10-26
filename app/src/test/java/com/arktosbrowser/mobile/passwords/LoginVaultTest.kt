// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.passwords

import com.arktosbrowser.mobile.database.DbKeyEnvelope
import com.arktosbrowser.mobile.database.DbKeyWrapper
import java.io.File
import java.nio.file.Files
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUTOF02 — [LoginVault] persistence/matching. Host tests use a
 * plain JCE AES/GCM wrapper in place of the AndroidKeyStore one, the
 * same seam `DatabaseKeyProviderTest` uses.
 */
class LoginVaultTest {

    private class JceDbKeyWrapper : DbKeyWrapper {
        private val key = KeyGenerator.getInstance("AES")
            .apply { init(256) }
            .generateKey()

        override fun wrap(raw: ByteArray): ByteArray {
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
        Files.createTempDirectory("arktos-logins-$tag").toFile()

    private fun login(
        origin: String = "https://example.com",
        username: String = "user",
        password: String = "secret",
        guid: String? = null
    ) = StoredLogin(
        guid = guid,
        origin = origin,
        formActionOrigin = null,
        httpRealm = null,
        username = username,
        password = password
    )

    @Test
    fun `fresh vault is empty`() {
        val vault = LoginVault(File(tempDir("fresh"), "logins.db"), JceDbKeyWrapper())
        assertTrue(vault.all().isEmpty())
    }

    @Test
    fun `upsert inserts and forOrigin filters`() {
        val vault = LoginVault(File(tempDir("upsert"), "logins.db"), JceDbKeyWrapper())
        vault.upsert(login())
        vault.upsert(login(origin = "https://other.test"))

        assertEquals(1, vault.forOrigin("https://example.com").size)
        assertEquals(2, vault.all().size)
    }

    @Test
    fun `upsert replaces same origin and username`() {
        val vault = LoginVault(File(tempDir("replace"), "logins.db"), JceDbKeyWrapper())
        vault.upsert(login(password = "old"))
        vault.upsert(login(password = "new"))

        assertEquals(1, vault.all().size)
        assertEquals("new", vault.all().single().password)
    }

    @Test
    fun `upsert replaces same guid across usernames`() {
        val vault = LoginVault(File(tempDir("guid"), "logins.db"), JceDbKeyWrapper())
        vault.upsert(login(guid = "g1", username = "old-name"))
        vault.upsert(login(guid = "g1", username = "new-name"))

        assertEquals(1, vault.all().size)
        assertEquals("new-name", vault.all().single().username)
    }

    @Test
    fun `entries survive a new vault instance`() {
        val dir = tempDir("persist")
        val wrapper = JceDbKeyWrapper()
        LoginVault(File(dir, "logins.db"), wrapper).upsert(login(guid = "g1"))

        val reloaded = LoginVault(File(dir, "logins.db"), wrapper)
        assertEquals(1, reloaded.all().size)
        assertEquals("g1", reloaded.all().single().guid)
    }

    @Test
    fun `undecryptable file is quarantined, not fatal`() {
        val dir = tempDir("broken")
        val file = File(dir, "logins.db")
        file.writeBytes("garbage".toByteArray())

        val vault = LoginVault(file, JceDbKeyWrapper())
        assertTrue(vault.all().isEmpty())
        assertTrue(File(dir, "logins.db.broken").isFile)
    }

    @Test
    fun `markUsed updates timestamp`() {
        val vault = LoginVault(File(tempDir("used"), "logins.db"), JceDbKeyWrapper())
        vault.upsert(login(guid = "g1"))
        vault.markUsed(login(guid = "g1"))

        assertTrue(vault.all().single().lastUsed > 0)
    }

    @Test
    fun `clear wipes and persists`() {
        val dir = tempDir("clear")
        val wrapper = JceDbKeyWrapper()
        val file = File(dir, "logins.db")
        LoginVault(file, wrapper).upsert(login())

        val vault = LoginVault(file, wrapper)
        vault.clear()

        assertTrue(vault.all().isEmpty())
        assertTrue(LoginVault(file, wrapper).all().isEmpty())
    }
}
