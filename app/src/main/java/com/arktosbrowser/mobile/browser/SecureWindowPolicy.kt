// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.browser

/**
 * SEC07 — decides whether a window gets `FLAG_SECURE` (blocked from
 * screenshots, screen recording, and recents thumbnails).
 *
 * Incognito windows are always secured — private pages should never
 * leak into a screenshot or the task switcher. Regular windows stay
 * screenshot-friendly by default; the Privacy→Security "Secure
 * windows" toggle
 * ([com.arktosbrowser.mobile.preference.UserPreferences.secureWindowsEnabled])
 * opts the whole app in for users who want it everywhere.
 *
 * Kept free of android.* types so the decision can be unit-tested.
 */
object SecureWindowPolicy {

    /**
     * True when [android.view.WindowManager.LayoutParams.FLAG_SECURE]
     * should be set on the window.
     */
    fun shouldSecure(isIncognito: Boolean, secureAllWindows: Boolean): Boolean =
        isIncognito || secureAllWindows

}
