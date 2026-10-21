// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.engine

import com.arktosbrowser.mobile.BrowserApp

/**
 * ENG03 — flavor seam: which [Backend] this build installs into
 * [Engines]. The `webview` engine flavor installs the Dagger-provided
 * `WebViewEngine` adapter; the `gecko` flavor's copy of this object
 * constructs `GeckoEngine` instead.
 */
object EngineSelector {

    fun createBackend(app: BrowserApp): Backend = app.webViewEngine

}
