// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.download

import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.net.URL

/**
 * Lightweight header probe for downloads.
 *
 * Before a URL is handed to the system download manager we fetch only its
 * response headers so we learn the real Content-Type, Content-Disposition and
 * Content-Length — the values the WebView download listener reports are often
 * guesses (application/octet-stream) and its URL may still point at a redirect
 * or interstitial page that would be saved as an .html file instead of the
 * file the user asked for.
 *
 * Pure JVM (java.net only) so it is unit-testable and stays engine-neutral.
 */
object DownloadHttpProbe {

    private const val MAX_REDIRECTS = 5
    private const val CONNECT_TIMEOUT_MS = 7_000
    private const val READ_TIMEOUT_MS = 7_000

    /** Headers recovered from the server, or `null` when the probe failed. */
    class Result(
        /** URL of the final response after redirects. */
        val finalUrl: String,
        /** HTTP status of the final response. */
        val statusCode: Int,
        /** Content-Type without parameters, lower-cased, or `null`. */
        val contentType: String?,
        /** Raw Content-Disposition header, or `null`. */
        val contentDisposition: String?,
        /** Expected body bytes, or -1 when the server did not say. */
        val contentLength: Long,
        /** Whether the server advertises `Accept-Ranges: bytes`. */
        val acceptsRanges: Boolean
    )

    /**
     * Fetch the response headers for [url]. Returns `null` when the server is
     * unreachable, the URL is malformed, or the request never yields a usable
     * response — callers must fall back to the WebView-supplied metadata.
     *
     * A HEAD request is tried first; servers that reject it (405/501) or omit
     * Content-Type are retried with a GET whose body is never read.
     */
    @JvmStatic
    fun probe(
        url: String,
        cookies: String? = null,
        userAgent: String? = null,
        referer: String? = null,
        proxy: Proxy? = null
    ): Result? {
        val head = fetch(url, "HEAD", cookies, userAgent, referer, proxy)
        val needsGet = head == null || head.statusCode >= 400 ||
            (head.statusCode in 200..299 && head.contentType == null && head.contentDisposition == null)
        if (needsGet) {
            // HEAD rejected or told us nothing useful — retry as GET.
            val get = fetch(url, "GET", cookies, userAgent, referer, proxy)
            if (get != null && (head == null || get.statusCode in 200..299)) {
                return get
            }
        }
        return head
    }

    private fun fetch(
        url: String,
        method: String,
        cookies: String?,
        userAgent: String?,
        referer: String?,
        proxy: Proxy? = null
    ): Result? {
        var current = url
        var hops = 0
        while (hops <= MAX_REDIRECTS) {
            val connection = try {
                (if (proxy != null) URL(current).openConnection(proxy)
                else URL(current).openConnection()) as? HttpURLConnection
            } catch (e: Exception) {
                null
            } ?: return null

            try {
                connection.instanceFollowRedirects = false
                connection.requestMethod = method
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                // Always send the page's user agent: some file hosts answer
                // requests that lack a browser UA with an HTML interstitial,
                // which is then what gets saved to disk.
                if (!userAgent.isNullOrBlank()) {
                    connection.setRequestProperty("User-Agent", userAgent)
                }
                if (!cookies.isNullOrBlank()) {
                    connection.setRequestProperty("Cookie", cookies)
                }
                if (!referer.isNullOrBlank()) {
                    connection.setRequestProperty("Referer", referer)
                }
                connection.setRequestProperty("Accept", "*/*")

                val code = connection.responseCode
                val location = connection.getHeaderField("Location")
                if (code in 300..399 && !location.isNullOrBlank()) {
                    val next = resolveLocation(current, location) ?: return null
                    connection.disconnect()
                    current = next
                    hops++
                    continue
                }

                return Result(
                    finalUrl = current,
                    statusCode = code,
                    contentType = normalizedContentType(connection.getHeaderField("Content-Type")),
                    contentDisposition = connection.getHeaderField("Content-Disposition"),
                    contentLength = contentLengthOf(connection),
                    acceptsRanges = connection.getHeaderField("Accept-Ranges")
                        ?.trim()?.equals("bytes", ignoreCase = true) == true
                )
            } catch (e: IOException) {
                return null
            } catch (e: IllegalStateException) {
                return null
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    private fun normalizedContentType(raw: String?): String? =
        raw?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    private fun contentLengthOf(connection: HttpURLConnection): Long {
        val range = connection.getHeaderField("Content-Range")
        if (range != null) {
            // "bytes 0-0/1234" — total follows the slash
            val total = range.substringAfter('/', "").trim().toLongOrNull()
            if (total != null && total >= 0) return total
        }
        return connection.contentLengthLong
    }

    private fun resolveLocation(base: String, location: String): String? = try {
        URI(base).resolve(location).toString()
    } catch (e: Exception) {
        null
    }
}
