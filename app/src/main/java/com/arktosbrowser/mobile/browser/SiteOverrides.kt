package com.arktosbrowser.mobile.browser

import java.net.URI

/**
 * Per-site JavaScript/cookie overrides (upstream #305).
 *
 * Both override sets are keyed on the lowercased host of the page URL —
 * the same granularity WebView exposes through `WebSettings` and the
 * shared `CookieManager`. The state serializes to a compact
 * `host=0,other=1` string so [SiteSettings] can persist it in shared
 * preferences; everything here is plain Kotlin so it is unit-testable
 * off-device.
 */
class SiteOverrides(
    javaScriptSerialized: String = "",
    cookiesSerialized: String = ""
) {

    private val javaScript = decode(javaScriptSerialized)
    private val cookies = decode(cookiesSerialized)

    /**
     * The explicit JavaScript state stored for [url]'s host, or null
     * when the site follows the global/list defaults.
     */
    fun javaScriptOverride(url: String?): Boolean? =
        siteHost(url)?.let(javaScript::get)

    /**
     * The explicit cookie state stored for [url]'s host, or null when
     * the site follows the global default.
     */
    fun cookieOverride(url: String?): Boolean? =
        siteHost(url)?.let(cookies::get)

    /**
     * Flips the effective JavaScript state for [url]'s host and returns
     * the new effective value. When the flipped state equals [default]
     * the override is dropped so the site follows the global setting
     * again — toggling twice always returns a host to inherited state.
     */
    fun toggleJavaScript(url: String?, default: Boolean): Boolean =
        toggle(javaScript, url, default)

    /**
     * Flips the effective cookie state for [url]'s host and returns the
     * new effective value; see [toggleJavaScript].
     */
    fun toggleCookies(url: String?, default: Boolean): Boolean =
        toggle(cookies, url, default)

    /** The serialized JavaScript overrides, for preference storage. */
    fun serializedJavaScript(): String = encode(javaScript)

    /** The serialized cookie overrides, for preference storage. */
    fun serializedCookies(): String = encode(cookies)

    private fun toggle(
        map: MutableMap<String, Boolean>,
        url: String?,
        default: Boolean
    ): Boolean {
        val host = siteHost(url) ?: return default
        val next = !(map[host] ?: default)
        if (next == default) map.remove(host) else map[host] = next
        return next
    }

    companion object {

        /**
         * The host a per-site override is keyed on — the lowercased
         * hostname of [url], or null for URLs that have no host to bind
         * an override to (`about:blank`, `data:`, asset pages, bare
         * text). A scheme is assumed when absent so a bare
         * `example.com/path` key still resolves.
         */
        fun siteHost(url: String?): String? {
            if (url.isNullOrBlank()) return null
            val candidate = if ("://" in url) url else "https://$url"
            return runCatching { URI(candidate).host }.getOrNull()?.lowercase()
        }

        private fun decode(serialized: String): MutableMap<String, Boolean> =
            serialized.split(',')
                .mapNotNull { entry ->
                    val idx = entry.lastIndexOf('=')
                    val value = if (idx > 0) entry.substring(idx + 1).trim() else ""
                    if (idx <= 0 || value.isEmpty()) {
                        null
                    } else {
                        entry.substring(0, idx).trim() to (value == "1")
                    }
                }
                .filter { it.first.isNotEmpty() }
                .toMap(mutableMapOf())

        private fun encode(map: Map<String, Boolean>): String =
            map.entries.joinToString(",") { (host, on) -> "$host=${if (on) "1" else "0"}" }
    }
}
