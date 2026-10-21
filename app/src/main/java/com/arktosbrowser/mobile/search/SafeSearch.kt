// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.search

/**
 * Forced SafeSearch for parental controls (upstream #327).
 *
 * When [com.arktosbrowser.mobile.preference.UserPreferences.safeSearchForced]
 * is on, every navigation to a
 * known search engine's results endpoint must carry that engine's strict
 * filtering parameter. Enforcement happens at the load layer — in
 * `ArktosCookieView.loadUrl`, `ArktosCookieWebClient.shouldOverrideLoading`
 * and as an `onPageStarted` backstop — so the parameter survives a user
 * editing or sharing the URL, not just searches composed by the URL bar.
 *
 * An existing parameter with a non-strict value (`safe=off`, `adlt=off`,
 * `kp=-2`, `family=0`) is replaced, not just complemented.
 */
object SafeSearch {

    /**
     * One engine's strict-filtering rule: which hosts and path prefix it
     * applies to and the query pair that forces strict results.
     *
     * @param requiredQueryKey when non-null, the rule only fires on URLs
     * that already carry this query key — used for engines that search at
     * their root path, so their home/settings pages aren't rewritten.
     */
    private class Rule(
        val hostMatches: (String) -> Boolean,
        val pathPrefix: String,
        val requiredQueryKey: String?,
        val param: String,
        val value: String
    ) {
        val strictPair = "$param=$value"
    }

    private fun hostIsOrEndsWith(suffix: String): (String) -> Boolean =
        { it == suffix || it.endsWith(".$suffix") }

    private val RULES = listOf(
        // google.<tld> under an optional www. — the "google." prefix
        // covers google.com, google.co.uk, google.com.au, …
        Rule({ it.startsWith("google.") }, "/search", null, "safe", "active"),
        Rule(hostIsOrEndsWith("bing.com"), "/search", null, "adlt", "strict"),
        // DuckDuckGo searches at the root path (and /html/, /lite/), so
        // only rewrite URLs that already carry a query term.
        Rule({ it == "duckduckgo.com" || it.endsWith(".duckduckgo.com") || it == "duck.com" },
            "/", "q", "kp", "1"),
        Rule({ hostIsOrEndsWith("yandex.ru")(it) || hostIsOrEndsWith("yandex.com")(it) },
            "/search", null, "family", "1"),
        Rule(hostIsOrEndsWith("yandex.ru"), "/yandsearch", null, "family", "1")
    )

    /**
     * Returns [url] with the engine's strict safe-search parameter applied
     * when [forced] and the URL matches a known results endpoint; returns
     * [url] unchanged otherwise (off, non-network scheme, unknown host,
     * non-search path, or the strict parameter already present).
     */
    fun enforcedUrl(url: String, forced: Boolean): String {
        if (!forced) return url

        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        val scheme = url.substring(0, schemeEnd)
        if (!scheme.equals("http", ignoreCase = true)
            && !scheme.equals("https", ignoreCase = true)) return url

        val rest = url.substring(schemeEnd + 3)
        val authority = rest.takeWhile { it != '/' && it != '?' && it != '#' }
        val host = authority.substringAfterLast('@').substringBefore(':').lowercase()
        if (host.isEmpty()) return url
        val bareHost = host.removePrefix("www.")

        val afterAuthority = rest.substring(authority.length)
        val path = afterAuthority.takeWhile { it != '?' && it != '#' }
        val query = afterAuthority.substringAfter('?', "").substringBefore('#')

        val rule = RULES.firstOrNull { rule ->
            rule.hostMatches(bareHost)
                && path.startsWith(rule.pathPrefix)
                && (rule.requiredQueryKey == null
                    || query.split('&').any { it.substringBefore('=') == rule.requiredQueryKey })
        } ?: return url

        return withStrictParam(url, rule.param, rule.strictPair)
    }

    /**
     * Rebuilds [url] so its query contains exactly `param=value` from
     * [strictPair]: an already-strict value returns the URL untouched, a
     * present-but-different value is replaced, and a missing parameter is
     * appended after the existing query (before any fragment).
     */
    private fun withStrictParam(url: String, param: String, strictPair: String): String {
        val fragmentSuffix = if ('#' in url) "#" + url.substringAfter('#') else ""
        val base = url.substringBefore('#')
        val queryStart = base.indexOf('?')
        if (queryStart < 0) return "$base?$strictPair$fragmentSuffix"

        val parts = base.substring(queryStart + 1).split('&')
        val strict = strictPair.substringAfter('=')
        if (parts.any {
                it.substringBefore('=').equals(param, ignoreCase = true)
                    && it.substringAfter('=').equals(strict, ignoreCase = true)
            }) return url

        val kept = parts.filter {
            it.isNotEmpty() && !it.substringBefore('=').equals(param, ignoreCase = true)
        }
        return base.substring(0, queryStart + 1) +
            (kept + strictPair).joinToString("&") + fragmentSuffix
    }
}
