package com.arktosbrowser.mobile.search.engine

import com.arktosbrowser.mobile.R

/**
 * The Mojeek search engine — an independent, no-tracking crawler index.
 */
class MojeekSearch : BaseSearchEngine(
    "file:///android_asset/mojeek.webp",
    "https://www.mojeek.com/search?q=",
    R.string.search_engine_mojeek
)
