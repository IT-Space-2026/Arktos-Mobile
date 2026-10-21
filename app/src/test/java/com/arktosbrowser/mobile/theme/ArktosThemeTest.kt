// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.theme

import com.arktosbrowser.mobile.AppTheme
import com.arktosbrowser.mobile.isDark
import com.arktosbrowser.mobile.resolve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Covers the Arktos brand themes: [AppTheme.ARKTOS_LIGHT] and
 * [AppTheme.ARKTOS_DARK]. Persisted enum values must stay stable, darkness
 * detection must treat the dark variant like the other dark themes, and the
 * resource styles backing both variants must exist.
 */
class ArktosThemeTest {

    @Test
    fun `arktos dark counts as dark, arktos light does not`() {
        assertTrue(AppTheme.ARKTOS_DARK.isDark)
        assertFalse(AppTheme.ARKTOS_LIGHT.isDark)
        // Existing mappings unchanged.
        assertTrue(AppTheme.DARK.isDark)
        assertTrue(AppTheme.BLACK.isDark)
        assertFalse(AppTheme.LIGHT.isDark)
        assertFalse(AppTheme.SYSTEM.isDark)
    }

    @Test
    fun `arktos themes are manual and pass through resolve`() {
        for (theme in listOf(AppTheme.ARKTOS_LIGHT, AppTheme.ARKTOS_DARK)) {
            assertEquals(theme, theme.resolve(systemNightMode = true))
            assertEquals(theme, theme.resolve(systemNightMode = false))
        }
    }

    @Test
    fun `all persisted theme values remain unique`() {
        assertEquals(
            AppTheme.values().size,
            AppTheme.values().map { it.value }.distinct().size
        )
    }

    @Test
    fun `arktos theme styles exist for browser and settings screens`() {
        val styles = listOf("src/main/res/values/styles.xml", "app/src/main/res/values/styles.xml")
            .map(::File)
            .first { it.isFile }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(styles)
        val names = (0 until doc.documentElement.getElementsByTagName("style").length)
            .map { doc.documentElement.getElementsByTagName("style").item(it) as Element }
            .map { it.getAttribute("name") }
            .toSet()

        for (name in listOf(
            "Theme.ArktosLightTheme",
            "Theme.ArktosDarkTheme",
            "Theme.SettingsTheme.Arktos",
            "Theme.SettingsTheme.Arktos.Dark"
        )) {
            assertTrue("$name must be declared in styles.xml", names.contains(name))
        }
    }
}
