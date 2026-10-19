package com.arktosbrowser.mobile.search.engine

import com.arktosbrowser.mobile.R

/**
 * The Google search engine.
 *
 * See https://www.google.com/images/srpr/logo11w.png for the icon.
 */
class GoogleSearch : BaseSearchEngine(
    "file:///android_asset/google.webp",
    "https://www.google.com/search?client=arktosmobile&ie=UTF-8&oe=UTF-8&q=",
    R.string.search_engine_google
)
