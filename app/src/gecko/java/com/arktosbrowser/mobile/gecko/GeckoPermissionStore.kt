// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.gecko

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.arktosbrowser.mobile.engine.SitePermissionGrant

/**
 * The persisted per-origin permission decisions for regular (non-OTR)
 * sessions — SharedPreferences as a simple origin|permission → VALUE_*
 * map, owned by the engine so [GeckoPermissionDelegate] and
 * [GeckoProfile]'s SitePermissions clear/enumeration share one store.
 *
 * Keys: `media|<origin>` for getUserMedia decisions and
 * `<origin>|<permission>` for content permissions (origins never
 * contain '|').
 */
internal class GeckoPermissionStore(application: Application) {

    private val prefs: SharedPreferences =
        application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(key: String): Int? =
        prefs.getInt(key, ABSENT).takeIf { it != ABSENT }

    fun put(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun grants(): List<SitePermissionGrant> = prefs.all.keys.mapNotNull { key ->
        val first = key.substringBefore('|')
        val rest = key.substringAfter('|', "")
        if (rest.isEmpty()) {
            null
        } else if (first == "media") {
            SitePermissionGrant(rest, "media")
        } else {
            SitePermissionGrant(first, rest)
        }
    }

    fun remove(grant: SitePermissionGrant) {
        val key = if (grant.permission == "media") {
            "media|${grant.origin}"
        } else {
            "${grant.origin}|${grant.permission}"
        }
        prefs.edit().remove(key).apply()
    }

    private companion object {
        private const val PREFS_NAME = "gecko_site_permissions"
        private const val ABSENT = Int.MIN_VALUE
    }
}
