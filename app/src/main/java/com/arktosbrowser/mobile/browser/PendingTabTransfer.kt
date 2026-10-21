// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.browser

import android.net.Uri

/**
 * SEC09 — in-memory handoff for the internal Settings → Browser
 * import/export-tabs requests.
 *
 * These payloads used to ride the launch intent's extras, but
 * `MainActivity` is exported: any app could forge
 * `EXTRA_EXPORT_TABS_URI` (leaking the open-tab list to an attacker
 * content-provider URI) or `EXTRA_IMPORT_TABS` (opening tabs on
 * scheme-unfiltered URLs). Requests now live in process memory —
 * unreachable to other apps — and are drained once by the browser
 * activity, on both the cold-start and `onNewIntent` paths.
 */
object PendingTabTransfer {

    sealed interface Request {
        /** Open [urls] in new tabs (Settings → Import tabs). */
        data class Import(val urls: List<String>) : Request

        /** Write the open tab list to [uri] (Settings → Export tabs). */
        data class Export(val uri: Uri) : Request
    }

    @Volatile
    private var pending: Request? = null

    /** Queues [request]; an un-consumed earlier request is replaced. */
    @Synchronized
    fun post(request: Request) {
        pending = request
    }

    /** Returns and clears the pending request, if any. */
    @Synchronized
    fun drain(): Request? = pending.also { pending = null }
}
