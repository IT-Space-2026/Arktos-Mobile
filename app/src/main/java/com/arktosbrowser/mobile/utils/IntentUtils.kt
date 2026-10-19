package com.arktosbrowser.mobile.utils

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.webkit.WebView
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.constant.INTENT_ORIGIN
import com.arktosbrowser.mobile.rust.ArktosCore
import java.net.URISyntaxException
import java.util.ArrayList

class IntentUtils(private val activity: Activity) {

    // PORT02 — the ACCEPTED_URI_SCHEMA regex lives in the Rust core
    // (`ArktosCore.acceptedUriScheme`), shared with UrlUtils.

    fun startActivityForUrl(tab: WebView?, url: String): Boolean {
        var intent: Intent = try {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        } catch (ex: URISyntaxException) {
            Log.w("Browser", "Bad URI $url: ${ex.message}")
            return false
        }

        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null

        val packageManager = activity.packageManager
        val pinnedResolvable = packageManager.resolveActivity(intent, 0) != null
        var unpinned: Intent? = null
        var otherHandlerInstalled = false
        if (!pinnedResolvable) {
            // The app the link pinned via package= is not installed. Before
            // sending the user to the store, check whether any other
            // installed app specifically handles this link — e.g. a
            // third-party client registered for the site's URLs (upstream
            // issue #316).
            unpinned = Intent(intent)
            unpinned.`package` = null
            otherHandlerInstalled = hasInstalledSpecializedHandler(unpinned)
        }
        // The fallback URL can only be honoured when we have a tab to load
        // it into.
        val fallbackUrl = if (tab != null)
            intent.getStringExtra(EXTRA_BROWSER_FALLBACK_URL) else null

        when (ExternalAppLaunchPolicy.decide(
            pinnedResolvable,
            otherHandlerInstalled, fallbackUrl, intent.`package`
        )) {
            ExternalAppLaunchPolicy.Action.LAUNCH -> {
                if (!pinnedResolvable) {
                    // Let the other installed handler claim the link.
                    intent = unpinned!!
                }
            }
            ExternalAppLaunchPolicy.Action.LOAD_FALLBACK -> {
                tab!!.loadUrl(fallbackUrl!!)
                return true
            }
            ExternalAppLaunchPolicy.Action.OPEN_MARKET -> {
                val market = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("market://search?q=pname:" + intent.`package`)
                )
                market.addCategory(Intent.CATEGORY_BROWSABLE)
                try {
                    activity.startActivity(market)
                } catch (ignored: Exception) {
                    // No store app on this device either — stay in browser.
                    return false
                }
                return true
            }
            ExternalAppLaunchPolicy.Action.STAY_IN_BROWSER -> return false
        }
        if (tab != null) {
            intent.putExtra(INTENT_ORIGIN, tab.hashCode())
        }

        if (ArktosCore.acceptedUriScheme(url) && !isSpecializedHandlerAvailable(intent)) {
            return false
        }
        try {
            if (activity.startActivityIfNeeded(intent, -1)) {
                return true
            }
        } catch (exception: Exception) {
            exception.printStackTrace()
            // TODO: 6/5/17 fix case where this could throw a FileUriExposedException due to file:// urls
        }
        return false
    }

    /**
     * Whether an installed app — other than the package the link pinned —
     * specifically handles this intent's target. For web URLs that means a
     * filter whose data authorities cover the URL's host: a generic browser
     * declaring bare http/https schemes doesn't count, which also stops the
     * link looping back into this browser. For non-web targets any matched
     * handler counts.
     */
    private fun hasInstalledSpecializedHandler(intent: Intent): Boolean {
        val handlers = activity.packageManager
            .queryIntentActivities(intent, PackageManager.GET_RESOLVED_FILTER)
        if (handlers.isEmpty()) {
            return false
        }
        val data = intent.data
            // Action-only intent — browsers can't match those, so anything
            // that resolved is a real handler.
            ?: return true
        val scheme = data.scheme
        val webScheme = "http".equals(scheme, ignoreCase = true)
                || "https".equals(scheme, ignoreCase = true)
        for (resolveInfo in handlers) {
            val filter = resolveInfo.filter ?: continue
            if (webScheme) {
                val authorities = ArrayList<String>(filter.countDataAuthorities())
                for (i in 0 until filter.countDataAuthorities()) {
                    val entry = filter.getDataAuthority(i)
                    if (entry != null && entry.host != null) {
                        authorities.add(entry.host)
                    }
                }
                if (ExternalAppLaunchPolicy.authorityMatchesHost(
                        authorities,
                        data.host
                    )
                ) {
                    return true
                }
            } else if (filter.countDataAuthorities() > 0
                || (filter.countDataSchemes() > 0
                        && !filter.hasDataScheme("http")
                        && !filter.hasDataScheme("https"))
            ) {
                return true
            }
        }
        return false
    }

    /**
     * Search for intent handlers that are specific to this URL aka, specialized
     * apps like google maps or youtube
     */
    private fun isSpecializedHandlerAvailable(intent: Intent): Boolean {
        val pm = activity.packageManager
        val handlers = pm.queryIntentActivities(
            intent,
            PackageManager.GET_RESOLVED_FILTER
        )
        if (handlers.isEmpty()) {
            return false
        }
        for (resolveInfo in handlers) {
            val filter = resolveInfo.filter
                // No intent filter matches this intent?
                // Error on the side of staying in the browser, ignore
                ?: continue
            // NOTICE: Use of && instead of || will cause the browser
            // to launch a new intent for every URL, using OR only
            // launches a new one if there is a non-browser app that
            // can handle it.
            // Previously we checked the number of data paths, but it is unnecessary
            // filter.countDataAuthorities() == 0 || filter.countDataPaths() == 0
            if (filter.countDataAuthorities() == 0) {
                // Generic handler, skip
                continue
            }
            return true
        }
        return false
    }

    /**
     * Shares a URL to the system.
     *
     * @param url   the URL to share. If the URL is null
     *              or a special URL, no sharing will occur.
     * @param title the title of the URL to share. This
     *              is optional.
     */
    fun shareUrl(url: String?, title: String?) {
        if (url != null && !url.isSpecialUrl()) {
            val shareIntent = Intent(Intent.ACTION_SEND)
            shareIntent.type = "text/plain"
            if (title != null) {
                shareIntent.putExtra(Intent.EXTRA_SUBJECT, title)
            }
            shareIntent.putExtra(Intent.EXTRA_TEXT, url)
            activity.startActivity(
                Intent.createChooser(
                    shareIntent,
                    activity.getString(R.string.dialog_title_share)
                )
            )
        }
    }

    companion object {
        // Extra placed by sites in intent:// URIs naming a page to load when
        // the pinned app is not installed (S.browser_fallback_url=...).
        private const val EXTRA_BROWSER_FALLBACK_URL = "browser_fallback_url"
    }
}
