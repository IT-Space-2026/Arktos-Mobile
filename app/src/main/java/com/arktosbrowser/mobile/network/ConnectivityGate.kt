// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.network

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.arktosbrowser.mobile.utils.ERROR_PAGE_ORIGIN
import dagger.Reusable
import javax.inject.Inject

/**
 * Decides when a load should get the dedicated "no internet connection"
 * error page instead of the generic network-error page (upstream #323).
 *
 * Two seams: [isConnected] is a best-effort synchronous probe used ahead
 * of load (main-frame interception) and when a load error arrives; the
 * companion holds the pure decision logic — which URLs may be
 * short-circuited to the offline page and which failures count as
 * connectivity loss — so it is unit-testable without a device.
 */
@Reusable
class ConnectivityGate @Inject constructor(
    private val connectivityManager: ConnectivityManager
) {

    /**
     * Best-effort: does the device currently have a network route to the
     * internet? Uses `activeNetwork`/`NetworkCapabilities` on API 23+ and
     * the deprecated `activeNetworkInfo` below it (minSdk 21). A captive
     * portal or an interface that vanished mid-check simply reports the
     * last known state — the [isOfflineError] description check covers
     * that race.
     */
    fun isConnected(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            connectivityManager.activeNetwork
                ?.let(connectivityManager::getNetworkCapabilities)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } else {
            @Suppress("DEPRECATION")
            connectivityManager.activeNetworkInfo?.isConnected == true
        }

    companion object {

        /**
         * True when [url] is a real page load that needs the network —
         * the only requests that may be short-circuited to the offline
         * page. Generated pages (`data:` commits and the
         * [ERROR_PAGE_ORIGIN] base), bundled `file:` assets and `about:`
         * URLs must always load, connected or not.
         */
        fun isNetworkPageRequest(url: String): Boolean =
            (url.startsWith("http://") || url.startsWith("https://"))
                && !url.startsWith(ERROR_PAGE_ORIGIN)

        /**
         * Chromium error descriptions that mean the local link went away.
         * Mapped to the offline page even when the connectivity probe
         * still reports a route — e.g. the interface dropped mid-load, or
         * a metered/roaming link lost its route.
         */
        private val OFFLINE_DESCRIPTIONS = setOf(
            "net::ERR_INTERNET_DISCONNECTED",
            "net::ERR_NETWORK_CHANGED",
            "net::ERR_NETWORK_IO_SUSPENDED"
        )

        /**
         * True when a failed load should be reported as "no internet
         * connection" rather than the generic error: the device reports
         * no route at all, or WebView surfaced an explicit
         * connectivity-loss description. Server-side failures on a
         * connected device (name resolution, refused connections) keep
         * the generic page — they are not a missing link.
         */
        fun isOfflineError(connected: Boolean, description: String?): Boolean =
            !connected || OFFLINE_DESCRIPTIONS.any { description?.contains(it) == true }
    }
}
