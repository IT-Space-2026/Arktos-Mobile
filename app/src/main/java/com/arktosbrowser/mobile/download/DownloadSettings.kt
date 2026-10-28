// SPDX-License-Identifier: MPL-2.0
package com.arktosbrowser.mobile.download

import android.content.Context

/**
 * Shared preferences for the Downloads screen — mirrors the
 * Iceraven "Download Settings" page:
 *  - [deletePolicy]: what "Delete" does to a row
 *      device  -> remove the record AND the file
 *      history -> remove the record only (file stays)
 *      ask     -> confirm every time
 *  - [externalManager]: open the system download-manager UI
 *    instead of the in-app screen.
 */
object DownloadSettings {

    private const val PREFS = "download_settings"
    private const val KEY_DELETE_POLICY = "delete_policy"
    private const val KEY_EXTERNAL_MANAGER = "external_manager"

    const val POLICY_DEVICE = "device"
    const val POLICY_HISTORY = "history"
    const val POLICY_ASK = "ask"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun deletePolicy(context: Context): String =
        prefs(context).getString(KEY_DELETE_POLICY, POLICY_ASK) ?: POLICY_ASK

    fun setDeletePolicy(context: Context, policy: String) {
        prefs(context).edit().putString(KEY_DELETE_POLICY, policy).apply()
    }

    fun externalManager(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EXTERNAL_MANAGER, false)

    fun setExternalManager(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_EXTERNAL_MANAGER, enabled).apply()
    }
}
