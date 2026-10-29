// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.sync

import com.arktosbrowser.mobile.rust.ArktosCore
import com.arktosbrowser.mobile.uniffi.ArktosStore
import com.arktosbrowser.mobile.uniffi.StoreBookmark
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/**
 * MSYNC05 — the Kotlin-side store adapter for the ported sync
 * engine. Pull-based: drains the op-log's unapplied ops per store,
 * writes them into [ArktosStore]/prefs, then marks each applied so
 * they don't re-deliver. Also appends local changes so they ship
 * out on the next transport drain.
 *
 * Op body convention (mirrors the desktop's adapters):
 *   {"add":     {"url","title","folder","position"}}
 *   {"remove":  {"url"}}
 *   {"edit":    {"old_url","url","title","folder","position"}}
 *   {"folder":  {"add":name} | {"remove":name} | {"rename":[old,new]}}
 *   {"visit":   {"url","title"}}
 *   {"set":     {"key","value"}}          — settings scope
 */
@Singleton
class SyncRepository @Inject constructor(
    private val store: ArktosStore
) {
    private companion object {
        @Volatile
        private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null
    }


    /**
     * Drain all unapplied ops for [storeName], applying each into the
     * shared store and marking applied. Returns ops applied.
     */
    /**
     * One-shot bring-up: installs this shared store into the Rust
     * shims and registers every adapter's apply lane. Called once
     * at app start on the gecko flavor.
     */
    fun install(dataRoot: String, context: android.content.Context? = null) {
        runCatching { ArktosCore.syncInstall(dataRoot, store) }
        // The transport stays lazy — Arti + LAN listeners only run
        // once this device has members to reach (or the user opens
        // the pairing flow, which calls ensureTransport itself).
        if (hasMembers(dataRoot)) ensureTransport(dataRoot, context)
    }

    private fun hasMembers(dataRoot: String): Boolean = try {
        org.json.JSONArray(ArktosCore.syncMembers(dataRoot)).length() > 0
    } catch (e: Exception) { false }

    /**
     * Android filters inbound multicast unless the app holds a
     * WifiManager.MulticastLock — without it the LAN leg's mDNS
     * socket hears nothing.  Held for process lifetime once the
     * transport is up; reference-counted so repeated calls are cheap.
     */
    private fun acquireMulticastLock(context: android.content.Context?) {
        if (context == null) return
        runCatching {
            if (multicastLock == null) {
                multicastLock = (context.applicationContext
                    .getSystemService(android.content.Context.WIFI_SERVICE)
                        as? android.net.wifi.WifiManager)
                    ?.createMulticastLock("arktos-sync")
                    ?.apply { setReferenceCounted(true) }
            }
            multicastLock?.takeIf { !it.isHeld }?.acquire()
        }
    }

    /**
     * Bring the transport up with the richest backend the build
     * carries: "auto" (LAN + Tor) on release gecko, "lan" on debug
     * (no Arti), "mem" as the loopback floor.  Idempotent — a
     * running transport is reused, never restarted.  Holds a wifi
     * MulticastLock so Android actually delivers mDNS packets to
     * the discovery socket.
     */
    fun ensureTransport(dataRoot: String, context: android.content.Context? = null) {
        runCatching {
            if (ArktosCore.syncTransportRunning(dataRoot)) return
            acquireMulticastLock(context)
            val name = android.os.Build.MODEL.ifBlank { "Arktos" }
            for (backend in listOf("auto", "lan", "mem")) {
                if (ArktosCore.syncTransportStart(dataRoot, backend, name)) break
            }
        }
    }

    fun drainPending(dataRoot: String, storeName: String): Int {
        return try {
            val arr = org.json.JSONArray(ArktosCore.syncPendingApply(dataRoot, storeName))
            var applied = 0
            for (i in 0 until arr.length()) {
                val op = arr.getJSONObject(i)
                val body = op.optJSONObject("body") ?: continue
                if (applyBody(storeName, op.optString("scope", ""), body)) {
                    ArktosCore.syncMarkApplied(dataRoot, op.optString("op_id"))
                    applied++
                }
            }
            applied
        } catch (e: Exception) {
            0
        }
    }

    /** Append a local change to a synced store so it ships out. */
    fun append(dataRoot: String, storeName: String, bodyJson: String): String =
        ArktosCore.syncAppend(dataRoot, storeName, "", bodyJson)

    /** Export ops newer than [sinceSeq] for the transport shipper. */
    fun export(dataRoot: String, sinceSeq: Long): String =
        ArktosCore.syncExport(dataRoot, null, sinceSeq)

    /** Import peer ops — dedups via the op-log. */
    fun import(dataRoot: String, opsJson: String): String =
        ArktosCore.syncImport(dataRoot, opsJson)

    /** Engine stats for the Synchronization screen. */
    fun stats(dataRoot: String): String = ArktosCore.syncStats(dataRoot)

    /**
     * Merge the group's decrypted login image into the app's vault.
     * The Rust applier materializes peer ops into the scope's
     * autofill vault at import time; this reflects them into the
     * store gecko actually autofills from.  Synced rows carry the
     * [LoginVault.SYNC_REALM] marker so the delete sweep only prunes
     * what sync itself imported.
     */
    fun mergePasswords(dataRoot: String, vault: com.arktosbrowser.mobile.passwords.LoginVault) {
        runCatching {
            val doc = JSONObject(ArktosCore.syncPasswordsList(dataRoot, "default"))
            val arr = doc.optJSONArray("logins") ?: return@runCatching
            val synced = buildList {
                for (i in 0 until arr.length()) {
                    val l = arr.getJSONObject(i)
                    val els = l.optJSONObject("elements") ?: continue
                    val username = pickElement(els, "user", "login", "email")
                    val password = pickElement(els, "pass", "pwd", "secret")
                    val url = l.optString("url", "")
                    if (url.isNotEmpty() && !username.isNullOrEmpty() && !password.isNullOrEmpty()) {
                        add(
                            com.arktosbrowser.mobile.passwords.StoredLogin(
                                guid = null,
                                origin = url,
                                formActionOrigin = null,
                                httpRealm = com.arktosbrowser.mobile.passwords.LoginVault.SYNC_REALM,
                                username = username,
                                password = password
                            )
                        )
                    }
                }
            }
            vault.applySynced(synced)
        }
    }

    /**
     * Reflect the group's merged settings image into the app's
     * SharedPreferences — the apply half of the settings lane.
     * Non-excluded keys land typed (bool/int/dbl/str/list);
     * [SyncBridge.applySetting] suppresses the emit echo.
     */
    fun mergeSettings(dataRoot: String, prefs: android.content.SharedPreferences) {
        runCatching {
            val doc = JSONObject(ArktosCore.syncSettingsList(dataRoot, "default"))
            val settings = doc.optJSONObject("settings") ?: return@runCatching
            val keys = settings.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = settings.optJSONObject(k) ?: continue
                val parsed: Any? = when {
                    v.has("bool") -> v.getBoolean("bool")
                    v.has("int") -> v.getLong("int")
                    v.has("dbl") -> v.getDouble("dbl")
                    v.has("str") -> v.getString("str")
                    v.has("list") -> {
                        val a = v.getJSONArray("list")
                        (0 until a.length()).map { a.getString(it) }.toSet()
                    }
                    else -> continue
                }
                SyncBridge.applySetting(prefs, k, parsed)
            }
        }
    }

    /** First element whose field name contains one of the tokens —
     *  desktop records carry real HTML field names, ours use the
     *  canonical "username"/"password" keys. */
    private fun pickElement(els: JSONObject, vararg tokens: String): String? {
        for (token in tokens) {
            val it = els.keys()
            while (it.hasNext()) {
                val k = it.next()
                if (k.contains(token, ignoreCase = true)) {
                    return els.optString(k).takeIf { v -> v.isNotEmpty() }
                }
            }
        }
        return null
    }

    // ── Op-body application ──────────────────────────────────────────

    private fun applyBody(storeName: String, scope: String, body: JSONObject): Boolean {
        return try {
            when (storeName) {
                "bookmarks" -> applyBookmark(body)
                "history" -> applyHistory(body)
                else -> true // settings/tabs/passwords: staged adapters, no-op for now
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun applyBookmark(body: JSONObject): Boolean {
        return when {
            body.has("add") -> {
                val a = body.getJSONObject("add")
                val folder = a.optString("folder", "")
                val pos = a.optInt("position", 0)
                store.addBookmarkIfNotExists(
                    StoreBookmark(
                        url = a.getString("url"),
                        title = a.optString("title", ""),
                        position = pos.toUInt(),
                        folder = folder,
                        createdAt = a.optLong("created_at", 0).toULong()
                    )
                )
                true
            }
            body.has("remove") ->
                store.deleteBookmark(body.getJSONObject("remove").getString("url"))
            body.has("edit") -> {
                val e = body.getJSONObject("edit")
                store.editBookmark(
                    e.getString("old_url"),
                    StoreBookmark(
                        url = e.getString("url"),
                        title = e.optString("title", ""),
                        position = e.optInt("position", 0).toUInt(),
                        folder = e.optString("folder", ""),
                        createdAt = e.optLong("created_at", 0).toULong()
                    )
                )
                true
            }
            body.has("folder") -> {
                val f = body.getJSONObject("folder")
                when {
                    f.has("add") -> { store.addFolder(f.getString("add")); true }
                    f.has("remove") -> { store.deleteFolder(f.getString("remove")); true }
                    f.has("rename") -> {
                        val r = f.getJSONArray("rename")
                        store.renameFolder(r.getString(0), r.getString(1)); true
                    }
                    else -> true
                }
            }
            else -> true
        }
    }

    private fun applyHistory(body: JSONObject): Boolean {
        return when {
            body.has("visit") -> {
                val v = body.getJSONObject("visit")
                store.visitHistoryEntry(v.getString("url"), v.optString("title").ifEmpty { null })
                true
            }
            body.has("remove") -> {
                store.deleteHistoryEntry(body.getJSONObject("remove").getString("url")); true
            }
            body.has("clear") -> { store.deleteHistory(); true }
            else -> true
        }
    }
}
