// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.network

import com.arktosbrowser.mobile.rust.ArktosCore
import com.arktosbrowser.mobile.search.SafeSearch
import com.arktosbrowser.mobile.uniffi.RequestVerdict
import com.arktosbrowser.mobile.utils.isAboutUrl
import com.arktosbrowser.mobile.utils.isFileUrl
import com.arktosbrowser.mobile.utils.isSpecialUrl

/**
 * ENG04 — the engine-neutral composition of every frame-navigation
 * gate the browser applies, in the order `ArktosCookieWebClient`
 * applies them (`shouldOverrideUrlLoading` +
 * `shouldInterceptRequest`): the HTTPS-only consent marker, chrome
 * pages, SafeSearch, strict/prefer HTTPS, the intent-scheme allowlist
 * and external-app resolution, the Rust-side blocklist verdicts,
 * the offline probe, and referrer trimming.
 *
 * The WebView client still runs its own inline copy — this class is
 * the shared, unit-testable expression of that ordering the GeckoView
 * backend consumes (its navigation delegates cannot see POST bodies
 * or subresource requests; the caller maps decisions onto whatever
 * its engine can do). Decisions carry no Android or engine types.
 */
sealed class NavigationDecision {

    /** The engine loads the request untouched. */
    object Proceed : NavigationDecision()

    /**
     * Deny the request and re-issue it for [url] — SafeSearch
     * enforcement, prefer-HTTPS upgrade and referrer takeover all end
     * here. [referrer] is the Referer the re-issued load must carry
     * (null = none; only meaningful for engines that let a load carry
     * an explicit referrer, like GeckoView's `Loader.referrer`).
     */
    data class Redirect(val url: String, val referrer: String? = null) : NavigationDecision()

    /**
     * Deny the request and serve a generated interstitial for
     * [originalUrl] instead — the caller builds the HTML (theme +
     * localized strings live on the engine side).
     */
    data class Interstitial(
        val page: GeneratedPage,
        val originalUrl: String
    ) : NavigationDecision()

    /** Generated page flavors the caller must be able to render. */
    enum class GeneratedPage {
        Changelog,
        Offline,
        HttpsOnly,
        BlockedAds,
        BlockedSite,
        Malware,
        LocalNetwork
    }

    /** Drop the request with no replacement content. */
    object Deny : NavigationDecision()
}

/**
 * Inputs for one frame navigation. [triggerUrl] is the page that
 * caused the load (GeckoView's `LoadRequest.triggerUri`, the WebView's
 * current URL); [referrerEligible] mirrors the WebView client's
 * "main-frame GET only" guard — on GeckoView there is no method on
 * the load request, so the caller passes `hasUserGesture` and accepts
 * that a form POST loses its body under a non-default referrer mode
 * (same power/pit trade-off the WebView avoids by inspecting
 * `request.method`).
 */
data class FrameNavigation(
    val url: String,
    val isMainFrame: Boolean,
    val isRedirect: Boolean,
    val hasUserGesture: Boolean,
    val triggerUrl: String?
)

/**
 * Preference snapshot for one decision, so [NavigationPolicy] itself
 * stays free of `UserPreferences` and tests stay small.
 */
data class NavigationPolicyConfig(
    val safeSearchForced: Boolean,
    val forceHttps: Boolean,
    val preferHttps: Boolean,
    val referrerMode: ReferrerPolicy.Mode,
    val blockIntent: Boolean,
    val incognito: Boolean,
    val connected: Boolean,
    /** `AllowListModel.isUrlAllowedAds(currentUrl)` — resolved by the caller. */
    val adsAllowedOnPage: Boolean
)

class NavigationPolicy {

    /**
     * Session-scoped HTTPS-only consent (SEC04): hosts the user
     * proceeded to from a displayed warning.
     */
    private val cleartextAllowedHosts = mutableSetOf<String>()

    /** prefer-HTTPS upgrade-once bookkeeping (no ping-pong). */
    private val httpsUpgradedHosts = mutableSetOf<String>()

    /**
     * The cleartext URL the HTTPS-only interstitial is currently shown
     * for — the proceed marker is honored only while a warning is up.
     */
    var httpsInterstitialUrl: String? = null
        private set

    /** Last host a main-frame ad-block page was shown for (proceed-once). */
    private var lastBlockedDomain = ""

