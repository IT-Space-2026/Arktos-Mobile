package com.arktosbrowser.mobile.search.engine

import com.arktosbrowser.mobile.R

/**
 * The StartPage search engine.
 */
class StartPageSearch : BaseSearchEngine(
    "file:///android_asset/startpage.webp",
    "https://startpage.com/do/search?language=english&query=",
    R.string.search_engine_startpage
)
