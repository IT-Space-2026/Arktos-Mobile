package com.arktosbrowser.mobile.network

import com.arktosbrowser.mobile.rust.ArktosCore
import com.arktosbrowser.mobile.utils.ERROR_PAGE_ORIGIN
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards upstream issue #323: an offline device should show the dedicated
 * "no internet connection" page instead of the generic network error.
 *
 * The device probe (`isConnected`) needs a real ConnectivityManager, so
 * the tests exercise the pure decision seams: which requests may be
 * short-circuited ahead of load, and which failures are connectivity
 * loss. No Robolectric in this project — [isConnected]'s one-line
 * platform calls stay untested by design.
 */
class ConnectivityGateTest {

    @Test
    fun `http and https page loads are network page requests`() {
        assertTrue(ConnectivityGate.isNetworkPageRequest("http://example.com/"))
        assertTrue(ConnectivityGate.isNetworkPageRequest("https://example.com/a?b=c"))
    }

    @Test
    fun `generated and bundled pages are never offline-intercepted`() {
        // Generated pages, bundled assets and pseudo-URLs must keep
        // loading with no link — otherwise the offline page itself (or
        // the start page) could never render.
        assertFalse(ConnectivityGate.isNetworkPageRequest("data:text/html;base64,AAAA"))
        assertFalse(ConnectivityGate.isNetworkPageRequest("file:///android_asset/homepage.html"))
        assertFalse(ConnectivityGate.isNetworkPageRequest("about:blank"))
        assertFalse(ConnectivityGate.isNetworkPageRequest("javascript:alert(1)"))
        assertFalse(ConnectivityGate.isNetworkPageRequest("content://x/y"))
        // The error pages' stable baseUrl is https — must not loop.
        assertFalse(ConnectivityGate.isNetworkPageRequest(ERROR_PAGE_ORIGIN))
        assertFalse(ConnectivityGate.isNetworkPageRequest("$ERROR_PAGE_ORIGIN/path"))
    }

    @Test
    fun `no connectivity reports offline regardless of description`() {
        assertTrue(ConnectivityGate.isOfflineError(false, null))
        assertTrue(ConnectivityGate.isOfflineError(false, "net::ERR_NAME_NOT_RESOLVED"))
        assertTrue(ConnectivityGate.isOfflineError(false, "net::ERR_CONNECTION_REFUSED"))
    }

    @Test
    fun `connectivity-loss descriptions are offline even when a route remains`() {
        // The probe can race the interface going down mid-load; the
        // Chromium description is the ground truth for these codes.
        assertTrue(ConnectivityGate.isOfflineError(true, "net::ERR_INTERNET_DISCONNECTED"))
        assertTrue(ConnectivityGate.isOfflineError(true, "net::ERR_NETWORK_CHANGED"))
        assertTrue(ConnectivityGate.isOfflineError(true, "net::ERR_NETWORK_IO_SUSPENDED"))
    }

    @Test
    fun `server-side failures on a connected device stay generic`() {
        // A dead domain or refused connection is not a missing link —
        // the generic error text remains more accurate.
        assertFalse(ConnectivityGate.isOfflineError(true, "net::ERR_NAME_NOT_RESOLVED"))
        assertFalse(ConnectivityGate.isOfflineError(true, "net::ERR_CONNECTION_REFUSED"))
        assertFalse(ConnectivityGate.isOfflineError(true, "net::ERR_CONNECTION_TIMED_OUT"))
        assertFalse(ConnectivityGate.isOfflineError(true, "net::ERR_CERT_AUTHORITY_INVALID"))
        assertFalse(ConnectivityGate.isOfflineError(true, null))
    }

    @Test
    fun `offline error page embeds title message and reload hook`() {
        // The same buildErrorPage seam the WebView client uses for the
        // ahead-of-load interception — pins the page variant's contract.
        val html = ArktosCore.buildErrorPage(
            "<style>x</style>",
            "No internet connection",
            "NET::ERR_INTERNET_DISCONNECTED",
            "Check the address or your connection, then try again.",
            "Reload",
            true,
            "window.location.href = 'https://example.com/';"
        )
        assertTrue(html.contains("<h1>No internet connection</h1>"))
        assertTrue(html.contains("NET::ERR_INTERNET_DISCONNECTED"))
        assertTrue(html.contains(">Reload</button>"))
        assertTrue(html.contains("window.location.href = 'https://example.com/';"))
    }
}
