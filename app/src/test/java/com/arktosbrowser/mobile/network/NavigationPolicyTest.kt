// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.network

import com.arktosbrowser.mobile.rust.ArktosCore
import com.arktosbrowser.mobile.search.SafeSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ENG04 — the navigation-gate composition the GeckoView delegates run
 * (`onLoadRequest`/`onSubframeLoadRequest`). Exercised against the real
 * Rust core via `jna.library.path`, the same seam the WebView client
 * gates on; the WebView keeps its own inline copy, so these pin down
 * the *ordering* both sides must share.
 */
class NavigationPolicyTest {

    private fun config(
        safeSearchForced: Boolean = false,
        forceHttps: Boolean = false,
        preferHttps: Boolean = false,
        referrerMode: ReferrerPolicy.Mode = ReferrerPolicy.Mode.DEFAULT,
        blockIntent: Boolean = true,
        incognito: Boolean = false,
        connected: Boolean = true,
        adsAllowedOnPage: Boolean = false
    ) = NavigationPolicyConfig(
        safeSearchForced, forceHttps, preferHttps, referrerMode,
        blockIntent, incognito, connected, adsAllowedOnPage
    )

    private fun nav(
        url: String,
        triggerUrl: String? = "https://source.example/",
        isRedirect: Boolean = false,
        hasUserGesture: Boolean = true
    ) = FrameNavigation(
        url = url, isMainFrame = true, isRedirect = isRedirect,
        hasUserGesture = hasUserGesture, triggerUrl = triggerUrl
    )

    private val noLaunch: (String) -> Boolean = { false }
    private val claimedLaunch: (String) -> Boolean = { true }

    // ---- proceed marker (SEC04) ---------------------------------------

    @Test
    fun `proceed marker is swallowed when no warning is displayed`() {
        val policy = NavigationPolicy()
        val marker = HttpsOnlyGate.proceedUrl("http://a.example/")
        val decision = policy.decideMainFrame(
            nav(marker, triggerUrl = null),
            config(forceHttps = true), noLaunch
        )
        assertEquals(NavigationDecision.Deny, decision)
    }

    @Test
    fun `proceed marker only resolves while its warning is displayed`() {
        val policy = NavigationPolicy()
        // Arm the interstitial for the cleartext URL.
        val warned = policy.decideMainFrame(
            nav("http://a.example/"), config(forceHttps = true), noLaunch
        )
        assertEquals(
            NavigationDecision.Interstitial(
                NavigationDecision.GeneratedPage.HttpsOnly, "http://a.example/"
            ),
            warned
        )
        // The marker of the warning that is up resolves to a real load.
        val marker = HttpsOnlyGate.proceedUrl("http://a.example/")
        val decision = policy.decideMainFrame(
            nav(marker, triggerUrl = null),
            config(forceHttps = true), noLaunch
        )
        assertEquals(NavigationDecision.Redirect("http://a.example/"), decision)
        // Consent granted: the cleartext page itself now proceeds.
        assertEquals(
            NavigationDecision.Proceed,
            policy.decideMainFrame(
                nav("http://a.example/"), config(forceHttps = true), noLaunch
            )
        )
    }

    @Test
    fun `strict https-only warns only on cleartext`() {
        val policy = NavigationPolicy()
        assertEquals(
            NavigationDecision.Interstitial(
                NavigationDecision.GeneratedPage.HttpsOnly, "http://b.example/"
            ),
            policy.decideMainFrame(
                nav("http://b.example/"), config(forceHttps = true), noLaunch
            )
        )
        val https = policy.decideMainFrame(
            nav("https://b.example/"), config(forceHttps = true), noLaunch
        )
        assertEquals(NavigationDecision.Proceed, https)
    }

    // ---- SafeSearch -----------------------------------------------------

