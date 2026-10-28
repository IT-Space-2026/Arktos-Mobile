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
}
