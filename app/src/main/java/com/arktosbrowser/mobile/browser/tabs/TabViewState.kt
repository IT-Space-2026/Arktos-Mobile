package com.arktosbrowser.mobile.browser.tabs

import com.arktosbrowser.mobile.engine.Page
import android.graphics.Bitmap

/**
 * @param id The unique id of the tab.
 * @param title The title of the tab.
 * @param favicon The favicon of the tab, may be null.
 * @param isForegroundTab True if the tab is in the foreground, false otherwise.
 */
data class TabViewState(
    val id: Int,
    val title: String,
    val favicon: Bitmap?,
    val isForegroundTab: Boolean
)

/**
 * Converts a [Page] to a [TabViewState].
 */
fun Page.asTabViewState() = TabViewState(
    id = id,
    title = title,
    favicon = favicon,
    isForegroundTab = isForegroundTab
)
