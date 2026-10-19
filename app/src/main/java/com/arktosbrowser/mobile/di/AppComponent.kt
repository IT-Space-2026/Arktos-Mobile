package com.arktosbrowser.mobile.di

import com.arktosbrowser.mobile.BrowserApp
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
import com.arktosbrowser.mobile.view.ArktosCookieChromeClient
import com.arktosbrowser.mobile.view.ArktosCookieView
import com.arktosbrowser.mobile.view.ArktosCookieWebClient
import android.app.Application
import com.arktosbrowser.mobile.download.DownloadActivity
import com.arktosbrowser.mobile.download.DownloadCompleteReceiver
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

    fun inject(arktosCookieView: ArktosCookieView)

    fun inject(activity: ThemableBrowserActivity)

    fun inject(advancedSettingsFragment: AdvancedSettingsFragment)

    fun inject(app: BrowserApp)

    fun inject(activity: ReadingActivity)

    fun inject(webClient: ArktosCookieWebClient)

    fun inject(activity: SettingsActivity)

    fun inject(activity: ThemableSettingsActivity)

    fun inject(listener: LightningDownloadListener)

    fun inject(receiver: DownloadCompleteReceiver)

    fun inject(fragment: PrivacySettingsFragment)

    fun inject(fragment: DebugSettingsFragment)

    fun inject(fragment: ExtensionsSettingsFragment)

    fun inject(suggestionsAdapter: SuggestionsAdapter)

    fun inject(chromeClient: ArktosCookieChromeClient)

    fun inject(searchBoxModel: SearchBoxModel)

    fun inject(generalSettingsFragment: GeneralSettingsFragment)

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
}
