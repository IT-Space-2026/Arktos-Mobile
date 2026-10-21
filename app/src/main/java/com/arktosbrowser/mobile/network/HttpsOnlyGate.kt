// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.network

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * SEC04 — strict HTTPS-only mode ([com.arktosbrowser.mobile.preference.UserPreferences.forceHTTPSenabled]).
 *
 * In strict mode a cleartext `http://` main-frame navigation is not
 * silently upgraded: `ArktosCookieWebClient` answers it with a warning
 * interstitial before the request reaches the network
 * (`shouldInterceptRequest`), with `shouldOverrideUrlLoading` and
 * `onPageStarted` as backstops for navigations that bypass
 * interception (e.g. POST submissions).
 *
 * The interstitial's "Proceed anyway" navigates to the [proceedUrl]
 * marker — an `arktos://insecure-allow` URL the client resolves back
 * to the cleartext target via [parseProceedUrl] — so a host lands in
 * the session consent set only through an explicit button press, and
 * only while the warning is actually on screen.
 */
object HttpsOnlyGate {

    /**
     * The interstitial "Proceed anyway" target prefix; the cleartext
     * URL follows percent-encoded in the `u` parameter.
     */
    const val PROCEED_URL_PREFIX = "arktos://insecure-allow?u="

    /**
     * True when [url] is a cleartext navigation that strict mode must
     * warn on: `http://` scheme, strict mode on, and the host not yet
     * consented to via the interstitial's proceed button. `https://`,
     * generated pages and all other schemes never warn.
     */
    fun isCleartextNavigation(url: String, strict: Boolean, allowedHosts: Set<String>): Boolean =
        strict && url.startsWith("http://") && hostOf(url) !in allowedHosts

    /**
     * The `https://` upgrade of a cleartext URL, preserving host,
     * port, path, query and fragment — the interstitial's primary
     * button target.
     */
    fun upgradedUrl(cleartextUrl: String): String =
        "https://" + cleartextUrl.removePrefix("http://")

    /**
     * The consent-marker URL the proceed button navigates to: the
     * cleartext target rides percent-encoded so it survives being
     * embedded in the generated page's `location.href` handler.
     */
    fun proceedUrl(cleartextUrl: String): String =
        PROCEED_URL_PREFIX + URLEncoder.encode(cleartextUrl, Charsets.UTF_8.name())

    /**
     * The cleartext URL carried by a [proceedUrl] marker, or null when
     * [url] isn't a marker or decodes to anything but an `http://`
     * address — the marker must never become a generic redirector.
     */
    fun parseProceedUrl(url: String): String? {
        if (!url.startsWith(PROCEED_URL_PREFIX)) return null
        val target = runCatching {
            URLDecoder.decode(url.removePrefix(PROCEED_URL_PREFIX), Charsets.UTF_8.name())
        }.getOrNull() ?: return null
        return target.takeIf { it.startsWith("http://") }
    }

    /**
     * Lowercased host of a URL, without userinfo or port; "" when the
     * URL has no authority — an empty host can never enter the consent
     * set, so malformed cleartext URLs keep warning.
     */
    fun hostOf(url: String): String {
        val rest = url.substringAfter("://", "")
        if (rest.isEmpty()) return ""
        val authority = rest.takeWhile { it != '/' && it != '?' && it != '#' }
        return authority.substringAfterLast('@').substringBefore(':').lowercase()
    }
}
