// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.sync

import com.arktosbrowser.mobile.BuildConfig
import com.arktosbrowser.mobile.rust.ArktosCore

/**
 * The one seam shared code uses to log a sync op — a no-op on the
 * WebView/Light flavor, which carries no sync implementation. On the
 * Gecko flavor it appends the op to the engine's op-log so a later
 * transport drain ships it. Keeping the bridge static keeps the
 * shared repositories free of a `SyncRepository` dependency, which
 * only exists in the gecko source set.
 */
object SyncBridge {

    /** Append a local change to a synced store — gecko only. */
    fun append(dataRoot: String, storeName: String, bodyJson: String) {
        if (!BuildConfig.IS_GECKO) return
        runCatching { ArktosCore.syncAppend(dataRoot, storeName, "default", bodyJson) }
    }

    /**
     * A local bookmark mutation — routes through the real adapter's
     * emit path so the op body matches the desktop's wire format.
     */
    fun bookmark(dataRoot: String, op: String, url: String, title: String, folder: String) {
        if (!BuildConfig.IS_GECKO) return
        runCatching { ArktosCore.syncBookmarkLocal(dataRoot, op, url, title, folder) }
    }

    /** A local history mutation — same adapter emit path. */
    fun history(dataRoot: String, op: String, url: String, title: String) {
        if (!BuildConfig.IS_GECKO) return
        runCatching { ArktosCore.syncHistoryLocal(dataRoot, op, url, title) }
    }

    /** Publish the open-tabs presence document — gecko only. */
    fun tabs(dataRoot: String, tabsJson: String) {
        if (!BuildConfig.IS_GECKO) return
        runCatching { ArktosCore.syncTabsLocal(dataRoot, tabsJson) }
    }

    /**
     * A local login mutation — routes through the autofill vault so
     * the passwords adapter emits the sealed op.  Emission is a
     * no-op while the vault is locked/opted-out; the local write
     * still lands.
     */
    fun password(dataRoot: String, op: String, url: String, username: String = "", password: String = "") {
        if (!BuildConfig.IS_GECKO) return
        runCatching {
            when (op) {
                "set" -> ArktosCore.syncPasswordLocal(dataRoot, "default", url, username, password)
                "remove" -> ArktosCore.syncPasswordRemove(dataRoot, "default", url)
                "clear" -> ArktosCore.syncPasswordClear(dataRoot, "default")
            }
        }
    }

    // ── Settings lane ────────────────────────────────────────────────

    private var settingsPrefs: android.content.SharedPreferences? = null
    private var settingsRoot: String? = null

    /** Values the apply side just wrote — the echo guard. */
    private val appliedValues = java.util.concurrent.ConcurrentHashMap<String, Any?>()

    /**
     * Keys that stay device-local — mirrors the desktop adapter's
     * exclusion philosophy (allow-all minus prefixes), with
     * mobile's security and ephemeral keys carved out.  Exact
     * matches so "password" (site-block) can't eat "passwords".
     */
    private val EXCLUDED_EXACT = setOf(
        "password", "usePassword", "passwordLock", "usePasswordLock",
        "downloadLocation", "lastChangelogVersionCode", "lastUpdateCheckAt",
        "dismissedUpdateTag", "lastBookmarkFolder",
        "incognitoNotificationVisible", "settingsSyncedDump"
    )
    private val EXCLUDED_PREFIX = listOf(
        "firstLaunch", "appLock", "useProxy", "sync/"
    )

    fun isSettingSynced(key: String?): Boolean {
        if (key.isNullOrEmpty()) return false
        if (key in EXCLUDED_EXACT) return false
        return EXCLUDED_PREFIX.none { key.startsWith(it) }
    }

    /**
     * Attach the user-preferences store — every non-excluded write
     * funnels a set/del op through the settings adapter, matching
     * the desktop's emit-on-write model.  Call once at app start.
     */
    fun attachSettings(prefs: android.content.SharedPreferences, dataRoot: String) {
        if (!BuildConfig.IS_GECKO || settingsPrefs != null) return
        settingsPrefs = prefs
        settingsRoot = dataRoot
        prefs.registerOnSharedPreferenceChangeListener { _, key ->
            emitSetting(key)
        }
        // One-time first-sync advertise: keys that never changed
        // never reached the ini, so the group would only see
        // deltas — emit whatever the image lacks or disagrees on.
        // Guarded by a marker pref (excluded from sync itself).
        if (!prefs.getBoolean("settingsSyncedDump", false)) {
            prefs.edit().putBoolean("settingsSyncedDump", true).apply()
            Thread {
                runCatching {
                    val doc = org.json.JSONObject(
                        ArktosCore.syncSettingsList(dataRoot, "default"))
                    val ini = doc.optJSONObject("settings")
                    for ((k, v) in prefs.all) {
                        if (v == null || !isSettingSynced(k)) continue
                        val j = settingJson(v).toString()
                        if (ini?.optJSONObject(k)?.toString() != j) {
                            ArktosCore.syncSettingLocal(dataRoot, "default", k, j)
                        }
                    }
                }
            }.start()
        }
    }

    private fun emitSetting(key: String?) {
        val prefs = settingsPrefs ?: return
        val root = settingsRoot ?: return
        if (!isSettingSynced(key) || key == null) return
        val current = prefs.all[key]
        if (appliedValues[key] == current) return // applied value echoing back
        appliedValues.remove(key)
        runCatching {
            if (current == null) {
                ArktosCore.syncSettingRemove(root, "default", key)
            } else {
                ArktosCore.syncSettingLocal(root, "default", key, settingJson(current).toString())
            }
        }
    }

    /** Apply a synced setting locally — preserves the existing
     *  entry's storage type (Int/Float) so delegate readers never
     *  ClassCast, and records the stored form as the echo guard. */
    fun applySetting(prefs: android.content.SharedPreferences, key: String, value: Any?) {
        if (!isSettingSynced(key)) return
        val prev = prefs.all[key]
        val stored: Any? = when (value) {
            null -> null
            is Boolean -> value
            is Number -> when (prev) {
                is Int -> value.toInt()
                is Float -> value.toFloat()
                is Double -> value.toDouble()
                else -> value.toLong()
            }
            is Set<*> -> value.filterIsInstance<String>().toSet()
            else -> value.toString()
        }
        appliedValues[key] = stored
        prefs.edit().apply {
            when (stored) {
                null -> remove(key)
                is Boolean -> putBoolean(key, stored)
                is Int -> putInt(key, stored)
                is Float -> putFloat(key, stored)
                is Double -> putLong(key, stored.toLong())
                is Long -> putLong(key, stored)
                is Set<*> -> putStringSet(key, stored.filterIsInstance<String>().toSet())
                else -> putString(key, stored.toString())
            }
        }.apply()
    }

    /** A local preference write's JSON rendering — the typed
     *  {"bool":..} wire form the adapter shares. */
    private fun settingJson(v: Any): org.json.JSONObject = when (v) {
        is Boolean -> org.json.JSONObject().put("bool", v)
        is Int, is Long, is Short, is Byte ->
            org.json.JSONObject().put("int", (v as Number).toLong())
        is Float, is Double ->
            org.json.JSONObject().put("dbl", (v as Number).toDouble())
        is Set<*> -> org.json.JSONObject().put("list", org.json.JSONArray(v.filterIsInstance<String>()))
        else -> org.json.JSONObject().put("str", v.toString())
    }
}
