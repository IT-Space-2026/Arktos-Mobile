// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.search.engine

import com.arktosbrowser.mobile.R

/**
 * The Qwant search engine — a privacy-focused European engine.
 */
class QwantSearch : BaseSearchEngine(
    "file:///android_asset/qwant.webp",
    "https://www.qwant.com/?q=",
    R.string.search_engine_qwant
)
