// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.network

import com.arktosbrowser.mobile.preference.IntEnum

/**
 * SEC13 — referrer control for top-level page navigations
 * ([com.arktosbrowser.mobile.preference.UserPreferences.referrerMode]).
 *
 * Android WebView can't set a Referrer-Policy and offers no callback
 * to rewrite request headers, but it only auto-attaches a `Referer`
 * to navigations it initiated from page content (link clicks, script
 * redirects). A programmatic `WebView.loadUrl` carries no referrer —
 * so `ArktosCookieWebClient.shouldOverrideUrlLoading` applies this
 * policy by vetoing the navigation and re-issuing it with an explicit
 * header map: [headersFor] returns the map to load with, or null when
 * the navigation should proceed untouched.
 *
 * Applied only to main-frame GET navigations — re-issuing a POST via
 * `loadUrl` would drop the request body, so form submissions keep the
 * default referrer behavior (the "where supported" caveat).
 */
object ReferrerPolicy {

    const val HEADER = "Referer"

    /**
     * DEFAULT — the WebView decides (its default policy already trims
     * cross-origin referrers to the origin and drops them on HTTPS→HTTP
     * downgrades); SAME_ORIGIN — send the source URL as Referer only
     * when source and target share an origin, strip it on every
     * cross-origin navigation; STRIP — never send a Referer.
     */
    enum class Mode(override val value: Int) : IntEnum {
        DEFAULT(0),
        SAME_ORIGIN(1),
        STRIP(2)
    }

    /**
     * The header map the caller must pass to `WebView.loadUrl` when
     * taking a [targetUrl] navigation over from a page currently at
     * [sourceUrl], or null when the navigation should be left to the
     * WebView. Null covers: [Mode.DEFAULT], non-http(s) targets
     * (`intent:`, `mailto:`, generated pages), and same-document
     * fragment navigations — re-issuing `page` → `page#anchor` through
     * `loadUrl` would reload the document instead of scrolling.
     * [baseHeaders] rides along minus any existing Referer entry.
     */
    fun headersFor(
        targetUrl: String,
        sourceUrl: String?,
        mode: Mode,
        baseHeaders: Map<String, String>
    ): Map<String, String>? {
        if (mode == Mode.DEFAULT) return null
        if (originParts(targetUrl) == null) return null
        if (sourceUrl != null && isSameDocumentNavigation(sourceUrl, targetUrl)) return null
        val trimmed = baseHeaders.filterKeys { !it.equals(HEADER, ignoreCase = true) }
        return if (mode == Mode.SAME_ORIGIN &&
            sourceUrl != null && sameOrigin(sourceUrl, targetUrl)) {
            trimmed + (HEADER to sourceUrl)
        } else {
            trimmed
        }
    }

    /**
     * Same-origin means identical scheme, host and effective port
     * (default 80/443 when unwritten) — a `www.` prefix, a subdomain
     * or an HTTPS→HTTP downgrade all count as cross-origin.
     */
    fun sameOrigin(a: String, b: String): Boolean {
        val pa = originParts(a) ?: return false
        return pa == originParts(b)
    }

    /**
     * A same-document navigation differs from its source only in the
     * fragment — the WebView scrolls rather than fetching, so no
     * Referer is ever attached and no takeover is needed. An exact
     * same-URL click is a full navigation and stays on the takeover
     * path.
     */
    fun isSameDocumentNavigation(source: String, target: String): Boolean =
        source != target && source.substringBefore('#') == target.substringBefore('#')

    private data class Origin(val scheme: String, val host: String, val port: String)

    /**
     * Lowercased scheme/host/port of an `http(s)` URL with the default
     * port filled in; null for every other scheme or a URL without a
     * host — only real web origins can send or receive a referrer.
     */
    private fun originParts(url: String): Origin? {
        val scheme = url.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") return null
        val authority = url.substringAfter("://")
            .takeWhile { it != '/' && it != '?' && it != '#' }
            .substringAfterLast('@')
            .lowercase()
        if (authority.isEmpty()) return null
        val host: String
        val port: String
        if (authority.startsWith('[')) {
            // IPv6 literal — the brackets stay on the host so the
            // port is only ever read after the closing "]:".
            if (!authority.contains(']')) return null
            host = authority.substringBefore(']') + "]"
            port = if (authority.length > host.length && authority[host.length] == ':') {
                authority.substring(host.length + 1)
            } else {
                ""
            }
        } else {
            host = authority.substringBefore(':')
            port = authority.substringAfter(':', "")
        }
        if (host.isEmpty()) return null
        return Origin(scheme, host, port.ifEmpty { if (scheme == "https") "443" else "80" })
    }
}
