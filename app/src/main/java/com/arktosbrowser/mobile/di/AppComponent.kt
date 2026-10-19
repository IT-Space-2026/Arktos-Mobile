package com.arktosbrowser.mobile.di

import com.arktosbrowser.mobile.BrowserApp
import com.arktosbrowser.mobile.adblock.BloomFilterAdBlocker
import com.arktosbrowser.mobile.adblock.NoOpAdBlocker
import com.arktosbrowser.mobile.browser.SearchBoxModel
import com.arktosbrowser.mobile.browser.activity.BrowserActivity
import com.arktosbrowser.mobile.browser.activity.ThemableBrowserActivity
import com.arktosbrowser.mobile.browser.bookmarks.BookmarksDrawerView
import com.arktosbrowser.mobile.device.BuildInfo
import com.arktosbrowser.mobile.dialog.LightningDialogBuilder
import com.arktosbrowser.mobile.download.LightningDownloadListener
import com.arktosbrowser.mobile.reading.activity.ReadingActivity
import com.arktosbrowser.mobile.search.SuggestionsAdapter
import com.arktosbrowser.mobile.settings.activity.SettingsActivity
import com.arktosbrowser.mobile.settings.activity.ThemableSettingsActivity
import com.arktosbrowser.mobile.settings.fragment.*
import com.arktosbrowser.mobile.view.SmartCookieChromeClient
import com.arktosbrowser.mobile.view.SmartCookieView
import com.arktosbrowser.mobile.view.SmartCookieWebClient
import android.app.Application
import com.arktosbrowser.mobile.download.DownloadActivity
import com.arktosbrowser.mobile.history.HistoryActivity
import com.arktosbrowser.mobile.popup.PopUpClass
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

    fun inject(activity: DownloadActivity)

    fun inject(activity: HistoryActivity)

    fun inject(fragment: ExportSettingsFragment)

    fun inject(builder: LightningDialogBuilder)

    fun inject(smartCookieView: SmartCookieView)

    fun inject(activity: ThemableBrowserActivity)

    fun inject(advancedSettingsFragment: AdvancedSettingsFragment)

    fun inject(app: BrowserApp)

    fun inject(activity: ReadingActivity)

    fun inject(webClient: SmartCookieWebClient)

    fun inject(activity: SettingsActivity)

    fun inject(activity: ThemableSettingsActivity)

    fun inject(listener: LightningDownloadListener)

    fun inject(fragment: PrivacySettingsFragment)

    fun inject(fragment: DebugSettingsFragment)

    fun inject(fragment: ExtensionsSettingsFragment)

    fun inject(suggestionsAdapter: SuggestionsAdapter)

    fun inject(chromeClient: SmartCookieChromeClient)

    fun inject(searchBoxModel: SearchBoxModel)

    fun inject(generalSettingsFragment: GeneralSettingsFragment)

    fun inject(displaySettingsFragment: DisplaySettingsFragment)

    fun inject(adBlockSettingsFragment: AdBlockSettingsFragment)

    fun inject(drawerSettingsFragment: DrawerSettingsFragment)

    fun inject(homepageSettingsFragment: HomepageSettingsFragment)

    fun inject(themeSettingsFragment: ThemeSettingsFragment)

    fun inject(drawerOffsetFragment: DrawerOffsetFragment)

    fun inject(parentalSettingsFragment: ParentalControlSettingsFragment)

    fun inject(bookmarksView: BookmarksDrawerView)

    fun provideBloomFilterAdBlocker(): BloomFilterAdBlocker

    fun provideNoOpAdBlocker(): NoOpAdBlocker

    fun inject(popUpClass: PopUpClass)
}
