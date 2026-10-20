package com.arktosbrowser.mobile.utils

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * SEC02 — password-encrypted backup archive.
 *
 * Android Auto Backup copies files verbatim and cannot run a password
 * prompt, so the encrypted restore path lives in the app itself:
 * Settings → Import/Export serializes bookmarks + settings into a JSON
 * payload sealed with AES-256-GCM under a PBKDF2-derived key.
 *
 * Layout on disk:
 *   "ARKBAK" (6) | version (1) | salt (16) | GCM iv (12) | ciphertext+tag
 *
 * The password is never stored — wrong passwords surface as a GCM tag
 * failure in [decrypt], reported to the user as a generic
 * "wrong password or corrupted backup".
 */
object BackupCrypto {

    private val MAGIC = byteArrayOf('A'.code.toByte(), 'R'.code.toByte(), 'K'.code.toByte(),
        'B'.code.toByte(), 'A'.code.toByte(), 'K'.code.toByte())
    private const val VERSION: Byte = 1
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val ITERATIONS = 600_000

    class BackupCryptoException(message: String, cause: Throwable? = null) :
        GeneralSecurityException(message, cause)

    fun isBackupBlob(blob: ByteArray): Boolean =
        blob.size > MAGIC.size + 1 && MAGIC.indices.all { blob[it] == MAGIC[it] }

    fun encrypt(plaintext: ByteArray, password: String): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LEN).also(random::nextBytes)
        val iv = ByteArray(IV_LEN).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(deriveKey(password, salt), "AES"),
            GCMParameterSpec(TAG_BITS, iv)
        )
        return MAGIC + byteArrayOf(VERSION) + salt + iv + cipher.doFinal(plaintext)
    }

    /**
     * Decrypt [blob]; throws [BackupCryptoException] on bad magic,
     * truncation, or a wrong password (GCM tag failure).
     */
    fun decrypt(blob: ByteArray, password: String): ByteArray {
        val headerLen = MAGIC.size + 1 + SALT_LEN + IV_LEN
        if (!isBackupBlob(blob) || blob.size <= headerLen) {
            throw BackupCryptoException("not an Arktos backup")
        }
        if (blob[MAGIC.size] != VERSION) {
            throw BackupCryptoException("unsupported backup version ${blob[MAGIC.size]}")
        }
        val salt = blob.copyOfRange(MAGIC.size + 1, MAGIC.size + 1 + SALT_LEN)
        val iv = blob.copyOfRange(MAGIC.size + 1 + SALT_LEN, headerLen)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        try {
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(deriveKey(password, salt), "AES"),
                GCMParameterSpec(TAG_BITS, iv)
            )
            return cipher.doFinal(blob, headerLen, blob.size - headerLen)
        } catch (e: GeneralSecurityException) {
            throw BackupCryptoException("decrypt failed", e)
        }
    }

    private fun deriveKey(password: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS))
            .encoded
}
