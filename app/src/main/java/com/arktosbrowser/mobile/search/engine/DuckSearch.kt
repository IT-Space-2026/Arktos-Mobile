package com.arktosbrowser.mobile.search.engine

import com.arktosbrowser.mobile.R

/**
 * The DuckDuckGo search engine.
 *
 * See https://duckduckgo.com/assets/logo_homepage.normal.v101.png for the icon.
 */
class DuckSearch : BaseSearchEngine(
    "file:///android_asset/duckduckgo.webp",
    "https://duckduckgo.com/?t=lightning&q=",
    R.string.search_engine_duckduckgo
)
