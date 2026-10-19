package com.arktosbrowser.mobile.di

import com.arktosbrowser.mobile.adblock.allowlist.AllowListModel
import com.arktosbrowser.mobile.adblock.allowlist.SessionAllowListModel
import com.arktosbrowser.mobile.adblock.source.AssetsHostsDataSource
import com.arktosbrowser.mobile.adblock.source.HostsDataSource
import com.arktosbrowser.mobile.adblock.source.HostsDataSourceProvider
import com.arktosbrowser.mobile.adblock.source.PreferencesHostsDataSourceProvider
import com.arktosbrowser.mobile.database.adblock.HostsDatabase
import com.arktosbrowser.mobile.database.adblock.HostsRepository
import com.arktosbrowser.mobile.database.allowlist.AdBlockAllowListDatabase
import com.arktosbrowser.mobile.database.allowlist.AdBlockAllowListRepository
import com.arktosbrowser.mobile.database.bookmark.BookmarkDatabase
import com.arktosbrowser.mobile.database.bookmark.BookmarkRepository
import com.arktosbrowser.mobile.database.downloads.DownloadsDatabase
import com.arktosbrowser.mobile.database.downloads.DownloadsRepository
import com.arktosbrowser.mobile.database.history.HistoryDatabase
import com.arktosbrowser.mobile.database.history.HistoryRepository
import com.arktosbrowser.mobile.database.javascript.JavaScriptDatabase
import com.arktosbrowser.mobile.database.javascript.JavaScriptRepository
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
    abstract fun provideBookmarkModel(bookmarkDatabase: BookmarkDatabase): BookmarkRepository

    @Binds
    abstract fun provideDownloadsModel(downloadsDatabase: DownloadsDatabase): DownloadsRepository

    @Binds
    abstract fun providesHistoryModel(historyDatabase: HistoryDatabase): HistoryRepository

    @Binds
    abstract fun providesJavaScriptModel(javaScriptDatabase: JavaScriptDatabase): JavaScriptRepository

    @Binds
    abstract fun providesAdBlockAllowListModel(adBlockAllowListDatabase: AdBlockAllowListDatabase): AdBlockAllowListRepository

    @Binds
    abstract fun providesAllowListModel(sessionAllowListModel: SessionAllowListModel): AllowListModel

    @Binds
    abstract fun providesSslWarningPreferences(sessionSslWarningPreferences: SessionSslWarningPreferences): SslWarningPreferences

    @Binds
    abstract fun providesHostsDataSource(assetsHostsDataSource: AssetsHostsDataSource): HostsDataSource

    @Binds
    abstract fun providesHostsRepository(hostsDatabase: HostsDatabase): HostsRepository

    @Binds
    abstract fun providesHostsDataSourceProvider(preferencesHostsDataSourceProvider: PreferencesHostsDataSourceProvider): HostsDataSourceProvider
}
