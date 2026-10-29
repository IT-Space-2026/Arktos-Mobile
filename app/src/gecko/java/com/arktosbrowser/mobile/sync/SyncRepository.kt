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

    /**
     * Drain all unapplied ops for [storeName], applying each into the
     * shared store and marking applied. Returns ops applied.
     */
    /**
     * One-shot bring-up: installs this shared store into the Rust
     * shims and registers every adapter's apply lane. Called once
     * at app start on the gecko flavor.
     */
    fun install(dataRoot: String) {
        runCatching { ArktosCore.syncInstall(dataRoot, store) }
        // Bring the transport up too — "auto" runs the LAN leg plus
        // the Tor leg, each degrading honestly on its own failure.
        runCatching {
            ArktosCore.syncTransportStart(dataRoot, "auto",
                android.os.Build.MODEL.ifBlank { "Arktos" })
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
