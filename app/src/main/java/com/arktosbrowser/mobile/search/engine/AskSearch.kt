package com.arktosbrowser.mobile.search.engine

import com.arktosbrowser.mobile.R

/**
 * The Ask search engine.
 */
class AskSearch : BaseSearchEngine(
    "file:///android_asset/ask.webp",
    "http://www.ask.com/web?qsrc=0&o=0&l=dir&qo=ArktosMobile&q=",
    R.string.search_engine_ask
)
