// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.userscript

import com.arktosbrowser.mobile.database.javascript.JavaScriptEntry
import com.arktosbrowser.mobile.log.Logger
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.uniffi.ArktosStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/**
 * ENG05 — the extracted, engine-neutral half of the userscript
 * runtime. Both backends inject the bootstrap this class builds:
 * the WebView via `addDocumentStartJavaScript`, GeckoView via the
 * built-in extension's `contentScripts.register`. These tests pin
 * the transport seams and the SEC06 policy surface the native
 * bridges consult.
 */
class UserScriptBootstrapTest {

    private val store = mock<ArktosStore> {
        on { gmValuesForScript(any()) } doReturn mapOf("k" to "\"v\"")
    }
    private val logger = mock<Logger>()
    private val userPreferences = mock<UserPreferences>()
    private val bootstrap = UserScriptBootstrap(store, userPreferences, logger)

    private fun entry(
        name: String,
        include: String? = ".*",
        exclude: String? = null,
        connects: String? = null
    ) = JavaScriptEntry(
        name = name,
        namespace = null,
        version = "1.0",
        author = null,
        include = include,
        exclude = exclude,
        time = null,
        permissions = "GM_xmlhttpRequest",
        code = "console.log(1);",
        requirements = null,
        runAt = "document-start",
        noFrames = false,
        connects = connects
    )

    @Test
    fun `empty script set produces no bootstrap`() {
        assertNull(bootstrap.build(emptyList(), UserScriptBootstrap.Transport.WEB_VIEW))
        assertNull(bootstrap.build(emptyList(), UserScriptBootstrap.Transport.GECKO))
    }

    @Test
    fun `webview transport posts through the arktosGM bridge`() {
        val js = bootstrap.build(
            listOf(entry("s")), UserScriptBootstrap.Transport.WEB_VIEW
        )!!

        assertTrue(js.contains("window.arktosGM"))
        assertTrue(js.contains("__arktosExec("))
        assertTrue(js.contains("console.log(1);"))
    }

    @Test
    fun `gecko transport uses direct native messaging`() {
        val js = bootstrap.build(
            listOf(entry("s")), UserScriptBootstrap.Transport.GECKO
        )!!

        // The bootstrap must reach the session's native delegate
        // directly — a background relay would stamp the extension URL
        // on sender.url and break the SEC06 origin check.
        assertTrue(js.contains("sendNativeMessage"))
        assertFalse(js.contains("arktosGM"))
        assertTrue(js.contains("__arktosExec("))
    }

    @Test
    fun `policies gate ops by include and exclude`() {
        bootstrap.build(
            listOf(
                entry(
                    name = "s",
                    include = ".*example\\.com.*",
                    exclude = ".*bad\\.example\\.com.*"
                )
            ),
            UserScriptBootstrap.Transport.WEB_VIEW
        )

        assertTrue(
            bootstrap.permitted("s", null, "https://www.example.com/page")
        )
        assertFalse(
            bootstrap.permitted("s", null, "https://bad.example.com/")
        )
        assertFalse(bootstrap.permitted("s", null, "https://other.org/"))
        assertFalse(
            bootstrap.permitted("unknown", null, "https://www.example.com/")
        )
    }

    @Test
    fun `connect declarations round trip through the policy map`() {
        bootstrap.build(
            listOf(entry(name = "s", connects = "api.example.com,*.cdn.example.com")),
            UserScriptBootstrap.Transport.WEB_VIEW
        )

        assertEquals(
            listOf("api.example.com", "*.cdn.example.com"),
            bootstrap.connectsOf("s")
        )
        assertEquals(emptyList<String>(), bootstrap.connectsOf("nope"))
    }

    @Test
    fun `rebuilding replaces the policy map`() {
        bootstrap.build(
            listOf(entry("a")), UserScriptBootstrap.Transport.WEB_VIEW
        )
        bootstrap.build(emptyList(), UserScriptBootstrap.Transport.WEB_VIEW)

        assertFalse(bootstrap.permitted("a", null, "https://x.com/"))
    }
}
