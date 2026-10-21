// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.search.engine

import com.arktosbrowser.mobile.R

/**
 * The StartPage mobile search engine.
 */
class StartPageMobileSearch : BaseSearchEngine(
    "file:///android_asset/startpage.webp",
    "https://www.startpage.com/sp/search?language=english&query=",
    R.string.search_engine_startpage_mobile
)
