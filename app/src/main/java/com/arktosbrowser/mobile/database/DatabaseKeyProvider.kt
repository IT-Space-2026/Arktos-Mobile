package com.arktosbrowser.mobile.database

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * SEC15 — at-rest encryption for `arktos.db`.
 *
 * The Rust `ArktosStore` runs SQLCipher with a raw 256-bit page key.
 * That key is generated once here and persisted wrapped by an
 * Android Keystore AES/GCM key, so the database file alone — or the
 * wrapped blob alone — is useless without the device keystore.
 *
 * The spec's password-derived variant (argon2id of the app-lock
 * credential) is deliberately not used: the credential survives only
 * as a hash, and `ArktosStore` is constructed by DI before the
 * app-lock gate finishes — the page key must be available at open
 * time regardless of lock state.
 */

/** Version byte prefix on the persisted wrapped-key blob. */
internal object DbKeyEnvelope {
    const val VERSION: Byte = 1

    /** `VERSION || ivLen || iv || ciphertext`. */
    fun encode(iv: ByteArray, ciphertext: ByteArray): ByteArray =
        byteArrayOf(VERSION, iv.size.toByte()) + iv + ciphertext

    /** Splits an envelope back into (iv, ciphertext); null when malformed. */
    fun decode(blob: ByteArray): Pair<ByteArray, ByteArray>? {
        if (blob.size < 2 || blob[0] != VERSION) return null
        val ivLen = blob[1].toInt() and 0xFF
        if (ivLen == 0 || blob.size <= 2 + ivLen) return null
        return blob.copyOfRange(2, 2 + ivLen) to blob.copyOfRange(2 + ivLen, blob.size)
    }
}

/**
 * Wraps/unwraps the raw store key. `unwrap` returns null when the
 * envelope can't be decrypted — a lost Keystore key means the old
 * database is unrecoverable, and the provider falls back to a fresh
 * key (the unreadable DB is then quarantined by the caller).
 */
interface DbKeyWrapper {
    fun wrap(raw: ByteArray): ByteArray
    fun unwrap(envelope: ByteArray): ByteArray?
}

/** AES/GCM wrap under an Android Keystore-held 256-bit key. */
class AndroidKeystoreDbKeyWrapper(
    private val alias: String = KEYSTORE_ALIAS,
) : DbKeyWrapper {

    private fun keystoreKey(): javax.crypto.SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val generator =
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build()
        )
        return generator.generateKey()
    }

    override fun wrap(raw: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        return DbKeyEnvelope.encode(cipher.iv, cipher.doFinal(raw))
    }

    override fun unwrap(envelope: ByteArray): ByteArray? {
        val (iv, ciphertext) = DbKeyEnvelope.decode(envelope) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (e: GeneralSecurityException) {
            // Keystore entry invalidated or tampered blob — the caller
            // regenerates; the old database stays unreadable either way.
            null
        }
    }

    private companion object {
        const val KEYSTORE_ALIAS = "arktos_store_key"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
    }
}

/**
 * Loads the raw 32-byte SQLCipher key for `ArktosStore.open`,
 * generating + persisting a wrapped copy on first launch. [keyFile]
 * lives in the app-private files dir; [wrapper] is the keystore seam
 * (a plain JCE wrapper in host unit tests).
 */
class DatabaseKeyProvider(
    private val keyFile: File,
    private val wrapper: DbKeyWrapper,
) {

    fun loadOrCreate(): ByteArray {
        readWrapped()?.let { if (it.size == KEY_BYTES) return it }
        val raw = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
        writeWrapped(raw)
        return raw
    }

    private fun readWrapped(): ByteArray? = try {
        if (keyFile.isFile) wrapper.unwrap(keyFile.readBytes()) else null
    } catch (e: IOException) {
        null
    }

    private fun writeWrapped(raw: ByteArray) {
        keyFile.parentFile?.mkdirs()
        val tmp = File(keyFile.parentFile, "${keyFile.name}.tmp")
        tmp.writeBytes(wrapper.wrap(raw))
        if (!tmp.renameTo(keyFile)) {
            tmp.delete()
            keyFile.writeBytes(wrapper.wrap(raw))
        }
    }

    companion object {
        const val KEY_BYTES = 32
    }
}