    /**
     * Mirrors `ArktosCookieWebClient.onPageStarted`: a committed
     * navigation disarms the HTTPS-only consent marker unless it is
     * the interstitial's own generated page.
     */
    fun onNavigationCommitted(committedUrl: String, isGeneratedPage: Boolean) {
        if (committedUrl != httpsInterstitialUrl && !isGeneratedPage) {
            httpsInterstitialUrl = null
        }
    }

    /**
     * Whether [url] is handled by an external app instead of the
     * engine — mailto/tel/custom schemes, `intent://` links and
     * browsable files. The caller performs the actual launch; when
     * the launch fails it falls through to [decideMainFrameInternal].
     */
    fun isExternalUrl(url: String): Boolean =
        url.startsWith("mailto:") ||
            url.startsWith("tel:") ||
            url.startsWith("intent://") ||
            url.startsWith("gh4b://") ||
            url.contains("://oauth") ||
            (url.isFileUrl() && !url.isSpecialUrl())

    /**
     * Decide a main-frame navigation. [launchExternal] performs the
     * external-app launch for [FrameNavigation.url] and reports
     * whether it was handled — mirroring
     * `isMailOrIntent || intentUtils.startActivityForUrl(...)`.
     */
    fun decideMainFrame(
        nav: FrameNavigation,
        config: NavigationPolicyConfig,
        launchExternal: (String) -> Boolean
    ): NavigationDecision {
        val url = nav.url

        // SEC04 — the consent marker only resolves while its warning
        // is displayed, and is swallowed either way so it can never
        // navigate.
        val proceedTarget = HttpsOnlyGate.parseProceedUrl(url)
        if (proceedTarget != null) {
            val warned = httpsInterstitialUrl
            httpsInterstitialUrl = null
            return if (proceedTarget == warned) {
                HttpsOnlyGate.hostOf(proceedTarget)
                    .takeIf { it.isNotEmpty() }
                    ?.let(cleartextAllowedHosts::add)
                NavigationDecision.Redirect(proceedTarget)
            } else {
                NavigationDecision.Deny
            }
        }

        // arktos:// chrome pages — the changelog must load even with
        // every blocklist enabled; the caller serves it.
        if (ArktosCore.isChangelogUrl(url)) {
            return NavigationDecision.Interstitial(
                NavigationDecision.GeneratedPage.Changelog, url
            )
        }

        // Forced SafeSearch (upstream #327): re-issue the navigation
        // with the strict parameter restored.
        val safeUrl = SafeSearch.enforcedUrl(url, config.safeSearchForced)
        if (safeUrl != url) {
            return NavigationDecision.Redirect(safeUrl)
        }

        // SEC04 — strict HTTPS-only mode stops cleartext navigations
        // and answers them with the warning page.
        if (HttpsOnlyGate.isCleartextNavigation(
                url, config.forceHttps, cleartextAllowedHosts
            )
        ) {
            httpsInterstitialUrl = url
            return NavigationDecision.Interstitial(
                NavigationDecision.GeneratedPage.HttpsOnly, url
            )
        }

        // prefer-HTTPS: upgrade once per host, then fall through on
        // retry so a site without HTTPS can still load (no ping-pong).
        // The WebView runs this from onPageStarted; a navigation
        // delegate sees it earlier, which is equivalent for a rewrite.
        if (config.preferHttps && url.startsWith("http://")) {
            val host = HttpsOnlyGate.hostOf(url)
            if (host !in httpsUpgradedHosts) {
                httpsUpgradedHosts += host
                return NavigationDecision.Redirect(HttpsOnlyGate.upgradedUrl(url))
            }
        }

        // Incognito never hands a URL to another app; with the
        // "don't leave the app" pref only mailto/tel-style links do —
        // mirrors `isIncognito || blockIntent && !isMailOrIntent`.
        // `about:` pages also stay inside (`URLUtil.isAboutUrl` gate).
        val wantsExternal = isExternalUrl(url)
        if (!config.incognito && !url.isAboutUrl() &&
            !(config.blockIntent && !wantsExternal) &&
            (wantsExternal || launchExternal(url))) {
            return NavigationDecision.Deny
        }

        // continueLoadingUrl's tail: unloadable schemes are dropped
        // before the referrer takeover re-issues the load.
        if (!isLoadableUrl(url)) return NavigationDecision.Deny

        return decideByReferrer(nav, config)
            // The interception phase: verdicts and the offline probe —
            // on WebView these run in shouldInterceptRequest, after
            // the override gates above claimed (or passed) the load.
            ?: decideByVerdict(url, nav, config)
            ?: decideByConnectivity(url, config)
            ?: NavigationDecision.Proceed
    }

