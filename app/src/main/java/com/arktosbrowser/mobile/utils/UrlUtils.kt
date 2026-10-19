/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:JvmName("UrlUtils")

package com.arktosbrowser.mobile.utils

import com.arktosbrowser.mobile.rust.ArktosCore

/**
 * PORT02 — the logic behind these helpers moved to the Rust core
 * (`urlutils.rs`); this file only keeps the call-site-facing names and
 * the [QUERY_PLACE_HOLDER] constant used to build search templates.
 */

/**
 * Attempts to determine whether user input is a URL or search terms. Anything with a space is
 * passed to search if [canBeSearch] is true.
 *
 * Converts to lowercase any mistakenly upper-cased scheme (i.e., "Http://" converts to
 * "http://")
 *
 * @param canBeSearch if true, will return a search url if it isn't a valid  URL. If false,
 * invalid URLs will return null.
 * @return original or modified URL.
 */
fun smartUrlFilter(url: String, canBeSearch: Boolean, searchUrl: String): String =
    ArktosCore.smartUrlFilter(url, canBeSearch, searchUrl)


/**
 * Returns whether the given url is the bookmarks/history page or a normal website
 */
fun String?.isSpecialUrl(): Boolean =
    this != null && ArktosCore.isSpecialUrl(this)

/**
 * Returns a display-only variant of the url with the scheme (`http://`,
 * `https://`, `file://`, …) and a leading `www.` host prefix removed, e.g.
 * `https://www.example.com/a` becomes `example.com/a`.
 *
 * The receiver is unchanged; the result is only meant for rendering in the
 * URL bar, never for navigation. Urls without a `://` separator are returned
 * as-is apart from the `www.` trim.
 */
fun String.trimmedForDisplay(): String {
    val withoutScheme = substringAfter("://", this)
    return if (withoutScheme.startsWith(WWW_PREFIX, ignoreCase = true)) {
        withoutScheme.substring(WWW_PREFIX.length)
    } else {
        withoutScheme
    }
}

/**
 * The placeholder origin used as `baseUrl` when the app's generated error and
 * interstitial pages are loaded via `WebView.loadDataWithBaseURL`. It exists
 * only to give those pages a stable origin and is never navigable on its own.
 */
const val ERROR_PAGE_ORIGIN = "https://arktos-error.local"

/**
 * Returns whether the url is a locally generated page rather than a real,
 * navigable address: `data:` URIs committed by `loadDataWithBaseURL`, the
 * [ERROR_PAGE_ORIGIN] base, and `about:blank` (loaded just before an error
 * page replaces it). Generated pages must never be persisted as a tab's URL
 * (upstream #281).
 */
fun String?.isGeneratedPageUrl(): Boolean =
    this != null
        && (this == "about:blank"
        || this.startsWith("data:")
        || this.startsWith(ERROR_PAGE_ORIGIN))

/**
 * Resolves the URL a tab should report — and persist — for the WebView's
 * [committedUrl]. When the WebView is showing a generated error/interstitial
 * page, the committed URL is a synthetic `data:` URI, so the original page
 * URL ([generatedPageOriginalUrl]) is returned instead (upstream #281).
 * Genuine `data:` navigations and all real pages pass through untouched.
 */
fun resolveTabUrl(committedUrl: String?, generatedPageOriginalUrl: String?): String =
    if (generatedPageOriginalUrl != null && committedUrl.isGeneratedPageUrl()) {
        generatedPageOriginalUrl
    } else {
        committedUrl.orEmpty()
    }

/**
 * Determines if the url is a url for the bookmark page.
 *
 * @return true if the url is a bookmark url, false otherwise.
 */
fun String?.isBookmarkUrl(): Boolean =
    this != null && ArktosCore.isBookmarkUrl(this)

/**
 * Determines if the url is a url for the bookmark page.
 *
 * @return true if the url is a bookmark url, false otherwise.
 */
fun String?.isDownloadsUrl(): Boolean =
    this != null && ArktosCore.isDownloadsUrl(this)

/**
 * Determines if the url is a url for the history page.
 *
 * @return true if the url is a history url, false otherwise.
 */
fun String?.isHistoryUrl(): Boolean =
    this != null && ArktosCore.isHistoryUrl(this)

/**
 * Determines if the url is a url for the start page.
 *
 * @return true if the url is a start page url, false otherwise.
 */
fun String?.isStartPageUrl(): Boolean =
    this != null && ArktosCore.isStartPageUrl(this)

const val QUERY_PLACE_HOLDER = "%s"
private const val URL_ENCODED_SPACE = "%20"
private const val WWW_PREFIX = "www."
