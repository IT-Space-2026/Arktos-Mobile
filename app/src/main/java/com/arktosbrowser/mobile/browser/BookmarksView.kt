package com.arktosbrowser.mobile.browser

import com.arktosbrowser.mobile.database.Bookmark

interface BookmarksView {

    fun navigateBack()

    fun handleUpdatedUrl(url: String)

    fun handleBookmarkDeleted(bookmark: Bookmark)

}
