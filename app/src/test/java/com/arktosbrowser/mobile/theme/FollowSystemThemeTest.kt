package com.arktosbrowser.mobile.theme

import com.arktosbrowser.mobile.AppTheme
import com.arktosbrowser.mobile.isDarkWebAddonEnabled
import com.arktosbrowser.mobile.resolve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards upstream issue #251: a "Follow system" theme option that switches
 * the browser theme (and the dark-web addon) with the device night mode.
 *
 * The resolution helpers are pure functions so they can be exercised here;
 * the test additionally audits the manifest to prove the browser activities
 * listen for uiMode config changes.
 */
class FollowSystemThemeTest {

    @Test
    fun `system theme resolves to arktos dark under night mode and arktos light otherwise`() {
        assertEquals(AppTheme.ARKTOS_DARK, AppTheme.SYSTEM.resolve(systemNightMode = true))
        assertEquals(AppTheme.ARKTOS_LIGHT, AppTheme.SYSTEM.resolve(systemNightMode = false))
    }

    @Test
    fun `manual themes ignore the system night mode`() {
        for (theme in listOf(AppTheme.LIGHT, AppTheme.DARK, AppTheme.BLACK, AppTheme.ARKTOS_LIGHT, AppTheme.ARKTOS_DARK)) {
            assertEquals(theme, theme.resolve(systemNightMode = true))
            assertEquals(theme, theme.resolve(systemNightMode = false))
        }
    }

    @Test
    fun `dark web addon follows night mode only when theme follows system`() {
        assertTrue(isDarkWebAddonEnabled(false, AppTheme.SYSTEM, systemNightMode = true))
        assertFalse(isDarkWebAddonEnabled(false, AppTheme.SYSTEM, systemNightMode = false))
        // A manual theme never lets the system drive the addon.
        assertFalse(isDarkWebAddonEnabled(false, AppTheme.DARK, systemNightMode = true))
        assertFalse(isDarkWebAddonEnabled(false, AppTheme.BLACK, systemNightMode = true))
    }

    @Test
    fun `dark web addon toggle still overrides everything`() {
        for (theme in AppTheme.values()) {
            assertTrue(isDarkWebAddonEnabled(true, theme, systemNightMode = false))
            assertTrue(isDarkWebAddonEnabled(true, theme, systemNightMode = true))
        }
    }

    @Test
    fun `persisted enum values stay unique after adding SYSTEM`() {
        // useTheme is stored as the IntEnum value; a collision would silently
        // remap existing users' themes.
        assertEquals(
            AppTheme.values().size,
            AppTheme.values().map { it.value }.distinct().size
        )
    }

    @Test
    fun `browser activities handle uiMode config changes`() {
        // Without uiMode in configChanges ThemableBrowserActivity's
        // onConfigurationChanged handler would never see a night-mode flip.
        val manifest = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
            .map(::File)
            .first { it.isFile }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest)
        val activities = doc.documentElement.getElementsByTagName("activity")
        val configChanges = (0 until activities.length)
            .map { activities.item(it) as Element }
            .associate {
                it.getAttribute("android:name") to it.getAttribute("android:configChanges")
            }

        for (name in listOf(".MainActivity", ".IncognitoActivity")) {
            assertTrue(
                "$name must list uiMode in android:configChanges",
                configChanges.getValue(name).split("|").contains("uiMode")
            )
        }
    }
}