    /**
     * Blocklist verdict + offline short-circuit for sub-frame
     * navigations — the [decideMainFrame] subset that applies below
     * the top document. A denied sub-frame counts as a blocked
     * request for the shield badge, like the WebView interception.
     */
    fun decideSubFrame(
        url: String,
        pageUrl: String?,
        adsAllowedOnPage: Boolean
    ): NavigationDecision {
        if (!isNetworkUrl(url)) return NavigationDecision.Proceed
        return when (ArktosCore.requestVerdict(url, pageUrl.orEmpty(), false)) {
            RequestVerdict.BLOCK_ADS ->
                if (adsAllowedOnPage) NavigationDecision.Proceed else NavigationDecision.Deny
            RequestVerdict.ALLOW -> NavigationDecision.Proceed
            else -> NavigationDecision.Deny
        }
    }

    private fun decideByVerdict(
        url: String,
        nav: FrameNavigation,
        config: NavigationPolicyConfig
    ): NavigationDecision? {
        if (!isNetworkUrl(url)) return null
        return when (ArktosCore.requestVerdict(
            url, nav.triggerUrl ?: nav.url, nav.isMainFrame
        )) {
            RequestVerdict.BLOCK_ADS ->
                if (config.adsAllowedOnPage) {
                    null
                } else if (nav.isMainFrame) {
                    // Same proceed-once contract as the WebView: the
                    // block page's proceed button reloads the URL —
                    // that retry is let through for the same host.
                    val host = HttpsOnlyGate.hostOf(url)
                    if (host == lastBlockedDomain) {
                        null
                    } else {
                        lastBlockedDomain = host
                        NavigationDecision.Interstitial(
                            NavigationDecision.GeneratedPage.BlockedAds, url
                        )
                    }
                } else {
                    NavigationDecision.Deny
                }
            RequestVerdict.BLOCK_SITE -> NavigationDecision.Interstitial(
                NavigationDecision.GeneratedPage.BlockedSite, url
            )
            RequestVerdict.BLOCK_MALWARE -> NavigationDecision.Interstitial(
                NavigationDecision.GeneratedPage.Malware, url
            )
            RequestVerdict.BLOCK_LOCAL_NETWORK ->
                if (nav.isMainFrame) {
                    NavigationDecision.Interstitial(
                        NavigationDecision.GeneratedPage.LocalNetwork, url
                    )
                } else {
                    NavigationDecision.Deny
                }
            RequestVerdict.ALLOW -> null
        }
    }

    private fun decideByConnectivity(
        url: String,
        config: NavigationPolicyConfig
    ): NavigationDecision? =
        if (ConnectivityGate.isNetworkPageRequest(url) && !config.connected) {
            NavigationDecision.Interstitial(
                NavigationDecision.GeneratedPage.Offline, url
            )
        } else {
            null
        }

    private fun decideByReferrer(
        nav: FrameNavigation,
        config: NavigationPolicyConfig
    ): NavigationDecision? {
        val mode = config.referrerMode
        if (mode == ReferrerPolicy.Mode.DEFAULT || !nav.isMainFrame) return null
        val source = nav.triggerUrl ?: return null
        if (ReferrerPolicy.isSameDocumentNavigation(source, nav.url)) return null
        // Non-http(s) targets can't send or receive a referrer — and
        // re-issuing generated/scheme URLs would just duplicate the
        // load. Mirrors ReferrerPolicy.headersFor's null cases.
        val scheme = nav.url.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") return null
        return NavigationDecision.Redirect(
            nav.url,
            referrer = if (mode == ReferrerPolicy.Mode.SAME_ORIGIN &&
                ReferrerPolicy.sameOrigin(source, nav.url)) {
                source
            } else {
                null
            }
        )
    }

    private fun isNetworkUrl(url: String): Boolean =
        url.startsWith("http://") || url.startsWith("https://")

    /**
     * Schemes an engine may actually navigate to — mirrors the tail of
     * `continueLoadingUrl` (network/file/about/data/javascript) plus
     * the local-content schemes GeckoView understands. Anything else
     * is dropped instead of erroring out.
     */
    private fun isLoadableUrl(url: String): Boolean =
        isNetworkUrl(url) || url.isFileUrl() || url.isAboutUrl() ||
            url.startsWith("data:") || url.startsWith("javascript:") ||
            url.startsWith("resource://") || url.startsWith("about:") ||
            url.startsWith("blob:") || url.startsWith("content:") ||
            url.startsWith("view-source:")
}
