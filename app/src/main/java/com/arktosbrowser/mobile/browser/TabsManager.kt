package com.arktosbrowser.mobile.browser

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import com.arktosbrowser.mobile.utils.isFileUrl
import com.arktosbrowser.mobile.di.DatabaseScheduler
import com.arktosbrowser.mobile.di.DiskScheduler
import com.arktosbrowser.mobile.di.MainScheduler
import com.arktosbrowser.mobile.engine.Engines
import com.arktosbrowser.mobile.engine.Page
import com.arktosbrowser.mobile.engine.PageRequest
import com.arktosbrowser.mobile.engine.ProfileOptions
import com.arktosbrowser.mobile.log.Logger
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.search.SearchEngineProvider
import com.arktosbrowser.mobile.utils.*
import com.arktosbrowser.mobile.view.*
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.kotlin.subscribeBy
import javax.inject.Inject

/**
 * A manager singleton that holds all the [Page] and tracks the current tab. It handles
 * creation, deletion, restoration, state saving, and switching of tabs.
 */
class TabsManager @Inject constructor(
    private val application: Application,
    private val searchEngineProvider: SearchEngineProvider,
    @DatabaseScheduler private val databaseScheduler: Scheduler,
    @DiskScheduler private val diskScheduler: Scheduler,
    @MainScheduler private val mainScheduler: Scheduler,
    private val homePageInitializer: HomePageInitializer,
    private val incognitoPageInitializer: IncognitoPageInitializer,
    private val bookmarkPageInitializer: BookmarkPageInitializer,
    private val historyPageInitializer: HistoryPageInitializer,
    private val downloadPageInitializer: DownloadPageInitializer,
    private val logger: Logger,
    private val userPreferences: UserPreferences
) {

    private val tabList = arrayListOf<Page>()

    /**
     * Return the current [Page] or null if no current tab has been set.
     *
     * @return a [Page] or null if there is no current tab.
     */
    var currentTab: Page? = null
        private set

    private var tabNumberListeners = emptySet<(Int) -> Unit>()

    private var isInitialized = false
    private var postInitializationWorkList = emptyList<() -> Unit>()

    /**
     * Adds a listener to be notified when the number of tabs changes.
     */
    fun addTabNumberChangedListener(listener: ((Int) -> Unit)) {
        tabNumberListeners = tabNumberListeners + listener
    }

    /**
     * Cancels any pending work that was scheduled to run after initialization.
     */
    fun cancelPendingWork() {
        postInitializationWorkList = emptyList()
    }

    /**
     * Executes the [runnable] after the manager has been initialized.
     */
    fun doAfterInitialization(runnable: () -> Unit) {
        if (isInitialized) {
            runnable()
        } else {
            postInitializationWorkList = postInitializationWorkList + runnable
        }
    }

    private fun finishInitialization() {
        isInitialized = true
        for (tab in tabList) {
            if(!tab.isForegroundTab && !userPreferences.allTabs){
                tab.stop()
            }
        }

        for (runnable in postInitializationWorkList) {
            runnable()
        }
    }

    /**
     * Initialize the state of the [TabsManager] based on previous state of the browser and with the
     * new provided [intent] and emit the last tab that should be displayed. By default operates on
     * a background scheduler and emits on the foreground scheduler.
     */
    fun initializeTabs(activity: Activity, intent: Intent?, incognito: Boolean): Single<Page> =
        Single
            .just(Option.fromNullable(extractUrlFromIntent(intent)))
            .doOnSuccess { shutdown() }
            .subscribeOn(mainScheduler)
            .observeOn(databaseScheduler)
            .flatMapObservable {
                return@flatMapObservable if (incognito) {
                    initializeIncognitoMode(it.value())
                } else {
                    initializeRegularMode(it.value(), activity)
                }
            }
            .observeOn(mainScheduler)
            .map { newTab(activity, it, incognito) }
            .lastOrError()
            .doAfterSuccess { finishInitialization() }

    /**
     * Returns an [Observable] that emits the [TabInitializer] for incognito mode.
     */
    private fun initializeIncognitoMode(initialUrl: String?): Observable<TabInitializer> =
        Observable.fromCallable {
            return@fromCallable initialUrl?.let(::UrlInitializer) ?: incognitoPageInitializer
        }

    /**
     * Returns an [Observable] that emits the [TabInitializer] for normal operation mode.
     */
    private fun initializeRegularMode(initialUrl: String?, activity: Activity): Observable<TabInitializer> =
        restorePreviousTabs()
            .concatWith(Maybe.fromCallable<TabInitializer> {
                return@fromCallable initialUrl?.let {
                    if (it.isFileUrl()) {
                        PermissionInitializer(it, activity, homePageInitializer)
                    } else {
                        UrlInitializer(it)
                    }
                }
            })
            .defaultIfEmpty(homePageInitializer)

    /**
     * Returns the URL [intent] asks the browser to open, or null when
     * it carries nothing — or nothing the browser may load. Extraction
     * and the scheme allowlist live in [IncomingIntents] so the
     * cold-start path here and the warm `onNewIntent` path validate
     * incoming intents identically (SEC09).
     */
    fun extractUrlFromIntent(intent: Intent?): String? =
        IncomingIntents.extractUrl(
            intent,
            "${searchEngineProvider.provideSearchEngine().queryUrl}$QUERY_PLACE_HOLDER"
        ) {
            logger.log(TAG, "Rejected intent URL with disallowed scheme: $it")
        }

    /**
     * Returns an observable that emits the [TabInitializer] for each previously opened tab as
     * saved on disk. Can potentially be empty.
     */
    private fun restorePreviousTabs(): Observable<TabInitializer> = readSavedStateFromDisk()
        .map { bundle ->
            return@map bundle.getString(URL_KEY)?.let { url ->
                when {
                    url.isBookmarkUrl() -> bookmarkPageInitializer
                    url.isDownloadsUrl() -> downloadPageInitializer
                    url.isStartPageUrl() -> homePageInitializer
                    url.isHistoryUrl() -> historyPageInitializer
                    else -> UrlInitializer(url)
                }
            } ?: BundleInitializer(bundle)
        }


    /**
     * Method used to resume all the tabs in the browser. This is necessary because we cannot pause
     * the WebView when the application is open currently due to a bug in the WebView, where calling
     * onResume doesn't consistently resume it.
     */
    fun resumeAll() {
        currentTab?.resumeTimers()
        for (tab in tabList) {
            tab.onResume()
            tab.initializePreferences()
        }
    }

    /**
     * Method used to pause all the tabs in the browser. This is necessary because we cannot pause
     * the WebView when the application is open currently due to a bug in the WebView, where calling
     * onResume doesn't consistently resume it.
     */
    fun pauseAll() {
        currentTab?.pauseTimers()
        tabList.forEach(Page::onPause)
    }

    /**
     * Return the tab at the given position in tabs list, or null if position is not in tabs list
     * range.
     *
     * @param position the index in tabs list
     * @return the corespondent [Page], or null if the index is invalid
     */
    fun getTabAtPosition(position: Int): Page? =
        if (position < 0 || position >= tabList.size) {
            null
        } else {
            tabList[position]
        }

    val allTabs: List<Page>
        get() = tabList

    /**
     * Shutdown the manager. This destroys all tabs and clears the references to those tabs. Current
     * tab is also released for garbage collection.
     */
    fun shutdown() {
        repeat(tabList.size) { deleteTab(0) }
        isInitialized = false
        currentTab = null
    }

    /**
     * The current number of tabs in the manager.
     *
     * @return the number of tabs in the list.
     */
    fun size(): Int = tabList.size

    /**
     * The index of the last tab in the manager.
     *
     * @return the last tab in the list or -1 if there are no tabs.
     */
    fun last(): Int = tabList.size - 1


    /**
     * The last tab in the tab manager.
     *
     * @return the last tab, or null if there are no tabs.
     */
    fun lastTab(): Page? = tabList.lastOrNull()

    /**
     * Create and return a new tab. The tab is automatically added to the tabs list.
     *
     * @param activity the activity needed to create the tab.
     * @param tabInitializer the initializer to run on the tab after it's been created.
     * @param isIncognito whether the tab is an incognito tab or not.
     * @return a valid initialized tab.
     */
    fun newTab(
        activity: Activity,
        tabInitializer: TabInitializer,
        isIncognito: Boolean
    ): Page {
        logger.log(TAG, "New tab")
        val tab = newPage(activity, tabInitializer, isIncognito)
        tabList.add(tab)
        tabNumberListeners.forEach { it(size()) }
        return tab
    }

    /**
     * Create and return a new tab at a specific position. The tab is automatically added to the tabs list.
     *
     * @param activity the activity needed to create the tab.
     * @param tabInitializer the initializer to run on the tab after it's been created.
     * @param isIncognito whether the tab is an incognito tab or not.
     * @return a valid initialized tab.
     */
    fun newTabAtPosition(
            activity: Activity,
            tabInitializer: TabInitializer,
            isIncognito: Boolean,
            index: Int
    ): Page {
        logger.log(TAG, "New tab")
        val tab = newPage(activity, tabInitializer, isIncognito)
        tabList.add(index, tab)
        tabNumberListeners.forEach { it(size()) }
        return tab
    }

    /**
     * Creates a tab through the engine [Backend.createPage] seam — the
     * manager holds the neutral [Page] contract, so any backend's page
     * can be a tab (ENG03).
     */
    private fun newPage(
        activity: Activity,
        tabInitializer: TabInitializer,
        isIncognito: Boolean
    ): Page {
        val profile = Engines.backend.createProfile(
            ProfileOptions(storageName = null, offTheRecord = isIncognito)
        )
        return Engines.backend.createPage(
            PageRequest(activity = activity, profile = profile, initializer = tabInitializer)
        )
    }

    /**
     * Removes a tab from the list and destroys the tab. If the tab removed is the current tab, the
     * reference to the current tab will be nullified.
     *
     * @param position The position of the tab to remove.
     */
    private fun removeTab(position: Int) {
        if (position < 0 || position >= tabList.size) {
            return
        }
        val tab = tabList.removeAt(position)
        if (currentTab == tab) {
            currentTab = null
        }
        tab.onDestroy()
    }

    fun moveTab(from: Int, to: Int){
        val tab = tabList.get(from)
        tabList.removeAt(from)
        tabList.add(to, tab)

    }

    /**
     * Deletes a tab from the manager. If the tab being deleted is the current tab, this method will
     * switch the current tab to a new valid tab.
     *
     * @param position the position of the tab to delete.
     * @return returns true if the current tab was deleted, false otherwise.
     */
    fun deleteTab(position: Int): Boolean {
        logger.log(TAG, "Delete tab: $position")
        val currentTab = currentTab
        val current = positionOf(currentTab)

        if (current == position) {
            when {
                size() == 1 -> this.currentTab = null
                current < size() - 1 -> switchToTab(current + 1)
                else -> switchToTab(current - 1)
            }
        }

        removeTab(position)
        tabNumberListeners.forEach { it(size()) }
        return current == position
    }

    /**
     * Return the position of the given tab.
     *
     * @param tab the tab to look for.
     * @return the position of the tab or -1 if the tab is not in the list.
     */
    fun positionOf(tab: Page?): Int = tabList.indexOf(tab)

    /**
     * Saves the state of the current WebViews, to a bundle which is then stored in persistent
     * storage and can be unparceled.
     */
    fun saveState() {
        val outState = Bundle(ClassLoader.getSystemClassLoader())
        logger.log(TAG, "Saving tab state")
        tabList
            .filter { it.url.isNotBlank() }
            .withIndex()
            .forEach { (index, tab) ->
                if (!tab.url.isSpecialUrl()) {
                    outState.putBundle(BUNDLE_KEY + index, tab.saveState())
                } else {
                    outState.putBundle(BUNDLE_KEY + index, Bundle().apply {
                        putString(URL_KEY, tab.url)
                    })
                }
            }
        FileUtils.writeBundleToStorage(application, outState, BUNDLE_STORAGE)
            .subscribeOn(diskScheduler)
            .subscribeBy(onError = { logger.log(TAG, "Failed to save tab state", it) })
    }

    /**
     * Use this method to clear the saved state if you do not wish it to be restored when the
     * browser next starts.
     */
    fun clearSavedState() = FileUtils.deleteBundleInStorage(application, BUNDLE_STORAGE)

    /**
     * Creates an [Observable] that emits the [Bundle] state stored for each previously opened tab
     * on disk. After the list of bundle [Bundle] is read off disk, the old state will be deleted.
     * Can potentially be empty.
     */
    private fun readSavedStateFromDisk(): Observable<Bundle> = Maybe
        .fromCallable { FileUtils.readBundleFromStorage(application, BUNDLE_STORAGE) }
        .flattenAsObservable { bundle ->
            bundle.keySet()
                .filter { it.startsWith(BUNDLE_KEY) }
                // Bundle is backed by ArrayMap, which iterates in
                // key-hash order — sort explicitly or tabs restore
                // in a scrambled order once there are >9 of them.
                .sortedBy { it.removePrefix(BUNDLE_KEY).toIntOrNull() ?: Int.MAX_VALUE }
                .mapNotNull(bundle::getBundle)
        }
        .doOnNext { logger.log(TAG, "Restoring previous WebView state now") }

    /**
     * Returns the index of the current tab.
     *
     * @return Return the index of the current tab, or -1 if the current tab is null.
     */
    fun indexOfCurrentTab(): Int = tabList.indexOf(currentTab)

    /**
     * Returns the index of the tab.
     *
     * @return Return the index of the tab, or -1 if the tab isn't in the list.
     */
    fun indexOfTab(tab: Page): Int = tabList.indexOf(tab)

    /**
     * Returns the [Page] with the provided hash, or null if there is no tab with the hash.
     *
     * @param hashCode the hashcode.
     * @return the tab with an identical hash, or null.
     */
    fun getTabForHashCode(hashCode: Int): Page? =
        tabList.firstOrNull { lightningView -> lightningView.hashCode() == hashCode }

    /**
     * Switch the current tab to the one at the given position. It returns the selected tab that has
     * been switched to.
     *
     * @return the selected tab or null if position is out of tabs range.
     */
    fun switchToTab(position: Int): Page? {
        logger.log(TAG, "switch to tab: $position")
        return if (position < 0 || position >= tabList.size) {
            logger.log(TAG, "Returning a null LightningView requested for position: $position")
            null
        } else {
            tabList[position].also {
                currentTab = it
                if(it.favicon == null && it.sslCertificate == null && !it.url.contains(":///") && !userPreferences.allTabs){
                    it.reload()
                }
            }
        }
    }

    companion object {

        private const val TAG = "TabsManager"

        private const val BUNDLE_KEY = "WEBVIEW_"
        internal const val URL_KEY = "URL_KEY"
        private const val BUNDLE_STORAGE = "SAVED_TABS.parcel"
    }

}
