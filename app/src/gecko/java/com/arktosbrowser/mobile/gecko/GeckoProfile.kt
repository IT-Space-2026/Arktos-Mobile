// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.gecko

import android.net.Uri
import com.arktosbrowser.mobile.engine.Profile
import com.arktosbrowser.mobile.engine.RequestPolicy
import com.arktosbrowser.mobile.engine.StorageArea
import com.arktosbrowser.mobile.engine.UserScript
import org.mozilla.geckoview.StorageController

/**
 * [Profile] over a GeckoView storage partition (ENG03 skeleton).
 *
 * GeckoView partitions storage by session `contextId` — [storageName]
 * becomes the contextId applied to each session created from this
 * profile ("default" maps to Gecko's default partition). Incognito
 * pages get `usePrivateMode` instead, which keeps nothing on disk.
 *
 * Gaps (documented for ENG05): GeckoView has no imperative per-request
 * cookie surface — [acceptsCookies]/[getCookie]/[setCookie] are
 * honored at the runtime-settings level only and get/set return
 * defaults; [requestPolicy] is consulted by [GeckoPage]'s navigation
 * delegate.
 */
class GeckoProfile(
    internal val engine: GeckoEngine,
    override val storageName: String,
    override val isOffTheRecord: Boolean,
    acceptsCookiesInitial: Boolean = true
) : Profile {

    override var userAgent: String = ""

    override var requestPolicy: RequestPolicy? = null

    private val profileScripts = mutableListOf<UserScript>()
    private val attributes = mutableMapOf<String, Any?>()

    /** Session `contextId` to use — null for the default partition. */
    internal fun contextId(): String? = storageName.takeIf { it != "default" }

    internal fun attribute(name: String): Any? = attributes[name]

    override fun insertScript(script: UserScript) {
        profileScripts.removeAll { it.name == script.name }
        profileScripts += script
    }

    override fun removeScript(name: String) {
        profileScripts.removeAll { it.name == name }
    }

    override fun scripts(): List<UserScript> = profileScripts.toList()

    /**
     * Wipe [areas] through `StorageController.clearData`. `SessionOnly`,
     * `FormData`, `SslDecisions` have no Gecko equivalent and are ignored.
     */
    override fun clear(areas: Set<StorageArea>) {
        var flags = 0L
        for (area in areas) {
            flags = flags or when (area) {
                StorageArea.All -> StorageController.ClearFlags.ALL
                StorageArea.Cookies -> StorageController.ClearFlags.COOKIES
                StorageArea.DiskCache -> StorageController.ClearFlags.ALL_CACHES
                StorageArea.LocalStorage, StorageArea.IndexedDb ->
                    StorageController.ClearFlags.DOM_STORAGES
                StorageArea.HttpAuth -> StorageController.ClearFlags.AUTH_SESSIONS
                StorageArea.AppPrivateData -> StorageController.ClearFlags.ALL
                StorageArea.FormData, StorageArea.SslDecisions,
                StorageArea.SessionOnly -> 0L
            }
        }
        if (flags != 0L) {
            engine.runtime.storageController.clearData(flags)
        }
    }

    /** Wipe one origin's site data (cookies + DOM storage for the host). */
    override fun clearSiteData(url: String) {
        val host = Uri.parse(url).host ?: return
        engine.runtime.storageController.clearDataFromHost(
            host, StorageController.ClearFlags.SITE_DATA
        )
    }

    // ---- cookie surface -----------------------------------------------
    // GeckoView has no CookieManager-style get/set API — get/set stay
    // stubs (documented gap: downloads can't attach a Cookie header).
    // `acceptsCookies` IS honored: it resolves through
    // `GeckoEngine.syncCookieBehavior` into the runtime's
    // `contentBlocking.cookieBehavior`(…PrivateMode), folded with the
    // third-party-cookie pref (ENG05).

    // Seeded from `userPreferences.cookiesEnabled` by the engine's
    // profile factories — the setter would run the behavior sync
    // reentrantly inside `defaultProfile`'s lazy initializer.
    override var acceptsCookies: Boolean = acceptsCookiesInitial
        set(value) {
            field = value
            engine.syncCookieBehavior()
        }

    override fun getCookie(url: String): String? = null

    override fun setCookie(url: String, value: String) = Unit

    override fun flushCookies() = Unit

    override fun setProfileAttribute(name: String, value: Any?) {
        attributes[name] = value
    }
}
