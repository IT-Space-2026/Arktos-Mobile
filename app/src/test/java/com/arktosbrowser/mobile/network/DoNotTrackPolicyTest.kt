// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SEC17 — Sec-GPC rides alongside DNT behind `doNotTrackEnabled`.
 * The policy object is pure JVM; a source-scan guard keeps
 * [com.arktosbrowser.mobile.view.ArktosCookieView] wired to it so the
 * header set can't silently drift back to DNT-only.
 */
class DoNotTrackPolicyTest {

    private fun moduleFile(path: String): File =
        listOf(path, "app/$path").map(::File).first { it.isFile }

    @Test
    fun `enabled emits both DNT and Sec-GPC`() {
        val headers = DoNotTrackPolicy.headers(enabled = true)
        assertEquals("1", headers[DoNotTrackPolicy.HEADER_DNT])
        assertEquals("1", headers[DoNotTrackPolicy.HEADER_GPC])
        assertEquals(2, headers.size)
    }

    @Test
    fun `Sec-GPC uses the spec header name`() {
        // GPC clients MUST send exactly "Sec-GPC: 1" — a typo'd name or
        // a "true"/"yes" value is ignored by honoring sites.
        assertEquals("Sec-GPC", DoNotTrackPolicy.HEADER_GPC)
        assertEquals("DNT", DoNotTrackPolicy.HEADER_DNT)
    }

    @Test
    fun `disabled emits no tracking signals`() {
        assertTrue(DoNotTrackPolicy.headers(enabled = false).isEmpty())
    }

    @Test
    fun `applyTo sets both headers when enabled`() {
        val target = mutableMapOf("Save-Data" to "on")
        DoNotTrackPolicy.applyTo(target, enabled = true)
        assertEquals(
            mapOf("Save-Data" to "on", "DNT" to "1", "Sec-GPC" to "1"),
            target
        )
    }

    @Test
    fun `applyTo removes stale signals when disabled`() {
        val target = mutableMapOf("DNT" to "1", "Sec-GPC" to "1", "Save-Data" to "on")
        DoNotTrackPolicy.applyTo(target, enabled = false)
        assertEquals(mapOf("Save-Data" to "on"), target)
        assertFalse(target.containsKey("Sec-GPC"))
    }

    @Test
    fun `ArktosCookieView applies the policy from initializePreferences`() {
        val view = moduleFile(
            "src/main/java/com/arktosbrowser/mobile/view/ArktosCookieView.kt"
        ).readText()
        assertTrue(
            "ArktosCookieView no longer applies DoNotTrackPolicy",
            view.contains("DoNotTrackPolicy.applyTo(requestHeaders")
        )
    }

    @Test
    fun `gecko engine drives full-coverage GPC pref off the same toggle`() {
        val gecko = moduleFile(
            "src/gecko/java/com/arktosbrowser/mobile/gecko/GeckoEngine.kt"
        ).readText()
        assertTrue(
            "gecko dropped the GPC pref",
            gecko.contains("privacy.globalprivacycontrol.enabled")
        )
        assertTrue(
            "gecko GPC is not bound to doNotTrackEnabled",
            gecko.contains("setGeckoPref(PREF_GPC, userPreferences.doNotTrackEnabled)")
        )
    }
}
