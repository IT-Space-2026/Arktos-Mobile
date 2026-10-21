// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.download

/**
 * Post-completion integrity verdict for a finished download.
 *
 * The system download manager can report a download as complete even when the
 * server closed the connection early (chunked responses carry no
 * Content-Length to check against — upstream SmartCookieWeb #270). When the
 * header probe learned the real length we can detect the truncation and retry
 * instead of leaving a corrupt file in Downloads.
 */
object DownloadIntegrity {

    enum class Verdict {
        COMPLETE,
        TRUNCATED,
        FAILED
    }

    /** Maximum number of attempts (initial + retries) for one download. */
    const val MAX_ATTEMPTS = 3

    /**
     * @param succeeded whether the download manager reported STATUS_SUCCESSFUL
     * @param downloadedBytes bytes actually written
     * @param expectedBytes expected bytes from the header probe, or -1
     */
    fun evaluate(succeeded: Boolean, downloadedBytes: Long, expectedBytes: Long): Verdict {
        if (!succeeded) return Verdict.FAILED
        if (expectedBytes > 0 && downloadedBytes in 0 until expectedBytes) {
            return Verdict.TRUNCATED
        }
        return Verdict.COMPLETE
    }

    /** True when a failed download is worth re-trying (transient reasons). */
    fun isRetryableReason(reason: Int): Boolean = when (reason) {
        // Mirrors android.app.DownloadManager constants, kept as literals so
        // this file stays unit-testable without the Android runtime.
        ERROR_UNKNOWN,
        ERROR_HTTP_DATA_ERROR,
        ERROR_UNHANDLED_HTTP_CODE -> true
        else -> false
    }

    // android.app.DownloadManager error codes (duplicated for JVM tests).
    private const val ERROR_UNKNOWN = 1000
    private const val ERROR_HTTP_DATA_ERROR = 1004
    private const val ERROR_UNHANDLED_HTTP_CODE = 1006

    /**
     * Everything needed to verify a finished download and re-enqueue it when
     * it was truncated. Persisted per system-download-id between the enqueue
     * and the ACTION_DOWNLOAD_COMPLETE broadcast, which may cross process
     * restarts.
     */
    class Meta(
        val url: String,
        val fileName: String,
        val mimeType: String?,
        val userAgent: String?,
        val expectedBytes: Long,
        val attempts: Int = 0
    ) {
        /** Increase the attempt counter. */
        fun retried() = Meta(url, fileName, mimeType, userAgent, expectedBytes, attempts + 1)

        fun encode(): String = listOf(
            url, fileName, mimeType.orEmpty(), userAgent.orEmpty(),
            expectedBytes.toString(), attempts.toString()
        ).joinToString(FIELD_SEPARATOR) { it.replace(FIELD_SEPARATOR, "") }

        companion object {
            // ASCII SOH — cannot legitimately appear in URLs or file names.
            private const val FIELD_SEPARATOR = "\u0001"

            fun decode(raw: String?): Meta? {
                if (raw == null) return null
                val parts = raw.split(FIELD_SEPARATOR)
                if (parts.size != 6) return null
                return Meta(
                    url = parts[0],
                    fileName = parts[1],
                    mimeType = parts[2].ifEmpty { null },
                    userAgent = parts[3].ifEmpty { null },
                    expectedBytes = parts[4].toLongOrNull() ?: -1L,
                    attempts = parts[5].toIntOrNull() ?: 0
                )
            }
        }
    }
}
