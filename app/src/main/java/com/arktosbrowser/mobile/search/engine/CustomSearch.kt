// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.search.engine

import com.arktosbrowser.mobile.R

/**
 * A custom search engine.
 */
class CustomSearch(queryUrl: String) : BaseSearchEngine(
    "file:///android_asset/arktos.webp",
    queryUrl,
    R.string.search_engine_custom
)
