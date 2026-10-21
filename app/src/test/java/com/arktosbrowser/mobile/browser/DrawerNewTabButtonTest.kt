// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards upstream issue #299: with "tabs in drawer" enabled, creating a tab
 * required opening the drawer first because the tab strip (and its +
 * button) is hidden. A persistent + button now sits in the toolbar and is
 * shown only in drawer mode.
 *
 * There is no Robolectric in this project, so the test audits the toolbar
 * layout and the BrowserActivity wiring directly.
 */
class DrawerNewTabButtonTest {

    private val toolbarLayout: Element by lazy {
        val file = listOf(
            "src/main/res/layout/toolbar_content.xml",
            "app/src/main/res/layout/toolbar_content.xml"
        ).map(::File).first { it.isFile }
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(file).documentElement
    }

    private fun elementsById(id: String): List<Element> {
        val nodes = toolbarLayout.getElementsByTagName("*")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .filter { it.getAttribute("android:id") == "@+id/$id" }
    }

    private val browserActivitySource: String by lazy {
        val file = listOf(
            "src/main/java/com/arktosbrowser/mobile/browser/activity/BrowserActivity.kt",
            "app/src/main/java/com/arktosbrowser/mobile/browser/activity/BrowserActivity.kt"
        ).map(::File).first { it.isFile }
        file.readText()
    }

    @Test
    fun `toolbar contains a dedicated new tab button`() {
        val buttons = elementsById("new_tab_button")
        assertEquals(1, buttons.size)
        val button = buttons.single()
        assertEquals("true", button.getAttribute("android:clickable"))
        assertEquals("true", button.getAttribute("android:focusable"))
        assertEquals("@string/action_new_tab", button.getAttribute("android:contentDescription"))
    }

    @Test
    fun `new tab button is hidden by default so strip mode is unaffected`() {
        // BrowserActivity toggles visibility at runtime; the layout default
        // must be gone so tab-strip mode doesn't get a duplicate + button.
        val button = elementsById("new_tab_button").single()
        assertEquals("gone", button.getAttribute("android:visibility"))
    }

    @Test
    fun `new tab button uses the same plus icon as the tab strip`() {
        val button = elementsById("new_tab_button").single()
        val images = button.getElementsByTagName("ImageView")
        val icons = (0 until images.length)
            .map { (images.item(it) as Element).getAttribute("app:srcCompat") }
        assertTrue(icons.contains("@drawable/ic_action_plus"))
    }

    @Test
    fun `new tab button sits before the overflow button`() {
        val nodes = toolbarLayout.getElementsByTagName("*")
        val order = (0 until nodes.length)
            .map { (nodes.item(it) as Element).getAttribute("android:id") }
        val newTabIndex = order.indexOf("@+id/new_tab_button")
        val moreIndex = order.indexOf("@+id/more_button")
        assertTrue(newTabIndex >= 0 && moreIndex >= 0)
        assertTrue(newTabIndex < moreIndex)
    }

    @Test
    fun `BrowserActivity shows the button only in drawer mode and wires new tab`() {
        val wiring = Regex(
            """newTabButton[\s\S]*?shouldShowTabsInDrawer[\s\S]*?newTabButtonClicked\(\)"""
        )
        assertTrue(
            "new_tab_button must be visibility-gated on shouldShowTabsInDrawer " +
                "and call newTabButtonClicked()",
            wiring.containsMatchIn(browserActivitySource)
        )
        assertNotNull(
            "long-press should keep the clone-tab action",
            Regex("""newTabButton[\s\S]*?newTabButtonLongClicked\(\)""")
                .find(browserActivitySource)
        )
    }
}
