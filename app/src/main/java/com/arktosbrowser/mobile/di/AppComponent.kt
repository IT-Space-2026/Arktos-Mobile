// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.di

import com.arktosbrowser.mobile.BrowserApp
import com.arktosbrowser.mobile.browser.SearchBoxModel
import com.arktosbrowser.mobile.browser.activity.BrowserActivity
import com.arktosbrowser.mobile.browser.activity.ThemableBrowserActivity
import com.arktosbrowser.mobile.browser.bookmarks.BookmarksDrawerView
import com.arktosbrowser.mobile.device.BuildInfo
import com.arktosbrowser.mobile.dialog.LightningDialogBuilder
import com.arktosbrowser.mobile.view.LightningDownloadListener
import com.arktosbrowser.mobile.reading.activity.ReadingActivity
import com.arktosbrowser.mobile.search.SuggestionsAdapter
import com.arktosbrowser.mobile.settings.activity.SettingsActivity
import com.arktosbrowser.mobile.settings.fragment.*
import com.arktosbrowser.mobile.view.ArktosCookieChromeClient
import com.arktosbrowser.mobile.view.ArktosCookieView
import com.arktosbrowser.mobile.view.ArktosCookieWebClient
import android.app.Application
import com.arktosbrowser.mobile.adblock.BlocklistLoader
import com.arktosbrowser.mobile.adblock.RankListLoader
import com.arktosbrowser.mobile.adblock.allowlist.AllowListModel
import com.arktosbrowser.mobile.browser.SiteSettings
import com.arktosbrowser.mobile.database.javascript.JavaScriptRepository
import com.arktosbrowser.mobile.download.DownloadCompleteReceiver
import com.arktosbrowser.mobile.history.HistoryActivity
import com.arktosbrowser.mobile.network.ConnectivityGate
import com.arktosbrowser.mobile.popup.PopUpClass
import com.arktosbrowser.mobile.log.Logger
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.uniffi.ArktosStore
import com.arktosbrowser.mobile.userscript.UserScriptBootstrap
import com.arktosbrowser.mobile.view.BookmarkPageInitializer
import com.arktosbrowser.mobile.view.DownloadPageInitializer
import com.arktosbrowser.mobile.view.HistoryPageInitializer
import com.arktosbrowser.mobile.view.HomePageInitializer
import com.arktosbrowser.mobile.view.IncognitoPageInitializer
import com.arktosbrowser.mobile.view.ProxyUtils
import io.reactivex.rxjava3.core.Scheduler
import dagger.BindsInstance
import dagger.Component
import javax.inject.Singleton

@Singleton
@Component(modules = [(AppModule::class), (AppBindsModule::class)])
interface AppComponent {

    @Component.Builder
    interface Builder {

        @BindsInstance
        fun application(application: Application): Builder

        @BindsInstance
        fun buildInfo(buildInfo: BuildInfo): Builder

        fun build(): AppComponent
    }

    fun inject(activity: BrowserActivity)

    fun inject(activity: HistoryActivity)

    fun inject(fragment: ExportSettingsFragment)

    fun inject(builder: LightningDialogBuilder)

    fun inject(arktosCookieView: ArktosCookieView)

    fun inject(activity: ThemableBrowserActivity)

    fun inject(advancedSettingsFragment: AdvancedSettingsFragment)
    fun inject(syncSettingsFragment: SyncSettingsFragment)

    fun inject(app: BrowserApp)

    fun inject(activity: ReadingActivity)

    fun inject(webClient: ArktosCookieWebClient)

    fun inject(activity: SettingsActivity)


    fun inject(listener: LightningDownloadListener)

    fun inject(receiver: DownloadCompleteReceiver)

    fun inject(fragment: PrivacySettingsFragment)

    fun inject(fragment: DebugSettingsFragment)

    fun inject(fragment: ExtensionsSettingsFragment)

    fun inject(suggestionsAdapter: SuggestionsAdapter)

    fun inject(chromeClient: ArktosCookieChromeClient)

    fun inject(searchBoxModel: SearchBoxModel)

    fun inject(generalSettingsFragment: GeneralSettingsFragment)

    fun inject(fragment: AboutSettingsFragment)

    fun inject(displaySettingsFragment: DisplaySettingsFragment)

    fun inject(adBlockSettingsFragment: AdBlockSettingsFragment)

    fun inject(listsSettingsFragment: ListsSettingsFragment)

    fun inject(drawerSettingsFragment: DrawerSettingsFragment)

    fun inject(homepageSettingsFragment: HomepageSettingsFragment)

    fun inject(themeSettingsFragment: ThemeSettingsFragment)

    fun inject(drawerOffsetFragment: DrawerOffsetFragment)

    fun inject(parentalSettingsFragment: ParentalControlSettingsFragment)

    fun inject(bookmarksView: BookmarksDrawerView)

    fun inject(popUpClass: PopUpClass)

    // ---- engine-backend provisions (ENG03) -----------------------------
    // Non-Dagger-constructed backends (the gecko flavor's GeckoEngine)
    // pull the same pieces the WebViewEngine gets injected — reachable
    // from the flavor's EngineSelector through BrowserApp.applicationComponent.

    fun homePageInitializer(): HomePageInitializer

    fun incognitoPageInitializer(): IncognitoPageInitializer

    fun bookmarkPageInitializer(): BookmarkPageInitializer

    fun downloadPageInitializer(): DownloadPageInitializer

    fun historyPageInitializer(): HistoryPageInitializer

    fun userPreferences(): UserPreferences

    fun logger(): Logger

    fun buildInfo(): BuildInfo

    // ---- gecko ENG04 page collaborators ---------------------------------
    // GeckoPage can't get a flavor-specific inject() method (the graph
    // lives in main), so its WebView-client parity collaborators are
    // exposed here for EngineSelector to hand over.

    fun lightningDialogBuilder(): LightningDialogBuilder

    fun allowListModel(): AllowListModel

    fun connectivityGate(): ConnectivityGate

    fun siteSettings(): SiteSettings

    fun blocklistLoader(): BlocklistLoader

    fun rankListLoader(): RankListLoader

    // ---- gecko ENG05 storage/proxy/scripts parity ----------------------

    fun javaScriptRepository(): JavaScriptRepository

    fun downloadsRepository(): com.arktosbrowser.mobile.database.downloads.DownloadsRepository

    fun userScriptBootstrap(): UserScriptBootstrap

    fun arktosStore(): ArktosStore

    fun proxyUtils(): ProxyUtils

    @NetworkScheduler
    fun networkScheduler(): Scheduler

    @MainScheduler
    fun mainScheduler(): Scheduler
}