    @Test
    fun `forced safesearch redirects to the enforced url`() {
        val policy = NavigationPolicy()
        val decision = policy.decideMainFrame(
            nav("https://www.google.com/search?q=test"),
            config(safeSearchForced = true), noLaunch
        )
        assertTrue(decision is NavigationDecision.Redirect)
        assertEquals(
            SafeSearch.enforcedUrl("https://www.google.com/search?q=test", true),
            (decision as NavigationDecision.Redirect).url
        )
        // And the enforced URL itself proceeds — no rewrite loop.
        assertEquals(
            NavigationDecision.Proceed,
            policy.decideMainFrame(
                nav((decision).url, triggerUrl = null),
                config(safeSearchForced = true), noLaunch
            )
        )
    }

    // ---- prefer HTTPS ---------------------------------------------------

    @Test
    fun `prefer https upgrades once per host then falls through`() {
        val policy = NavigationPolicy()
        val first = policy.decideMainFrame(
            nav("http://c.example/"), config(preferHttps = true), noLaunch
        )
        assertEquals(
            NavigationDecision.Redirect("https://c.example/"), first
        )
        // Second cleartext nav to the same host is let through — the
        // upgrade is once-per-host (no ping-pong).
        assertEquals(
            NavigationDecision.Proceed,
            policy.decideMainFrame(
                nav("http://c.example/"), config(preferHttps = true), noLaunch
            )
        )
        // A different host still upgrades.
        assertTrue(
            policy.decideMainFrame(
                nav("http://d.example/"), config(preferHttps = true), noLaunch
            ) is NavigationDecision.Redirect
        )
    }

    // ---- external handoff ----------------------------------------------

    @Test
    fun `external schemes are claimed by the launcher`() {
        val policy = NavigationPolicy()
        assertEquals(
            NavigationDecision.Deny,
            policy.decideMainFrame(
                nav("mailto:a@b.c"), config(), noLaunch
            )
        )
    }

    @Test
    fun `blockIntent keeps non-external links in the browser`() {
        val policy = NavigationPolicy()
        var launched = false
        val decision = policy.decideMainFrame(
            nav("https://e.example/"), config(blockIntent = true)
        ) {
            launched = true
            false
        }
        assertFalse(launched)
        assertEquals(NavigationDecision.Proceed, decision)
    }

    @Test
    fun `incognito never hands a url to another app`() {
        val policy = NavigationPolicy()
        var launched = false
        val decision = policy.decideMainFrame(
            nav("intent://g.example/#Intent;end"), config(incognito = true)
        ) {
            launched = true
            true
        }
        assertFalse(launched)
        // The scheme isn't engine-loadable either → dropped, not denied-
        // because-of-launch (same outcome as the WebView).
        assertEquals(NavigationDecision.Deny, decision)
    }

    // ---- verdicts -------------------------------------------------------

    @Test
    fun `ads verdict on the main frame shows the block page once`() {
        val listId = "test-navpol-${System.nanoTime()}"
        val domain = "navpol-${System.nanoTime()}.example"
        ArktosCore.configureBlocklist(
            adsEnabled = true, malwareEnabled = false, lanBlockEnabled = false
        )
        try {
            assertEquals(1, ArktosCore.loadSubscriptionHosts(listId, "0.0.0.0 $domain\n"))
            val policy = NavigationPolicy()
            val decision = policy.decideMainFrame(
                nav("https://$domain/x"), config(), noLaunch
            )
            assertEquals(
                NavigationDecision.Interstitial(
                    NavigationDecision.GeneratedPage.BlockedAds, "https://$domain/x"
                ),
                decision
            )
            // The interstitial's proceed button reloads the URL — the
            // retry on the same host is let through (proceed-once).
            assertEquals(
                NavigationDecision.Proceed,
                policy.decideMainFrame(
                    nav("https://$domain/x"), config(), noLaunch
                )
            )
        } finally {
            ArktosCore.removeSubscriptionHosts(listId)
        }
    }

