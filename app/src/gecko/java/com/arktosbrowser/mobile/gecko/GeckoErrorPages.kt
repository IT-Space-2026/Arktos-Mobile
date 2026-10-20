package com.arktosbrowser.mobile.gecko

import android.app.Activity
import android.util.Base64
import com.arktosbrowser.mobile.AppTheme
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.html.ChangelogPage
import com.arktosbrowser.mobile.network.HttpsOnlyGate
import com.arktosbrowser.mobile.network.NavigationDecision
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.rust.ArktosCore
import com.arktosbrowser.mobile.rust.ArktosCore.buildErrorPage
import org.mozilla.geckoview.WebRequestError
import java.io.IOException

/**
 * ENG04 — builds the generated error/block/interstitial pages the
 * GeckoView backend serves where `ArktosCookieWebClient` answers a
 * `shouldInterceptRequest`/`loadDataWithBaseURL` on the WebView side.
 *
 * GeckoView has no mid-request response injection, so pages are
 * loaded via `GeckoSession.Loader.data` (navigation-time interstitials)
 * or handed to `NavigationDelegate.onLoadError` as `data:` URIs — both
 * commit synthetic `data:` documents, and `GeckoPage` tracks the real
 * page URL the same way `errorPageOriginalUrl` does on WebView.
 */
