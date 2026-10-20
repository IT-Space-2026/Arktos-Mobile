package com.arktosbrowser.mobile.view

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * SEC06 — the GM bridge's `@connect` gate for `GM_xmlhttpRequest`.
 *
 * Until now any page could post `{op:'xhr'}` to `window.arktosGM`
 * and have the browser fetch arbitrary hosts through the
 * proxy-aware OkHttp client — a CORS-free request primitive for
 * every site. The engine now keeps a host-side copy of each
 * installed script's declared `@connect` list (parsed by the Rust
 * userscript parser into the `javascript.connects` column) and
 * answers requests this object rejects with an error reply.
 *
 * Rules, mirroring Tampermonkey/Greasemonkey:
 *
 *  * Same-host requests are always allowed — the page could issue
 *    them itself with plain `fetch`, so a script that declares no
 *    `@connect` keeps working for the origin it runs on but gets no
 *    cross-origin XHR.
 *  * `*` allows every host; `self` allows the posting page's host.
 *  * `example.com` and `*.example.com` both allow the domain and its
 *    subdomains. Entries written as full URLs or with ports are
 *    normalized down to the host before matching.
 */
object GmConnectPolicy {

    /** Host of [url], or null when it isn't an absolute http(s) URL. */
    fun hostOf(url: String?): String? =
        url?.toHttpUrlOrNull()?.host?.lowercase()

    /**
     * Whether [targetHost] may be reached from a page at [pageHost]
     * under the script's declared [connects] list.
     */
    fun hostAllowed(connects: List<String>, pageHost: String?, targetHost: String?): Boolean {
        val target = targetHost?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        val page = pageHost?.lowercase()
        if (page != null && target == page) return true
        return connects.any { entryAllows(it, target, page) }
    }

    private fun entryAllows(connect: String, target: String, page: String?): Boolean {
        val entry = normalize(connect)
        return when {
            entry.isEmpty() -> false
            entry == "*" -> true
            entry == "self" -> page != null && target == page
            else -> {
                val domain = entry.removePrefix("*.")
                domain.isNotEmpty() && (target == domain || target.endsWith(".$domain"))
            }
        }
    }

    /**
     * `@connect` values are occasionally written as full URLs or
     * host:port pairs — reduce each to a bare host pattern before
     * matching. `*` and `self` pass through untouched.
     */
    private fun normalize(connect: String): String {
        val e = connect.trim().lowercase()
        if (e == "*" || e == "self") return e
        val host = e.substringAfter("://", e)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('@')
            .substringBefore(':')
        return when {
            host.startsWith("*.") -> host
            host.startsWith('*') -> "*.${host.removePrefix("*")}"
            else -> host
        }
    }

    /**
     * Whether a script with [includes]/[excludes] would run on
     * [pageUrl] — mirrors `__arktosMatches`/`__arktosExec` in the
     * bootstrap: the include set defaults to `.*`, matching is
     * full-string, and any exclude hit vetoes. Malformed patterns
     * count as no match, like the JS `try/catch`.
     */
    fun runsOn(includes: List<String>, excludes: List<String>, pageUrl: String): Boolean {
        if (matchesAny(excludes, pageUrl)) return false
        return matchesAny(includes.ifEmpty { listOf(".*") }, pageUrl)
    }

    private fun matchesAny(patterns: List<String>, url: String): Boolean =
        patterns.any { pattern ->
            runCatching { Regex("^(?:$pattern)$").matches(url) }.getOrDefault(false)
        }
}
