// SPDX-License-Identifier: MPL-2.0
/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.arktosbrowser.mobile.gecko

import android.content.Context
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/**
 * A [GeckoView] that keeps its session alive across window detaches.
 *
 * A stock GeckoView *closes* the bound session inside
 * `onDetachedFromWindow`. The shell detaches the shared view every time
 * the user switches to a WebView-backed page (homepage, changelog,
 * bookmarks…), calls `removeTabView()`, or reparents it into the
 * fullscreen container — each one silently killed the session, and the
 * next bind painted a dead surface (the black/frozen tab). Releasing
 * the session before the detach lands keeps it alive; it re-binds on
 * the next attach via [pendingSession].
 *
 * mozilla-components' `NestedGeckoView` inside `GeckoEngineView` does
 * exactly this for the same reason.
 */
class ArktosGeckoView(context: Context) : GeckoView(context) {

    /**
     * The session that should be bound whenever this view is attached —
     * lets the binding survive a detach/attach cycle (fullscreen
     * reparent, tab-view swap) without the engine re-acquiring.
     */
    internal var pendingSession: GeckoSession? = null

    override fun onDetachedFromWindow() {
        // Release *before* the detach lands — otherwise GeckoView
        // closes the session automatically.
        releaseSession()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (session == null) {
            pendingSession?.let { setSession(it) }
        }
    }
}