class GeckoErrorPages(
    private val activity: Activity,
    private val userPreferences: UserPreferences
) {

    /**
     * The color passed to generated block/error pages — cleared on the
     * light themes, matching the WebView client's `blockPageColor`.
     */
    private fun blockPageColor(): String =
        if (userPreferences.resolvedTheme(activity) == AppTheme.LIGHT ||
            userPreferences.resolvedTheme(activity) == AppTheme.ARKTOS_LIGHT) {
            ""
        } else {
            COLOR_DARK
        }

    /** HTML for one of the [NavigationDecision] interstitials. */
    fun interstitialHtml(
        page: NavigationDecision.GeneratedPage,
        originalUrl: String
    ): String = when (page) {
        NavigationDecision.GeneratedPage.Changelog -> changelogHtml()
        NavigationDecision.GeneratedPage.Offline -> offlineErrorPage(originalUrl)
        NavigationDecision.GeneratedPage.HttpsOnly -> httpsOnlyWarningPage(originalUrl)
        NavigationDecision.GeneratedPage.BlockedAds -> blockedAdsPage(originalUrl)
        NavigationDecision.GeneratedPage.BlockedSite -> ArktosCore.buildErrorPage(
            blockPageColor(),
            activity.getString(R.string.blocked_title),
            "NET::SITE_BLOCKED_BY_LIST",
            activity.getString(R.string.page_blocked_hint),
            activity.getString(R.string.error_reload),
            false
        )
        NavigationDecision.GeneratedPage.Malware -> ArktosCore.buildErrorPage(
            blockPageColor(),
            activity.getString(R.string.malware_title),
            activity.getString(R.string.malware_message),
            activity.getString(R.string.page_blocked_hint),
            activity.getString(R.string.error_reload),
            false
        )
        NavigationDecision.GeneratedPage.LocalNetwork -> ArktosCore.buildErrorPage(
            blockPageColor(),
            activity.getString(R.string.blocked_title),
            activity.getString(R.string.page_blocked_lan),
            activity.getString(R.string.page_blocked_hint),
            activity.getString(R.string.error_reload),
            false
        )
    }

    /**
     * CHLOG01 — the bundled changelog with the dark palette applied and
     * its `arktos://changelog/<asset>` artwork references rewritten to
     * `resource://android/assets/` (GeckoView's APK-assets shorthand —
     * `file:///android_asset/` is a WebView-ism it does not support).
     */
    private fun changelogHtml(): String = try {
        activity.assets.open("changelog.html").bufferedReader()
            .use { it.readText() }
            .replace("arktos://changelog/", RESOURCE_ASSETS_PREFIX)
            .let { ChangelogPage.applyDarkTheme(it, blockPageColor().isNotEmpty()) }
    } catch (e: IOException) {
        ArktosCore.buildErrorPage(
            blockPageColor(),
            activity.getString(R.string.error_title),
            "file not found",
            activity.getString(R.string.error_page_hint),
            activity.getString(R.string.error_reload),
            true
        )
    }

    /**
     * The offline variant of the generated error page (upstream #323) —
     * the reload button navigates back to [url], re-running the
     * connectivity check.
     */
    private fun offlineErrorPage(url: String): String =
        buildErrorPage(
            blockPageColor(),
            activity.getString(R.string.error_offline_title),
            activity.getString(R.string.error_offline_message),
            activity.getString(R.string.error_page_hint),
            activity.getString(R.string.error_reload),
            true,
            "window.location.href = '" + url + "';"
        )

    /**
     * SEC04 — the HTTPS-only warning interstitial. The proceed button
     * navigates to [HttpsOnlyGate.proceedUrl], which the navigation
     * delegate resolves back to the cleartext target while the warning
     * is displayed.
     */
    private fun httpsOnlyWarningPage(cleartextUrl: String): String =
        ArktosCore.buildHttpsWarningPage(
            blockPageColor(),
            activity.getString(R.string.https_only_title),
            "NET::ERR_CLEARTEXT_NOT_PERMITTED",
            cleartextUrl,
            activity.getString(R.string.https_only_hint),
            activity.getString(R.string.https_only_upgrade),
            HttpsOnlyGate.upgradedUrl(cleartextUrl),
            activity.getString(R.string.proceed),
            HttpsOnlyGate.proceedUrl(cleartextUrl),
            activity.getString(R.string.go_back)
        )

    /** The ad-blocker interstitial — "proceed" reloads the URL. */
    private fun blockedAdsPage(url: String): String =
        ArktosCore.buildBlockPage(
            blockPageColor(),
            activity.getString(R.string.page_blocked),
            activity.getString(R.string.page_blocked_adblocker),
            url,
            activity.getString(R.string.page_blocked_hint),
            activity.getString(R.string.proceed),
            activity.getString(R.string.go_back),
            true
        )

    /**
     * The generic load-failure page served for
     * `NavigationDelegate.onLoadError`. [offline] selects the
     * connectivity-loss wording (upstream #323); the reload button
     * re-issues the navigation.
     */
    fun loadErrorPage(url: String, error: WebRequestError, offline: Boolean): String {
        val reloadCode = "window.location.href = '" + url + "';"
        return buildErrorPage(
            blockPageColor(),
            activity.getString(
                if (offline) R.string.error_offline_title else R.string.error_title
            ),
            if (offline) {
                activity.getString(R.string.error_offline_message)
            } else {
                describe(error)
            },
            activity.getString(R.string.error_page_hint),
            activity.getString(R.string.error_reload),
            true,
            reloadCode
        )
    }

    /**
     * The certificate-error interstitial (ENG04 SSL path). GeckoView
     * cannot bypass certificate errors — there is no
     * `SslErrorHandler.proceed` equivalent — so unlike the WebView
     * dialog this page offers only "go back"; proceed-consent is a
     * documented gap.
     */
    fun certErrorPage(url: String, error: WebRequestError): String =
        buildErrorPage(
            blockPageColor(),
            activity.getString(R.string.title_warning),
            activity.getString(R.string.message_insecure_connection, describe(error)),
            activity.getString(R.string.error_page_hint),
            activity.getString(R.string.go_back),
            true,
            "window.history.back();"
        )

    /** Human-readable form of a [WebRequestError]'s category+code. */
    private fun describe(error: WebRequestError): String {
        val code = when (error.code) {
            WebRequestError.ERROR_SECURITY_SSL -> "SEC_ERROR_SSL"
            WebRequestError.ERROR_SECURITY_BAD_CERT -> "SEC_ERROR_BAD_CERTIFICATE"
            WebRequestError.ERROR_BAD_HSTS_CERT -> "SEC_ERROR_HSTS"
            WebRequestError.ERROR_NET_INTERRUPT -> "net::ERR_INTERRUPTED"
            WebRequestError.ERROR_NET_TIMEOUT -> "net::ERR_TIMED_OUT"
            WebRequestError.ERROR_CONNECTION_REFUSED -> "net::ERR_CONNECTION_REFUSED"
            WebRequestError.ERROR_UNKNOWN_SOCKET_TYPE -> "net::ERR_SOCKET"
            WebRequestError.ERROR_REDIRECT_LOOP -> "net::ERR_TOO_MANY_REDIRECTS"
            WebRequestError.ERROR_OFFLINE -> "net::ERR_INTERNET_DISCONNECTED"
            WebRequestError.ERROR_PORT_BLOCKED -> "net::ERR_UNSAFE_PORT"
            WebRequestError.ERROR_NET_RESET -> "net::ERR_CONNECTION_RESET"
            WebRequestError.ERROR_HTTPS_ONLY -> "net::ERR_CLEARTEXT_NOT_PERMITTED"
            WebRequestError.ERROR_UNSAFE_CONTENT_TYPE -> "net::ERR_UNSAFE_CONTENT_TYPE"
            WebRequestError.ERROR_CORRUPTED_CONTENT -> "net::ERR_CONTENT_DECODING"
            WebRequestError.ERROR_CONTENT_CRASHED -> "net::ERR_CONTENT_CRASHED"
            WebRequestError.ERROR_INVALID_CONTENT_ENCODING -> "net::ERR_CONTENT_DECODING"
            WebRequestError.ERROR_UNKNOWN_HOST -> "net::ERR_NAME_NOT_RESOLVED"
            WebRequestError.ERROR_MALFORMED_URI -> "net::ERR_INVALID_URL"
            WebRequestError.ERROR_UNKNOWN_PROTOCOL -> "net::ERR_UNKNOWN_URL_SCHEME"
            WebRequestError.ERROR_FILE_NOT_FOUND -> "net::ERR_FILE_NOT_FOUND"
            WebRequestError.ERROR_FILE_ACCESS_DENIED -> "net::ERR_ACCESS_DENIED"
            WebRequestError.ERROR_DATA_URI_TOO_LONG -> "net::ERR_DATA_URI_TOO_LONG"
            WebRequestError.ERROR_PROXY_CONNECTION_REFUSED -> "net::ERR_PROXY_CONNECTION"
            WebRequestError.ERROR_UNKNOWN_PROXY_HOST -> "net::ERR_PROXY_UNKNOWN_HOST"
            WebRequestError.ERROR_SAFEBROWSING_MALWARE_URI -> "safebrowsing::malware"
            WebRequestError.ERROR_SAFEBROWSING_UNWANTED_URI -> "safebrowsing::unwanted"
            WebRequestError.ERROR_SAFEBROWSING_HARMFUL_URI -> "safebrowsing::harmful"
            WebRequestError.ERROR_SAFEBROWSING_PHISHING_URI -> "safebrowsing::phishing"
            WebRequestError.ERROR_HARMFULADDON_URI -> "safebrowsing::harmful_addon"
            else -> "error ${error.code}"
        }
        return code
    }

    companion object {

        /** GeckoView's shorthand for files in the APK `assets/` dir. */
        const val RESOURCE_ASSETS_PREFIX = "resource://android/assets/"

        /** What WebView serves instead: `file:///android_asset/<name>`. */
        const val ANDROID_ASSET_PREFIX = "file:///android_asset/"

        /**
         * Wrap generated-page HTML as a `data:` URI — the form
         * `NavigationDelegate.onLoadError` accepts for a custom error
         * page (the android-components/Fenix pattern).
         */
        fun dataUri(html: String): String =
            "data:text/html;base64," + Base64.encodeToString(
                html.toByteArray(Charsets.UTF_8), Base64.NO_WRAP
            )

        private const val COLOR_DARK =
            "<style>body{background-color:#424242;} h1{color:#ffffff;}</style>"
    }
}
