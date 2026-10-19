package com.arktosbrowser.mobile.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verdict matrix for completed-download integrity (upstream #270: the system
 * manager can mark a truncated chunked download "complete") plus the
 * encode/decode round-trip of the persisted retry metadata.
 */
class DownloadIntegrityTest {

    @Test
    fun `failed status is failed`() {
        assertEquals(
            DownloadIntegrity.Verdict.FAILED,
            DownloadIntegrity.evaluate(false, 500, 1000)
        )
    }

    @Test
    fun `success shorter than expected is truncated`() {
        assertEquals(
            DownloadIntegrity.Verdict.TRUNCATED,
            DownloadIntegrity.evaluate(true, 5_000_000, 30_000_000)
        )
        assertEquals(
            DownloadIntegrity.Verdict.TRUNCATED,
            DownloadIntegrity.evaluate(true, 0, 100)
        )
    }

    @Test
    fun `success at or beyond expected length is complete`() {
        assertEquals(
            DownloadIntegrity.Verdict.COMPLETE,
            DownloadIntegrity.evaluate(true, 30_000_000, 30_000_000)
        )
        assertEquals(
            DownloadIntegrity.Verdict.COMPLETE,
            DownloadIntegrity.evaluate(true, 30_000_001, 30_000_000)
        )
    }

    @Test
    fun `unknown expected length cannot be verified`() {
        assertEquals(
            DownloadIntegrity.Verdict.COMPLETE,
            DownloadIntegrity.evaluate(true, 42, -1)
        )
    }

    @Test
    fun `only transient failure reasons retry`() {
        assertTrue(DownloadIntegrity.isRetryableReason(1000)) // ERROR_UNKNOWN
        assertTrue(DownloadIntegrity.isRetryableReason(1004)) // ERROR_HTTP_DATA_ERROR
        assertTrue(DownloadIntegrity.isRetryableReason(1006)) // ERROR_UNHANDLED_HTTP_CODE
        assertFalse(DownloadIntegrity.isRetryableReason(1001)) // ERROR_FILE_ERROR
        assertFalse(DownloadIntegrity.isRetryableReason(1002)) // ERROR_UNHANDLED_REDIRECT
        assertFalse(DownloadIntegrity.isRetryableReason(1008)) // ERROR_CANNOT_RESUME
    }

    @Test
    fun `meta survives an encode decode round trip`() {
        val meta = DownloadIntegrity.Meta(
            url = "https://example.com/dl/app.apk?token=x",
            fileName = "app.apk",
            mimeType = "application/vnd.android.package-archive",
            userAgent = "ArktosBrowser/Test",
            expectedBytes = 30_000_000,
            attempts = 1
        )
        val decoded = DownloadIntegrity.Meta.decode(meta.encode())!!

        assertEquals(meta.url, decoded.url)
        assertEquals(meta.fileName, decoded.fileName)
        assertEquals(meta.mimeType, decoded.mimeType)
        assertEquals(meta.userAgent, decoded.userAgent)
        assertEquals(meta.expectedBytes, decoded.expectedBytes)
        assertEquals(meta.attempts, decoded.attempts)
    }

    @Test
    fun `meta decode tolerates nullables and rejects garbage`() {
        val sparse = DownloadIntegrity.Meta("https://x/y.zip", "y.zip", null, null, -1)
        val decoded = DownloadIntegrity.Meta.decode(sparse.encode())!!
        assertNull(decoded.mimeType)
        assertNull(decoded.userAgent)
        assertEquals(-1L, decoded.expectedBytes)

        assertNull(DownloadIntegrity.Meta.decode(null))
        assertNull(DownloadIntegrity.Meta.decode(""))
        assertNull(DownloadIntegrity.Meta.decode("abc"))
    }

    @Test
    fun `retried increments the attempt counter`() {
        val meta = DownloadIntegrity.Meta("https://x/f", "f", null, null, -1)
        assertEquals(0, meta.attempts)
        assertEquals(1, meta.retried().attempts)
        assertEquals(2, meta.retried().retried().attempts)
    }
}
