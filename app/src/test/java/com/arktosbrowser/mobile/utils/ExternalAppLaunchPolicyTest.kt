// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.utils

import com.arktosbrowser.mobile.utils.ExternalAppLaunchPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the intent-resolution ordering fixed for upstream issue #316:
 * an installed third-party handler must be preferred over an app-store
 * prompt when the link's pinned package is not installed.
 */
class ExternalAppLaunchPolicyTest {

    @Test
    fun `pinned app installed always wins`() {
        assertEquals(
            Action.LAUNCH,
            ExternalAppLaunchPolicy.decide(
                pinnedResolvable = true,
                installedSpecializedHandler = true,
                fallbackUrl = "https://play.google.com/store/apps/details?id=com.reddit.frontpage",
                pinnedPackage = "com.reddit.frontpage"
            )
        )
    }

    @Test
    fun `installed third party handler preferred over store`() {
        // The issue-316 case: com.reddit.frontpage not installed, but a
        // third-party Reddit client handles the URL.
        assertEquals(
            Action.LAUNCH,
            ExternalAppLaunchPolicy.decide(
                pinnedResolvable = false,
                installedSpecializedHandler = true,
                fallbackUrl = null,
                pinnedPackage = "com.reddit.frontpage"
            )
        )
    }

    @Test
    fun `fallback url preferred over store when nothing installed`() {
        assertEquals(
            Action.LOAD_FALLBACK,
            ExternalAppLaunchPolicy.decide(
                pinnedResolvable = false,
                installedSpecializedHandler = false,
                fallbackUrl = "https://play.google.com/store/apps/details?id=com.reddit.frontpage",
                pinnedPackage = "com.reddit.frontpage"
            )
        )
    }

    @Test
    fun `store opened only when no handler and no usable fallback`() {
        assertEquals(
            Action.OPEN_MARKET,
            ExternalAppLaunchPolicy.decide(
                pinnedResolvable = false,
                installedSpecializedHandler = false,
                fallbackUrl = null,
                pinnedPackage = "com.reddit.frontpage"
            )
        )
        assertEquals(
            Action.OPEN_MARKET,
            ExternalAppLaunchPolicy.decide(
                pinnedResolvable = false,
                installedSpecializedHandler = false,
                fallbackUrl = "javascript:alert(1)",
                pinnedPackage = "com.reddit.frontpage"
            )
        )
    }

    @Test
    fun `stays in browser when nothing can handle the link`() {
        assertEquals(
            Action.STAY_IN_BROWSER,
            ExternalAppLaunchPolicy.decide(
                pinnedResolvable = false,
                installedSpecializedHandler = false,
                fallbackUrl = null,
                pinnedPackage = null
            )
        )
    }

    @Test
    fun `only http and https fallback urls are loadable`() {
        assertTrue(ExternalAppLaunchPolicy.isLoadableFallbackUrl("https://example.com/x"))
        assertTrue(ExternalAppLaunchPolicy.isLoadableFallbackUrl("http://example.com"))
        assertTrue(ExternalAppLaunchPolicy.isLoadableFallbackUrl("HTTPS://EXAMPLE.COM"))
        assertFalse(ExternalAppLaunchPolicy.isLoadableFallbackUrl(null))
        assertFalse(ExternalAppLaunchPolicy.isLoadableFallbackUrl("javascript:alert(1)"))
        assertFalse(ExternalAppLaunchPolicy.isLoadableFallbackUrl("file:///etc/passwd"))
        assertFalse(ExternalAppLaunchPolicy.isLoadableFallbackUrl("market://details?id=x"))
        // not a scheme-prefixed URL at all
        assertFalse(ExternalAppLaunchPolicy.isLoadableFallbackUrl("example.com"))
    }

    @Test
    fun `authority matches exact and sub domain hosts`() {
        assertTrue(
            ExternalAppLaunchPolicy.authorityMatchesHost(
                listOf("reddit.com"), "reddit.com"
            )
        )
        assertTrue(
            ExternalAppLaunchPolicy.authorityMatchesHost(
                listOf("reddit.com"), "www.reddit.com"
            )
        )
        assertTrue(
            ExternalAppLaunchPolicy.authorityMatchesHost(
                listOf("*.reddit.com"), "old.reddit.com"
            )
        )
        assertTrue(
            ExternalAppLaunchPolicy.authorityMatchesHost(
                listOf("other.example", "Reddit.COM"), "reddit.com"
            )
        )
    }

    @Test
    fun `generic browser authorities do not count`() {
        // A filter with no declared authority (plain browser) never reaches
        // here, but a filter whose authority is a different or wildcard
        // domain must not claim the host either.
        assertFalse(ExternalAppLaunchPolicy.authorityMatchesHost(emptyList(), "www.reddit.com"))
        assertFalse(ExternalAppLaunchPolicy.authorityMatchesHost(listOf("example.com"), "www.reddit.com"))
        assertFalse(ExternalAppLaunchPolicy.authorityMatchesHost(listOf("notreddit.com"), "reddit.com"))
        assertFalse(ExternalAppLaunchPolicy.authorityMatchesHost(listOf("reddit.com.evil.com"), "reddit.com"))
        assertFalse(ExternalAppLaunchPolicy.authorityMatchesHost(listOf("reddit.com"), null))
        assertFalse(ExternalAppLaunchPolicy.authorityMatchesHost(listOf("reddit.com"), ""))
    }
}
