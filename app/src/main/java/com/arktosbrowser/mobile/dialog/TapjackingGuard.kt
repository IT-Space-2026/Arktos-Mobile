// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.dialog

import android.app.Dialog
import android.os.Build

/**
 * SEC08 — tapjacking protection for dialogs whose buttons an overlay
 * app could trick the user into tapping (credential entry, proxy
 * config, download confirm, …).
 *
 * `filterTouchesWhenObscured` makes the framework drop touches when the
 * dialog's window is obscured by another visible window — the standard
 * counter to SYSTEM_ALERT_WINDOW tapjacking. Setting it on the decor
 * view covers the whole dialog hierarchy, including the stock
 * AlertDialog buttons. On S+ `setHideOverlayWindows` additionally
 * keeps overlay windows from drawing on top at all.
 *
 * [shouldFilterObscuredTouches] is the pure policy half so the
 * surface→protection mapping stays unit-testable without android.*
 * in the test.
 */
object TapjackingGuard {

    /** Dialogs a malicious overlay would profit from tapping through. */
    enum class Surface {
        /** App-lock unlock + biometric-retry prompts. */
        APP_LOCK,

        /** Parental-controls password gate. */
        PARENTAL_GATE,

        /** Setting a new PIN/password (app lock or parental). */
        CREDENTIAL_SET,

        /** Encrypted-backup password entry. */
        BACKUP_PASSWORD,

        /** Manual proxy host:port entry. */
        PROXY_HOST,

        /** "Download this file?" confirmation. */
        DOWNLOAD_CONFIRM,

        /** Everything else — no filtering. */
        GENERIC
    }

    /**
     * True when [surface] is sensitive enough that touches arriving
     * while another window obscures it must be discarded.
     */
    fun shouldFilterObscuredTouches(surface: Surface): Boolean =
        surface != Surface.GENERIC

    /** Applies the obscured-touch filter to [dialog]'s whole view tree. */
    fun protect(dialog: Dialog, surface: Surface) {
        if (!shouldFilterObscuredTouches(surface)) {
            return
        }
        val window = dialog.window ?: return
        window.decorView.filterTouchesWhenObscured = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                window.setHideOverlayWindows(true)
            } catch (e: SecurityException) {
                // HIDE_OVERLAY_WINDOWS is an appops-gated special
                // permission — OEMs that refuse it must not crash.
            }
        }
    }
}
