// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.network

/**
 * SEC17 — "do not track" request signals
 * ([com.arktosbrowser.mobile.preference.UserPreferences.doNotTrackEnabled]).
 *
 * Emits the legacy `DNT: 1` header alongside its successor
 * `Sec-GPC: 1` (Global Privacy Control — the legally-backed opt-out
 * signal DNT never became). Both ride the same toggle: a user asking
 * not to be tracked wants every signal the site might honor.
 *
 * Coverage caveat: WebView only attaches custom headers to
 * `WebView.loadUrl(url, headers)` calls, i.e. top-level navigations
 * the app initiates. Subresources, XHR/fetch and POST bodies never
 * carry them — there is no WebView API to rewrite outgoing request
 * headers. The gecko flavor closes that gap with the
 * `privacy.donottrackheader.enabled` /
 * `privacy.globalprivacycontrol.enabled` prefs, which GeckoView
 * applies to *every* request (see `gecko/GeckoEngine.kt`).
 */
object DoNotTrackPolicy {

    const val HEADER_DNT = "DNT"
    const val HEADER_GPC = "Sec-GPC"

    /**
     * The headers a top-level navigation should carry when the user
     * asked not to be tracked, or none when the toggle is off.
     */
    fun headers(enabled: Boolean): Map<String, String> =
        if (enabled) {
            linkedMapOf(HEADER_DNT to "1", HEADER_GPC to "1")
        } else {
            emptyMap()
        }

    /**
     * Applies [headers] to the [target] header map — entries are set
     * when the toggle is on and removed when it is off, so toggling
     * the preference never leaves a stale signal behind.
     */
    fun applyTo(target: MutableMap<String, String>, enabled: Boolean) {
        if (enabled) {
            target[HEADER_DNT] = "1"
            target[HEADER_GPC] = "1"
        } else {
            target.remove(HEADER_DNT)
            target.remove(HEADER_GPC)
        }
    }
}
