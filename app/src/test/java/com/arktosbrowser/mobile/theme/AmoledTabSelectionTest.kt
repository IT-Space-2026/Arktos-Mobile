// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.pow

/**
 * Guards upstream issue #298: the active tab in the drawer was nearly
 * indistinguishable under the AMOLED (black) theme because
 * `selectedBackground` resolved to a 6% white overlay on pure black.
 *
 * There is no Robolectric in this project, so the test audits the resource
 * files directly: it resolves each base theme's `selectedBackground` and
 * `drawerBackground` attributes to colors, composites them, and checks the
 * resulting contrast.
 */
class AmoledTabSelectionTest {

    private val valuesDir = listOf("src/main/res/values", "app/src/main/res/values")
        .map(::File)
        .first { it.isDirectory }

    private val colors: Map<String, Int> by lazy {
        parseResourceFile(File(valuesDir, "colors.xml"), "color")
    }

    private val styles: Map<String, Map<String, String>> by lazy {
        val doc = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(File(valuesDir, "styles.xml"))
        doc.documentElement.getElementsByTagName("style")
            .let { nodes ->
                (0 until nodes.length).associate { i ->
                    val style = nodes.item(i) as Element
                    val name = style.getAttribute("name")
                    val items = style.getElementsByTagName("item")
                    name to (0 until items.length).associate { j ->
                        val item = items.item(j) as Element
                        item.getAttribute("name") to item.textContent.trim()
                    }
                }
            }
    }

    private fun parseResourceFile(file: File, tag: String): Map<String, Int> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.documentElement.getElementsByTagName(tag)
        return (0 until nodes.length).associate { i ->
            val el = nodes.item(i) as Element
            el.getAttribute("name") to parseColor(el.textContent.trim())
        }
    }

    private fun parseColor(hex: String): Int {
        require(hex.startsWith("#")) { "Not a color literal: $hex" }
        val value = hex.substring(1).toLong(16)
        return if (hex.length == 7) (0xFF000000 or value).toInt() else value.toInt()
    }

    private fun resolveColor(styleName: String, attr: String): Int {
        val ref = styles.getValue(styleName).getValue(attr)
        require(ref.startsWith("@color/")) { "$styleName $attr is not a color ref: $ref" }
        return colors.getValue(ref.removePrefix("@color/"))
    }

    /** Composites a possibly translucent [src] over an opaque [dst]. */
    private fun composite(src: Int, dst: Int): Int {
        val a = (src ushr 24) / 255f
        fun chan(shift: Int) =
            (((src ushr shift) and 0xFF) * a + ((dst ushr shift) and 0xFF) * (1 - a)).toInt()
        return (0xFF shl 24) or (chan(16) shl 16) or (chan(8) shl 8) or chan(0)
    }

    private fun luminance(color: Int): Double {
        fun lin(shift: Int): Double {
            val c = ((color ushr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * lin(16) + 0.7152 * lin(8) + 0.0722 * lin(0)
    }

    private fun contrastRatio(c1: Int, c2: Int): Double {
        val l1 = luminance(c1)
        val l2 = luminance(c2)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    private fun selectionContrast(styleName: String): Double {
        val selected = resolveColor(styleName, "selectedBackground")
        val drawer = resolveColor(styleName, "drawerBackground")
        return contrastRatio(composite(selected, drawer), drawer)
    }

    @Test
    fun `black theme selected tab is clearly distinguishable from drawer`() {
        // The reported bug left the active tab at ~1.1:1 contrast on the AMOLED
        // drawer; require comfortably better than that.
        assertTrue(
            "AMOLED selected-tab contrast is too low",
            selectionContrast("Theme.BaseBlackTheme") >= 1.5
        )
    }

    @Test
    fun `light and dark themes keep their original selection colors`() {
        assertEquals(
            "@color/selected_light",
            styles.getValue("Theme.BaseLightTheme").getValue("selectedBackground")
        )
        assertEquals(
            "@color/selected_dark",
            styles.getValue("Theme.BaseDarkTheme").getValue("selectedBackground")
        )
    }

    @Test
    fun `black theme selection is at least as contrasting as dark theme`() {
        assertTrue(
            selectionContrast("Theme.BaseBlackTheme") >=
                selectionContrast("Theme.BaseDarkTheme")
        )
    }
}
