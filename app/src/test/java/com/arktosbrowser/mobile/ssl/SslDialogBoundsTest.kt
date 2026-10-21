// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.ssl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards upstream issue #277: at maximum system font/display scaling the
 * certificate info dialog was taller than the screen — the dialog window
 * wraps its content, so the bottom (expiry date, OK button) was clipped
 * instead of being reachable by scrolling.
 *
 * There is no Robolectric in this project, so the test exercises the pure
 * height-bounding helper used by `showSslDialog` and audits the layout to
 * confirm the content sits in a scroll container.
 */
class SslDialogBoundsTest {

    private val layoutFile = listOf(
        "src/main/res/layout/dialog_ssl_info.xml",
        "app/src/main/res/layout/dialog_ssl_info.xml"
    ).map(::File).first { it.isFile }

    @Test
    fun `content that fits is not shrunk`() {
        assertEquals(
            500,
            boundedScrollHeight(
                contentHeight = 500,
                chromeHeight = 200,
                availableHeight = 1000,
                minHeight = 96
            )
        )
    }

    @Test
    fun `oversized content is capped so chrome stays on screen`() {
        // 900px of cert info + 200px of title/buttons on a 1000px screen:
        // content must be capped to 800px so the dialog fits and scrolls.
        assertEquals(
            800,
            boundedScrollHeight(
                contentHeight = 900,
                chromeHeight = 200,
                availableHeight = 1000,
                minHeight = 96
            )
        )
    }

    @Test
    fun `content is never inflated to fill spare room`() {
        assertEquals(
            50,
            boundedScrollHeight(
                contentHeight = 50,
                chromeHeight = 200,
                availableHeight = 1000,
                minHeight = 96
            )
        )
    }

    @Test
    fun `chrome taller than the screen still leaves a scrollable minimum`() {
        // Absurd scaling where title + buttons alone overflow the screen:
        // the viewport must keep at least minHeight rather than vanish.
        assertEquals(
            96,
            boundedScrollHeight(
                contentHeight = 900,
                chromeHeight = 1200,
                availableHeight = 1000,
                minHeight = 96
            )
        )
    }

    @Test
    fun `content smaller than the minimum is kept at its own height`() {
        assertEquals(
            40,
            boundedScrollHeight(
                contentHeight = 40,
                chromeHeight = 1200,
                availableHeight = 1000,
                minHeight = 96
            )
        )
    }

    @Test
    fun `dialog layout root is a scroll container`() {
        val doc = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(layoutFile)
        val rootTag = doc.documentElement.tagName
        assertTrue(
            "dialog_ssl_info must be rooted in a scrollable container, found $rootTag",
            rootTag.endsWith("ScrollView")
        )
    }
}
