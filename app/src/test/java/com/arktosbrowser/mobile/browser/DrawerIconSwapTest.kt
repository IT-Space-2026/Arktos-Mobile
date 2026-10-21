// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards upstream issue #297: an option to swap the positions of the tabs and
 * bookmarks drawer icons, mirroring the existing "swap bookmark and tab
 * drawers" option. When enabled, the bookmark icon takes the toolbar's start
 * slot, the tab drawer icon moves next to the overflow button, and the two
 * icons trade places in the bottom navigation bar.
 *
 * There is no Robolectric in this project, so the test audits the layouts,
 * menu resources, and the BrowserActivity wiring directly.
 */
class DrawerIconSwapTest {

    private fun loadXml(vararg candidates: String): Element {
        val file = candidates.map(::File).first { it.isFile }
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(file).documentElement
    }

    private fun loadSource(vararg candidates: String): String {
        val file = candidates.map(::File).first { it.isFile }
        return file.readText()
    }

    private val toolbarLayout: Element by lazy {
        loadXml(
            "src/main/res/layout/toolbar_content.xml",
            "app/src/main/res/layout/toolbar_content.xml"
        )
    }

    private fun elementsById(root: Element, id: String): List<Element> {
        val nodes = root.getElementsByTagName("*")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .filter { it.getAttribute("android:id") == "@+id/$id" }
    }

    private val browserActivitySource: String by lazy {
        loadSource(
            "src/main/java/com/arktosbrowser/mobile/browser/activity/BrowserActivity.kt",
            "app/src/main/java/com/arktosbrowser/mobile/browser/activity/BrowserActivity.kt"
        )
    }

    @Test
    fun `toolbar contains a bookmarks button hidden by default`() {
        val button = elementsById(toolbarLayout, "bookmarks_button").single()
        assertEquals("gone", button.getAttribute("android:visibility"))
        assertEquals("true", button.getAttribute("android:clickable"))
        assertEquals("true", button.getAttribute("android:focusable"))
        assertEquals(
            "@string/action_bookmarks",
            button.getAttribute("android:contentDescription")
        )
        val images = button.getElementsByTagName("ImageView")
        val icons = (0 until images.length)
            .map { (images.item(it) as Element).getAttribute("app:srcCompat") }
        assertTrue(icons.contains("@drawable/ic_action_star"))
    }

    @Test
    fun `bookmarks button occupies the start slot the tab icon swaps into`() {
        // The bookmarks button sits before home_button in the layout so that
        // making it visible places it at the toolbar's start edge.
        val nodes = toolbarLayout.getElementsByTagName("*")
        val order = (0 until nodes.length)
            .map { (nodes.item(it) as Element).getAttribute("android:id") }
        val bookmarksIndex = order.indexOf("@+id/bookmarks_button")
        val homeIndex = order.indexOf("@+id/home_button")
        assertTrue(bookmarksIndex >= 0 && homeIndex >= 0)
        assertTrue(bookmarksIndex < homeIndex)
    }

    @Test
    fun `swapped bottom menu trades the tabs and bookmarks icon positions`() {
        val swapped = loadXml(
            "src/main/res/menu/bottom_menu_swapped.xml",
            "app/src/main/res/menu/bottom_menu_swapped.xml"
        )
        val itemIds = (0 until swapped.getElementsByTagName("item").length)
            .map { swapped.getElementsByTagName("item").item(it) as Element }
            .map { it.getAttribute("android:id") }
        assertEquals(
            listOf("@+id/bookmarks", "@+id/back", "@+id/home", "@+id/forward", "@+id/tabs"),
            itemIds
        )
    }

    @Test
    fun `preference and settings wire the swap icons option`() {
        val userPreferences = loadSource(
            "src/main/java/com/arktosbrowser/mobile/preference/UserPreferences.kt",
            "app/src/main/java/com/arktosbrowser/mobile/preference/UserPreferences.kt"
        )
        assertTrue(
            "bookmarksAndTabsIconsSwapped must persist via a dedicated key",
            userPreferences.contains(
                "bookmarksAndTabsIconsSwapped by preferences.booleanPreference(" +
                    "SWAP_BOOKMARKS_AND_TABS_ICONS, false)"
            )
        )

        val drawerPrefs = loadXml(
            "src/main/res/xml/preference_drawers.xml",
            "app/src/main/res/xml/preference_drawers.xml"
        )
        val switches = drawerPrefs.getElementsByTagName("SwitchPreference")
        val keys = (0 until switches.length)
            .map { (switches.item(it) as Element).getAttribute("android:key") }
        assertTrue(keys.contains("cb_swapicons"))

        val drawerFragment = loadSource(
            "src/main/java/com/arktosbrowser/mobile/settings/fragment/DrawerSettingsFragment.kt",
            "app/src/main/java/com/arktosbrowser/mobile/settings/fragment/DrawerSettingsFragment.kt"
        )
        assertTrue(drawerFragment.contains("cb_swapicons"))
        assertTrue(
            drawerFragment.contains("userPreferences.bookmarksAndTabsIconsSwapped = it")
        )
    }

    @Test
    fun `BrowserActivity applies the swap to toolbar and navbar`() {
        // Tab icon moves to the end while the bookmark icon appears at start.
        assertTrue(
            Regex("""bookmarksButton\.visibility = VISIBLE[\s\S]*?removeView\(toolbarBinding\.homeButton\)""")
                .containsMatchIn(browserActivitySource)
        )
        assertTrue(
            "bookmarks button must open the bookmark drawer",
            Regex("""bookmarksButton\.setOnClickListener \{ openBookmarksDrawer\(\) \}""")
                .containsMatchIn(browserActivitySource)
        )
        // The bottom navbar re-inflates the swapped menu.
        assertTrue(browserActivitySource.contains("R.menu.bottom_menu_swapped"))
        // Toggling the option restarts the activity so the toolbar rebuilds.
        assertTrue(
            Regex("""swapDrawerIcons != userPreferences\.bookmarksAndTabsIconsSwapped[\s\S]*?restart\(\)""")
                .containsMatchIn(browserActivitySource)
        )
    }
}
