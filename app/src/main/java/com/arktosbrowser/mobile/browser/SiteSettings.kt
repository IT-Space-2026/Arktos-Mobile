package com.arktosbrowser.mobile.browser

import com.arktosbrowser.mobile.preference.UserPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Effective per-site JavaScript/cookie state (upstream #305): an
 * explicit per-host override layer on top of the global switches and
 * the legacy JavaScript site list. A toolbar toggle flips whatever the
 * effective value is for the current origin — if the site currently
 * runs JavaScript the toggle turns it off for that host, and vice
 * versa — and persists so the choice survives restarts.
 */
@Singleton
class SiteSettings @Inject constructor(
    private val userPreferences: UserPreferences
) {

    private val overrides = SiteOverrides(
        javaScriptSerialized = userPreferences.perSiteJavaScriptOverrides,
        cookiesSerialized = userPreferences.perSiteCookieOverrides
    )

    /**
     * The explicit per-site JavaScript state for [url]'s host, or null
     * when the site inherits the global/list defaults.
     */
    fun javaScriptOverride(url: String?): Boolean? = overrides.javaScriptOverride(url)

    /**
     * Whether JavaScript should run for [url]: the per-site override
     * when set, otherwise the global switch ANDed with the legacy site
     * list.
     */
    fun javaScriptEnabled(url: String?): Boolean =
        overrides.javaScriptOverride(url) ?: defaultJavaScriptEnabled(url)

    /**
     * Whether cookies should be accepted for [url]: the per-site
     * override when set, otherwise the global cookie switch.
     */
    fun cookiesEnabled(url: String?): Boolean =
        overrides.cookieOverride(url) ?: userPreferences.cookiesEnabled

    /**
     * Flips JavaScript for [url]'s host, persists the new override set
     * and returns the new effective state so callers can push it into
     * the WebSettings before reloading.
     */
    fun toggleJavaScript(url: String?): Boolean =
        overrides.toggleJavaScript(url, defaultJavaScriptEnabled(url)).also {
            userPreferences.perSiteJavaScriptOverrides = overrides.serializedJavaScript()
        }

    /**
     * Flips cookie acceptance for [url]'s host, persists the new
     * override set and returns the new effective state so callers can
     * push it into the CookieManager before reloading.
     */
    fun toggleCookies(url: String?): Boolean =
        overrides.toggleCookies(url, userPreferences.cookiesEnabled).also {
            userPreferences.perSiteCookieOverrides = overrides.serializedCookies()
        }

    private fun defaultJavaScriptEnabled(url: String?): Boolean =
        userPreferences.javaScriptEnabled && url != null &&
            JavaScriptSiteList.allowsJavaScript(
                userPreferences.javaScriptChoice,
                userPreferences.javaScriptBlocked,
                url
            )
}
