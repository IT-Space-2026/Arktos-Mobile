// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.html

import android.util.Base64
import android.util.Log
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.view.ProxyUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves start-page tile icons into `data:` URIs. Many sites only declare
 * icons through `<link rel="icon">` tags and serve nothing at `/favicon.ico`
 * (e.g. it-space.info keeps them under assets/), so the page HTML is parsed
 * first and `/favicon.ico` is only a fallback. Results are cached in memory;
 * an empty string marks a site with no usable icon.
 */
@Singleton
class ShortcutIconResolver @Inject constructor(
    private val userPreferences: UserPreferences
) {

    private val cache = ConcurrentHashMap<String, String>()

    fun resolve(pageUrl: String): String? {
        val origin = runCatching { URI(pageUrl).let { "${it.scheme}://${it.host}" } }.getOrNull()
            ?: return null
        if (!cache.containsKey(origin)) {
            val declared = runCatching { fetchDeclaredIcon(origin, pageUrl) }
                .onFailure { Log.w(TAG, "declared icon fetch failed for $origin", it) }
                .getOrNull()
            cache[origin] = declared
                ?: runCatching { fetchIcon("$origin/favicon.ico") }
                    .onFailure { Log.w(TAG, "favicon.ico fetch failed for $origin", it) }
                    .getOrNull()
                ?: ""
        }
        return cache[origin]?.ifEmpty { null }
    }

    private fun fetchDeclaredIcon(origin: String, pageUrl: String): String? {
        val html = fetchText(pageUrl) ?: return null.also {
            Log.d(TAG, "$origin: page fetch returned null")
        }
        val doc = Jsoup.parse(html, origin)
        val candidates = doc.select("link[href]")
            .filter { it.attr("rel").lowercase().contains("icon") }
            .sortedByDescending { iconScore(it) }
        if (candidates.isEmpty()) {
            Log.d(TAG, "$origin: no icon link tags")
            return null
        }
        // Sites sometimes declare icons whose files are missing — fall through
        // the ranked list until one actually downloads.
        for (link in candidates) {
            val href = link.attr("abs:href")
            if (href.isEmpty()) continue
            fetchIcon(href)?.let { return it }
        }
        Log.d(TAG, "$origin: all declared icons failed (${candidates.size})")
        return null
    }

    // Regular icons outrank apple-touch; larger declared sizes outrank smaller.
    private fun iconScore(link: Element): Int {
        val rel = link.attr("rel").lowercase()
        val base = if ("apple" in rel) 0 else 10_000
        val size = if ("apple" in rel) {
            link.attr("sizes").substringBefore('x').toIntOrNull() ?: 180
        } else {
            link.attr("sizes").substringBefore('x').toIntOrNull() ?: 32
        }
        return base + size
    }

    private fun fetchText(url: String): String? = open(url)?.use { conn ->
        String(conn.inputStream.readBounded(MAX_HTML_BYTES))
    }

    private fun fetchIcon(url: String): String? = open(url)?.use { conn ->
        val body = conn.inputStream.readBounded(MAX_ICON_BYTES)
        if (body.isEmpty()) {
            null
        } else {
            val mime = conn.contentType?.substringBefore(';')
                ?.takeIf { it.startsWith("image/") }
                ?: mimeFromExtension(url)
            "data:$mime;base64," + Base64.encodeToString(body, Base64.NO_WRAP)
        }
    }

    private fun open(url: String): HttpURLConnection? {
        val proxy = ProxyUtils.connectionProxy(userPreferences)
        val conn = if (proxy != null) URL(url).openConnection(proxy) else URL(url).openConnection()
        conn as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        return if (conn.responseCode in 200..299) conn else {
            Log.d(TAG, "$url -> HTTP ${conn.responseCode}")
            conn.disconnect()
            null
        }
    }

    private fun mimeFromExtension(url: String): String = when {
        url.contains(".svg") -> "image/svg+xml"
        url.contains(".ico") -> "image/x-icon"
        url.contains(".jpg") || url.contains(".jpeg") -> "image/jpeg"
        url.contains(".webp") -> "image/webp"
        url.contains(".gif") -> "image/gif"
        else -> "image/png"
    }

    private fun InputStream.readBounded(max: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(max, 64 * 1024))
        val buffer = ByteArray(8192)
        var total = 0
        while (total < max) {
            val read = read(buffer, 0, minOf(buffer.size, max - total))
            if (read < 0) break
            out.write(buffer, 0, read)
            total += read
        }
        return out.toByteArray()
    }

    private fun HttpURLConnection.use(block: (HttpURLConnection) -> String?): String? =
        try {
            block(this)
        } finally {
            disconnect()
        }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 3_000
        const val READ_TIMEOUT_MS = 4_000
        const val MAX_HTML_BYTES = 256 * 1024
        const val MAX_ICON_BYTES = 512 * 1024
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
        const val TAG = "ShortcutIcons"
    }
}
