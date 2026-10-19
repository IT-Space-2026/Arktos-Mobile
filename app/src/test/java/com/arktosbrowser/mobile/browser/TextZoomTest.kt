package com.arktosbrowser.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers upstream issue #275: the "Text size" setting capped WebView
 * textZoom at 200%. The ladder now extends to 400% while every index
 * stored by older versions (0..7) keeps its original zoom level.
 */
class TextZoomTest {

    @Test
    fun `indices stored by older versions keep their zoom`() {
        val legacy = mapOf(
            0 to 200, 1 to 150, 2 to 125, 3 to 100,
            4 to 75, 5 to 50, 6 to 25, 7 to 20
        )
        legacy.forEach { (index, zoom) ->
            assertEquals(zoom, TextZoom.zoomPercent(index))
        }
    }

    @Test
    fun `maximum zoom is raised above the old 200 percent cap`() {
        assertTrue(TextZoom.zoomPercent(TextZoom.MIN_INDEX) > TextZoom.zoomPercent(0))
        assertEquals(400, TextZoom.zoomPercent(TextZoom.MIN_INDEX))
    }

    @Test
    fun `zoom decreases monotonically across the ladder`() {
        val zooms = (TextZoom.MIN_INDEX..TextZoom.MAX_INDEX).map(TextZoom::zoomPercent)
        assertEquals(zooms.sortedDescending(), zooms)
        assertEquals(zooms.size, zooms.distinct().size)
    }

    @Test
    fun `out of range indices clamp to the nearest level`() {
        assertEquals(TextZoom.zoomPercent(TextZoom.MIN_INDEX), TextZoom.zoomPercent(-100))
        assertEquals(TextZoom.zoomPercent(TextZoom.MAX_INDEX), TextZoom.zoomPercent(100))
    }

    @Test
    fun `seekbar progress round trips through stored indices`() {
        for (index in TextZoom.MIN_INDEX..TextZoom.MAX_INDEX) {
            assertEquals(index, TextZoom.fromProgress(TextZoom.toProgress(index)))
        }
    }

    @Test
    fun `seekbar ends map to the extreme zoom levels`() {
        assertEquals(20, TextZoom.zoomPercent(TextZoom.fromProgress(0)))
        assertEquals(400, TextZoom.zoomPercent(TextZoom.fromProgress(TextZoom.SEEKBAR_MAX)))
    }
}
