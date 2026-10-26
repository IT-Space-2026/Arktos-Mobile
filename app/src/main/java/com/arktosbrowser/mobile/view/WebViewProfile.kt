// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.view

import android.content.Context
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import android.webkit.CookieSyncManager
import android.webkit.GeolocationPermissions
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import com.arktosbrowser.mobile.browser.IncognitoPartition
import com.arktosbrowser.mobile.engine.Profile
import com.arktosbrowser.mobile.engine.RequestPolicy
import com.arktosbrowser.mobile.engine.SitePermissionGrant
import com.arktosbrowser.mobile.engine.StorageArea
import com.arktosbrowser.mobile.engine.UserScript
import com.arktosbrowser.mobile.passwords.LoginVaults
import com.arktosbrowser.mobile.utils.WebUtils
import java.io.File

/**
 * [Profile] over the process-global WebView storage partition
 * (`CookieManager`, `WebStorage`, `WebViewDatabase`).
 *
 * WebView has exactly one partition per process; the incognito browser
 * lives in the `:incognito` process, so this object is the OTR profile
 * there (its [isOffTheRecord] flag mirrors the process, see
 * [WebViewEngine.initialize]).
 *
 * [userAgent], [requestPolicy], profile scripts and profile attributes
 * are stored for API completeness — the WebView backend applies the user
 * agent per-page via settings and runs its request policy inside
 * `ArktosCookieWebClient`, so nothing reads them back today.
 */
class WebViewProfile(
    private val context: Context,
    override val isOffTheRecord: Boolean,
    override val storageName: String,
    private val dataDirName: String = IncognitoPartition.WEB_VIEW_DEFAULT_DATA_DIR
) : Profile {

    override var userAgent: String = ""

    override var requestPolicy: RequestPolicy? = null

    private val profileScripts = mutableListOf<UserScript>()
    private val attributes = mutableMapOf<String, Any?>()

    override fun insertScript(script: UserScript) {
        profileScripts.removeAll { it.name == script.name }
        profileScripts += script
    }

    override fun removeScript(name: String) {
        profileScripts.removeAll { it.name == name }
    }

    override fun scripts(): List<UserScript> = profileScripts.toList()

    /**
     * Wipe the given [areas] of the partition. `All` expands to every
     * concrete area; `SessionOnly` has no WebView equivalent and is
     * ignored.
     */
    override fun clear(areas: Set<StorageArea>) {
        val expanded = if (StorageArea.All in areas) {
            StorageArea.entries.toSet() - StorageArea.All
        } else {
            areas
        }

        if (StorageArea.Cookies in expanded) {
            CookieManager.getInstance().removeAllCookies(null)
        }
        if (StorageArea.LocalStorage in expanded || StorageArea.IndexedDb in expanded) {
            WebStorage.getInstance().deleteAllData()
        }
        if (StorageArea.FormData in expanded || StorageArea.HttpAuth in expanded) {
            val database = WebViewDatabase.getInstance(context)
            if (StorageArea.FormData in expanded) database.clearFormData()
            if (StorageArea.HttpAuth in expanded) database.clearHttpAuthUsernamePassword()
        }

        // The browser-managed login vault (WebViewLoginBridge) is
        // form data — a FormData clear must empty it too.
        if (StorageArea.FormData in expanded) {
            LoginVaults.get(context).clear()
        }

        // Instance-level clears (disk cache, saved form data, SSL
        // decisions) need a live WebView object; a throwaway instance is
        // enough — these APIs all act on the shared partition.
        if (StorageArea.DiskCache in expanded
            || StorageArea.FormData in expanded
            || StorageArea.SslDecisions in expanded
        ) {
            WebView(context).apply {
                if (StorageArea.DiskCache in expanded) clearCache(true)
                if (StorageArea.FormData in expanded) clearFormData()
                if (StorageArea.SslDecisions in expanded) clearSslPreferences()
                destroy()
            }
        }

        // Remembered site-permission grants — GeolocationPermissions
        // is the only origin-scoped permission store WebView keeps.
        // clearAll() is API 30+, so iterate origins (works on 26+).
        if (StorageArea.SitePermissions in expanded) {
            GeolocationPermissions.getInstance().getOrigins { origins ->
                origins?.forEach {
                    GeolocationPermissions.getInstance().clear(it)
                }
            }
        }

        // The sledgehammer the old eraseWebStorage path used for
        // incognito hardening: delete the partition's data dir.
        // [dataDirName] is this process's own partition — inside the
        // :incognito process it is app_webview_incognito, NOT the main
        // profile's app_webview (INCOG01).
        if (StorageArea.AppPrivateData in expanded
            && Build.VERSION.SDK_INT > Build.VERSION_CODES.N
        ) {
            WebUtils.deleteDir(File(context.dataDir, dataDirName))
        }
    }

    /**
     * WebView only persists geolocation grants — the dialog's
     * `callback.invoke(origin, granted, remember=true)` lands here.
     */
    override fun sitePermissionGrants(
        callback: (List<SitePermissionGrant>) -> Unit
    ) {
        GeolocationPermissions.getInstance().getOrigins { origins ->
            callback(origins.orEmpty().map {
                SitePermissionGrant(it, "geolocation")
            })
        }
    }

    override fun clearSitePermissionGrant(grant: SitePermissionGrant) {
        GeolocationPermissions.getInstance().clear(grant.origin)
    }

    /**
     * Wipes one origin's site data — the per-site scope of
     * [clear]. CookieManager has no per-host delete, so the host's
     * cookie names are re-set with an epoch expiry; DOM storage goes
     * through [WebStorage.deleteOrigin].
     */
    override fun clearSiteData(url: String) {
        val uri = Uri.parse(url)
        val host = uri.host ?: return
        val origin = "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"

        val cookieManager = CookieManager.getInstance()
        val cookies = cookieManager.getCookie(url).orEmpty()
        for (pair in cookies.split(";")) {
            val name = pair.substringBefore('=').trim()
            if (name.isNotEmpty()) {
                cookieManager.setCookie(origin,
                    "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Domain=$host")
            }
        }
        cookieManager.flush()

        WebStorage.getInstance().deleteOrigin(origin)
    }

    override var acceptsCookies: Boolean
        get() = CookieManager.getInstance().acceptCookie()
        set(value) {
            // Pre-Lollipop needs the legacy sync manager created before
            // the cookie manager is touched (same as the old
            // updateCookiePreference call sites).
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                @Suppress("DEPRECATION")
                CookieSyncManager.createInstance(context)
            }
            CookieManager.getInstance().setAcceptCookie(value)
        }

    override fun getCookie(url: String): String? =
        CookieManager.getInstance().getCookie(url)

    override fun setCookie(url: String, value: String) =
        CookieManager.getInstance().setCookie(url, value)

    override fun flushCookies() = CookieManager.getInstance().flush()

    override fun setProfileAttribute(name: String, value: Any?) {
        attributes[name] = value
    }
}
