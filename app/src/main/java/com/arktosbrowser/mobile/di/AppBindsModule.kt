// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.di

import com.arktosbrowser.mobile.adblock.allowlist.AllowListModel
import com.arktosbrowser.mobile.adblock.allowlist.SessionAllowListModel
import com.arktosbrowser.mobile.database.allowlist.AdBlockAllowListRepository
import com.arktosbrowser.mobile.database.allowlist.RustAllowListRepository
import com.arktosbrowser.mobile.database.bookmark.BookmarkRepository
import com.arktosbrowser.mobile.database.bookmark.RustBookmarkRepository
import com.arktosbrowser.mobile.database.downloads.DownloadsRepository
import com.arktosbrowser.mobile.database.downloads.RustDownloadsRepository
import com.arktosbrowser.mobile.database.history.HistoryRepository
import com.arktosbrowser.mobile.database.history.RustHistoryRepository
import com.arktosbrowser.mobile.database.javascript.JavaScriptRepository
import com.arktosbrowser.mobile.database.javascript.RustJavaScriptRepository
import com.arktosbrowser.mobile.ssl.SessionSslWarningPreferences
import com.arktosbrowser.mobile.ssl.SslWarningPreferences
import dagger.Binds
import dagger.Module

/**
 * Dependency injection module used to bind implementations to interfaces.
 */
@Module
abstract class AppBindsModule {

    @Binds
    abstract fun provideBookmarkModel(rustBookmarkRepository: RustBookmarkRepository): BookmarkRepository

    @Binds
    abstract fun provideDownloadsModel(rustDownloadsRepository: RustDownloadsRepository): DownloadsRepository

    @Binds
    abstract fun providesHistoryModel(rustHistoryRepository: RustHistoryRepository): HistoryRepository

    @Binds
    abstract fun providesJavaScriptModel(rustJavaScriptRepository: RustJavaScriptRepository): JavaScriptRepository

    @Binds
    abstract fun providesAdBlockAllowListModel(rustAllowListRepository: RustAllowListRepository): AdBlockAllowListRepository

    @Binds
    abstract fun providesAllowListModel(sessionAllowListModel: SessionAllowListModel): AllowListModel

    @Binds
    abstract fun providesSslWarningPreferences(sessionSslWarningPreferences: SessionSslWarningPreferences): SslWarningPreferences
}
