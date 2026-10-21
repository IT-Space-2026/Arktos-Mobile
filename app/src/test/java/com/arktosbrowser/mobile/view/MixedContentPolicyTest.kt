// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.view

import android.webkit.WebSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SEC03 — normal browsing used to run
 * [WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE], which lets HTTPS
 * pages load attacker-tamperable HTTP subresources. The policy now
 * defaults to strict blocking everywhere; the privacy toggle can only
 * relax normal browsing, never incognito.
 */
class MixedContentPolicyTest {

    @Test
    fun `normal browsing blocks mixed content by default`() {
        assertEquals(
            WebSettings.MIXED_CONTENT_NEVER_ALLOW,
            MixedContentPolicy.mode(isIncognito = false, blockMixedContent = true)
        )
    }

    @Test
    fun `incognito always blocks mixed content`() {
        assertEquals(
            WebSettings.MIXED_CONTENT_NEVER_ALLOW,
            MixedContentPolicy.mode(isIncognito = true, blockMixedContent = true)
        )
        assertEquals(
            WebSettings.MIXED_CONTENT_NEVER_ALLOW,
            MixedContentPolicy.mode(isIncognito = true, blockMixedContent = false)
        )
    }

    @Test
    fun `normal browsing falls back to compatibility mode only when the toggle is off`() {
        assertEquals(
            WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE,
            MixedContentPolicy.mode(isIncognito = false, blockMixedContent = false)
        )
    }

    @Test
    fun `never escalates to always-allow`() {
        listOf(true, false).forEach { incognito ->
            listOf(true, false).forEach { block ->
                assert(
                    MixedContentPolicy.mode(incognito, block) !=
                        WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                )
            }
        }
    }
}