    @Test
    fun `ads verdict on a sub frame denies when the page is not allowlisted`() {
        val listId = "test-navsub-${System.nanoTime()}"
        val domain = "navsub-${System.nanoTime()}.example"
        ArktosCore.configureBlocklist(
            adsEnabled = true, malwareEnabled = false, lanBlockEnabled = false
        )
        try {
            assertEquals(1, ArktosCore.loadSubscriptionHosts(listId, "0.0.0.0 $domain\n"))
            val policy = NavigationPolicy()
            assertEquals(
                NavigationDecision.Deny,
                policy.decideSubFrame("https://$domain/ad.js", "https://page.example", false)
            )
            assertEquals(
                NavigationDecision.Proceed,
                policy.decideSubFrame("https://$domain/ad.js", "https://page.example", true)
            )
        } finally {
            ArktosCore.removeSubscriptionHosts(listId)
        }
    }

    // ---- offline --------------------------------------------------------

    @Test
    fun `offline devices get the offline page for network loads`() {
        val policy = NavigationPolicy()
        val decision = policy.decideMainFrame(
            nav("https://f.example/"), config(connected = false), noLaunch
        )
        assertEquals(
            NavigationDecision.Interstitial(
                NavigationDecision.GeneratedPage.Offline, "https://f.example/"
            ),
            decision
        )
        // Generated/bundled pages still load with no link.
        assertEquals(
            NavigationDecision.Proceed,
            policy.decideMainFrame(
                nav("data:text/html,hi", triggerUrl = null),
                config(connected = false), noLaunch
            )
        )
    }

    // ---- referrer -------------------------------------------------------

    @Test
    fun `strip mode reissues the load with no referrer`() {
        val policy = NavigationPolicy()
        val decision = policy.decideMainFrame(
            nav("https://g.example/"), config(referrerMode = ReferrerPolicy.Mode.STRIP), noLaunch
        )
        assertEquals(
            NavigationDecision.Redirect("https://g.example/", null), decision
        )
    }

    @Test
    fun `same-origin mode keeps the referrer on same-origin navs only`() {
        val policy = NavigationPolicy()
        val same = policy.decideMainFrame(
            nav("https://source.example/path"),
            config(referrerMode = ReferrerPolicy.Mode.SAME_ORIGIN), noLaunch
        )
        assertEquals(
            NavigationDecision.Redirect(
                "https://source.example/path", "https://source.example/"
            ),
            same
        )
        val cross = policy.decideMainFrame(
            nav("https://other.example/"),
            config(referrerMode = ReferrerPolicy.Mode.SAME_ORIGIN), noLaunch
        )
        assertEquals(
            NavigationDecision.Redirect("https://other.example/", null), cross
        )
    }

    @Test
    fun `referrer takeover skips fragment navigations and non-http targets`() {
        val policy = NavigationPolicy()
        val cfg = config(referrerMode = ReferrerPolicy.Mode.STRIP)
        // Same-document fragment nav — the engine scrolls; no takeover.
        val frag = policy.decideMainFrame(
            FrameNavigation(
                url = "https://source.example/#section",
                isMainFrame = true, isRedirect = false,
                hasUserGesture = true, triggerUrl = "https://source.example/"
            ),
            cfg, noLaunch
        )
        assertEquals(NavigationDecision.Proceed, frag)
        // Non-http targets can't carry a referrer — left alone.
        val about = policy.decideMainFrame(
            nav("about:blank", triggerUrl = "https://x.example/"),
            cfg, noLaunch
        )
        assertEquals(NavigationDecision.Proceed, about)
    }

    // ---- scheme sanity ---------------------------------------------------

    @Test
    fun `unhandled schemes are dropped`() {
        val policy = NavigationPolicy()
        assertEquals(
            NavigationDecision.Deny,
            policy.decideMainFrame(
                nav("gibberish://x/y", triggerUrl = null), config(), noLaunch
            )
        )
        // … while real engines' schemes proceed.
        assertEquals(
            NavigationDecision.Proceed,
            policy.decideMainFrame(
                nav("data:text/html,hi", triggerUrl = null), config(), noLaunch
            )
        )
    }
}
