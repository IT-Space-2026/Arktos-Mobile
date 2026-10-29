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
        runCatching { ArktosCore.syncAppend(dataRoot, storeName, "", bodyJson) }
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
}
