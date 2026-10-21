// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.utils

/**
 * Pure decision logic for routing a tapped link to a third-party app,
 * mirroring the `intent://` resolution order used by Chrome and Fulguris
 * (upstream issue #316):
 *
 * 1. the app the link pinned via `package=` when it is installed,
 * 2. any other installed app that specifically handles the link
 *    (e.g. a third-party client registered for that site's URLs),
 * 3. the link's `browser_fallback_url`,
 * 4. an app-store search for the pinned package,
 * 5. staying in the browser.
 *
 * Previously step 2 did not exist: an uninstalled `package=` pin jumped
 * straight to a Play Store search even when another installed app (e.g.
 * a FOSS Reddit client) was set up to open the same links.
 *
 * Kept free of android.* types so the ordering can be unit-tested.
 */
object ExternalAppLaunchPolicy {

    enum class Action {
        /** Fire the parsed intent — package pin kept when the pinned app
         *  resolved, dropped when another installed handler claims it. */
        LAUNCH,

        /** Load the `browser_fallback_url` extra into the current tab. */
        LOAD_FALLBACK,

        /** Open an app-store search for the pinned package. */
        OPEN_MARKET,

        /** Nothing external can handle the link — keep loading in-app. */
        STAY_IN_BROWSER
    }

    @JvmStatic
    fun decide(
        pinnedResolvable: Boolean,
        installedSpecializedHandler: Boolean,
        fallbackUrl: String?,
        pinnedPackage: String?
    ): Action = when {
        pinnedResolvable -> Action.LAUNCH
        installedSpecializedHandler -> Action.LAUNCH
        isLoadableFallbackUrl(fallbackUrl) -> Action.LOAD_FALLBACK
        pinnedPackage != null -> Action.OPEN_MARKET
        else -> Action.STAY_IN_BROWSER
    }

    /**
     * Only http(s) fallback URLs are honoured — otherwise a page could
     * smuggle a `javascript:`/`file:` URL into `browser_fallback_url` and
     * have it loaded into the tab.
     */
    @JvmStatic
    fun isLoadableFallbackUrl(url: String?): Boolean =
        url != null &&
            (url.startsWith("https://", ignoreCase = true) ||
                url.startsWith("http://", ignoreCase = true))

    /**
     * Whether any of the [authorities] declared by an intent filter refers
     * to [host] — either an exact match or the host sitting under a
     * declared domain. This is what separates a real third-party handler
     * (e.g. a Reddit client declaring `reddit.com`) from a generic browser
     * whose filters declare bare http/https schemes and would otherwise
     * "handle" every link — including looping the URL back into this
     * browser.
     */
    @JvmStatic
    fun authorityMatchesHost(authorities: List<String>, host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val target = host.lowercase()
        return authorities.any { authority ->
            val base = authority.lowercase().removePrefix("*.").removePrefix(".")
            base.isNotEmpty() && (target == base || target.endsWith(".$base"))
        }
    }
}
