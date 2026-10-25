// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.browser

import android.app.Activity
import android.content.Intent
import com.arktosbrowser.mobile.BuildConfig
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.constant.*
import com.arktosbrowser.mobile.di.MainScheduler
import com.arktosbrowser.mobile.engine.Engines
import com.arktosbrowser.mobile.engine.Page
import com.arktosbrowser.mobile.html.ChangelogPage
import com.arktosbrowser.mobile.html.bookmark.BookmarkPageFactory
import com.arktosbrowser.mobile.html.homepage.HomePageFactory
import com.arktosbrowser.mobile.html.incognito.IncognitoPageFactory
import com.arktosbrowser.mobile.log.Logger
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.rust.ArktosCore
import com.arktosbrowser.mobile.ssl.SslState
import com.arktosbrowser.mobile.update.UpdateChecker
import com.arktosbrowser.mobile.utils.isFileUrl
import com.arktosbrowser.mobile.view.BundleInitializer
import com.arktosbrowser.mobile.view.TabInitializer
import com.arktosbrowser.mobile.view.UrlInitializer
import com.arktosbrowser.mobile.engine.FindResults
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.kotlin.subscribeBy

/**
 * Presenter in charge of keeping track of the current tab and setting the current tab of the
 * browser.
 */
class BrowserPresenter(
        private val view: BrowserView,
        private val isIncognito: Boolean,
        private val userPreferences: UserPreferences,
        private val tabsModel: TabsManager,
        @MainScheduler private val mainScheduler: Scheduler,
        private val homePageFactory: HomePageFactory,
        private val incognitoPageFactory: IncognitoPageFactory,
        private val bookmarkPageFactory: BookmarkPageFactory,
        private val recentTabModel: RecentTabModel,
        private val logger: Logger,
        private val updateChecker: UpdateChecker
) {

    private var currentTab: Page? = null
    private var shouldClose: Boolean = false
    private var intented: Page? = null
    private var sslStateSubscription: Disposable? = null
    private var updateCheckDisposable: Disposable? = null

    init {
        tabsModel.addTabNumberChangedListener(view::updateTabNumber)
    }

    /**
     * Initializes the tab manager with the new intent that is handed in by the BrowserActivity.
     *
     * @param intent the intent to handle, may be null.
     */
    fun setupTabs(intent: Intent?) {
        tabsModel.initializeTabs(view as Activity, intent, isIncognito)
            .subscribeBy(
                onSuccess = {
                    // At this point we always have at least a tab in the tab manager
                    view.notifyTabViewInitialized()
                    view.updateTabNumber(tabsModel.size())
                    tabChanged(tabsModel.positionOf(it))
                    openChangelogOncePerUpgrade(intent)
                    checkForUpdates()
                },
                onError = {
                    // Corrupt saved state or an initializer failure must not
                    // leave the browser with zero tabs — fall back to a fresh
                    // homepage tab so the app stays usable.
                    logger.log(TAG, "Failed to initialize tabs", it)
                    if (tabsModel.size() == 0) {
                        newTab(
                            if (isIncognito) {
                                UrlInitializer("$FILE${incognitoPageFactory.createIncognitoPage()}")
                            } else {
                                UrlInitializer(mapHomepageToCurrentUrl())
                            },
                            true
                        )
                    }
                    view.notifyTabViewInitialized()
                    view.updateTabNumber(tabsModel.size())
                }
            )

    }

    /**
     * CHLOG01 — one-shot "what's new": after a versionCode bump, open
     * the changelog once in a new tab — foreground when the launch
     * carried no external URL, background when an intent link is being
     * opened. Regular mode only: the incognito process must not race
     * the SharedPreferences write that marks the version seen.
     */
    private fun openChangelogOncePerUpgrade(intent: Intent?) {
        if (isIncognito || !userPreferences.whatsNewEnabled) {
            return
        }
        if (!ChangelogGate.shouldShow(userPreferences.lastChangelogVersionCode, BuildConfig.VERSION_CODE)) {
            return
        }
        userPreferences.lastChangelogVersionCode = BuildConfig.VERSION_CODE
        if (tabsModel.allTabs.any { it.url == ChangelogPage.URL }) {
            // Restored session already carries the changelog tab.
            return
        }
        val hadIntentUrl = tabsModel.extractUrlFromIntent(intent) != null
        newTab(UrlInitializer(ChangelogPage.URL), !hadIntentUrl)
    }

    /**
     * UPDT01 — the daily GitHub-release check. Announces a newer tag
     * once in a dialog ("download" opens the release page in a tab);
     * a dismissed tag doesn't re-nag, and incognito never checks.
     */
    private fun checkForUpdates() {
        if (isIncognito || !userPreferences.updateCheckEnabled) {
            return
        }
        val now = (System.currentTimeMillis() / 1000).toInt()
        if (now - userPreferences.lastUpdateCheckAt < DAY_SECONDS) {
            return
        }
        userPreferences.lastUpdateCheckAt = now
        updateChecker.check()
            .observeOn(mainScheduler)
            .subscribeBy(
                onSuccess = { info ->
                    if (info.tag != userPreferences.dismissedUpdateTag) {
                        showUpdateDialog(info)
                    }
                },
                onError = { logger.log(TAG, "Update check failed", it) }
            )
            .let { updateCheckDisposable = it }
    }

    private fun showUpdateDialog(info: UpdateChecker.UpdateInfo) {
        val activity = view as? Activity ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_available_title, info.version))
            .setMessage(R.string.update_available_message)
            .setPositiveButton(R.string.action_download) { _, _ ->
                userPreferences.dismissedUpdateTag = info.tag
                newTab(UrlInitializer(info.url), true)
            }
            .setNegativeButton(R.string.action_ok) { _, _ ->
                userPreferences.dismissedUpdateTag = info.tag
            }
            .show()
    }

    /**
     * Notify the presenter that a change occurred to the current tab. Currently doesn't do anything
     * other than tell the view to notify the adapter about the change.
     *
     * @param tab the tab that changed, may be null.
     */
    fun tabChangeOccurred(tab: Page?) = tab?.let {
        view.notifyTabViewChanged(tabsModel.indexOfTab(it))
    }

    private fun onTabChanged(newTab: Page?) {
        logger.log(TAG, "On tab changed")
        view.updateSslState(newTab?.currentSslState() ?: SslState.None)

        sslStateSubscription?.dispose()
        sslStateSubscription = newTab
            ?.sslStateObservable()
            ?.observeOn(mainScheduler)
            ?.subscribe(view::updateSslState)

        val engineView = newTab?.let { Engines.backend.createView(it) }

        if (newTab == null) {
            view.removeTabView()
            currentTab?.let {
                it.pauseTimers()
                it.onDestroy()
            }
        } else {
            if (engineView == null) {
                view.removeTabView()
                currentTab?.let {
                    it.pauseTimers()
                    it.onDestroy()
                }
            } else {
                currentTab.let {
                    // TODO: Restore this when Google fixes the bug where the WebView is
                    // blank after calling onPause followed by onResume.
                    // currentTab.onPause();
                    it?.isForegroundTab = false
                }

                newTab.resumeTimers()
                newTab.onResume()
                newTab.isForegroundTab = true

                view.updateProgress(newTab.progress)
                view.setBackButtonEnabled(newTab.canGoBack)
                view.setForwardButtonEnabled(newTab.canGoForward)
                view.updateUrl(newTab.url, false)
                view.setTabView(engineView)
                val index = tabsModel.indexOfTab(newTab)
                if (index >= 0) {
                    view.notifyTabViewChanged(tabsModel.indexOfTab(newTab))
                }
            }
        }

        currentTab = newTab
    }

    /**
     * Closes all tabs but the current tab.
     */
    fun closeAllOtherTabs(keepAlive: Boolean = false) {

        while (tabsModel.last() != tabsModel.indexOfCurrentTab()) {
            deleteTab(tabsModel.last(), keepAlive = keepAlive)
        }

        while (0 != tabsModel.indexOfCurrentTab()) {
            deleteTab(0, keepAlive = keepAlive)
        }

    }

    /**
     * Closes every tab. With [keepAlive] the browser is never closed —
     * the last delete leaves a fresh default tab behind.
     */
    fun closeAllTabs(keepAlive: Boolean = false) {
        repeat(tabsModel.size()) {
            deleteTab(0, keepAlive = keepAlive)
        }
    }

    private fun mapHomepageToCurrentUrl(): String = when (val homepage = userPreferences.homepage) {
        SCHEME_HOMEPAGE -> "$FILE${homePageFactory.createHomePage()}"
        SCHEME_INCOGNITO -> "$FILE${incognitoPageFactory.createIncognitoPage()}"
        SCHEME_BOOKMARKS -> "$FILE${bookmarkPageFactory.createBookmarkPage(null)}"
        else -> homepage
    }

    /**
     * Deletes the tab at the specified position.
     *
     * @param position the position at which to delete the tab.
     */
    fun deleteTab(position: Int, back: Boolean = false, keepAlive: Boolean = false) {
        logger.log(TAG, "deleting tab...")
        val tabToDelete = tabsModel.getTabAtPosition(position) ?: return

        recentTabModel.addClosedTab(tabToDelete.saveState())

        if(!back && tabToDelete.isNewTab) tabToDelete.isNewTab = false

        val isShown = tabToDelete.isShown
        val shouldClose = shouldClose && isShown && tabToDelete.isNewTab || intented == currentTab && back && isShown
        val currentTab = tabsModel.currentTab

        if (tabsModel.size() == 1
            && currentTab != null
            && currentTab.url.isFileUrl()
            && currentTab.url == mapHomepageToCurrentUrl()
            && userPreferences.closeOnLastTab
            && !keepAlive) {
            view.closeActivity()
            return
        } else {
            if (isShown) {
                view.removeTabView()
            }
            val currentDeleted = tabsModel.deleteTab(position)
            if (currentDeleted) {
                tabChanged(tabsModel.indexOfCurrentTab())
            }
        }

        val afterTab = tabsModel.currentTab
        view.notifyTabViewRemoved(position)

        if (afterTab == null) {
            if(userPreferences.closeOnLastTab && !keepAlive){
                view.closeBrowser()
            }
            else{
                newTab(UrlInitializer(if (isIncognito) "$FILE${incognitoPageFactory.createIncognitoPage()}" else mapHomepageToCurrentUrl()), true)
            }
            return
        } else if (afterTab !== currentTab) {
            view.notifyTabViewChanged(tabsModel.indexOfCurrentTab())
        }

        if (shouldClose && !isIncognito && !keepAlive) {
            this.shouldClose = false
            view.closeActivity()
        }

        view.updateTabNumber(tabsModel.size())

        logger.log(TAG, "...deleted tab")
    }

    /**
     * Handle a new intent from the the main BrowserActivity.
     *
     * @param intent the intent to handle, may be null.
     */
    fun onNewIntent(intent: Intent?) = tabsModel.doAfterInitialization {
        // SEC09 — extraction + scheme allowlist live in IncomingIntents
        // via TabsManager, shared with the cold-start initializeTabs
        // path so both entry points validate identically.
        val url = tabsModel.extractUrlFromIntent(intent)
        val tabHashCode = intent?.extras?.getInt(INTENT_ORIGIN, 0) ?: 0
        if (tabHashCode != 0 && url != null) {
            tabsModel.getTabForHashCode(tabHashCode)?.loadUrl(url)
        } else if (url != null) {
            if (url.isFileUrl()) {
                intented = currentTab
                view.showBlockedLocalFileDialog {
                    newTab(UrlInitializer(url), true)
                    shouldClose = true
                    tabsModel.lastTab()?.isNewTab = true
                }
            } else {
                intented = currentTab
                newTab(UrlInitializer(url), true)
                shouldClose = true
                tabsModel.lastTab()?.isNewTab = true
            }
        }
    }

    /**
     * Call when the user long presses the new tab button.
     */
    fun onNewTabLongClicked() {
        recentTabModel.lastClosed()?.let {
            newTab(BundleInitializer(it), true)
            view.showSnackbar(R.string.reopening_recent_tab)
        }
    }

    /**
     * Loads a URL in the current tab.
     *
     * @param url the URL to load, must not be null.
     */
    fun loadUrlInCurrentView(url: String) {
        tabsModel.currentTab?.loadUrl(url)
    }

    /**
     * Notifies the presenter that it should shut down. This should be called when the
     * BrowserActivity is destroyed so that we don't leak any memory.
     */
    fun shutdown() {
        //TODO: Fix a crash when this is called!
        //onTabChanged(null)
        tabsModel.cancelPendingWork()
        sslStateSubscription?.dispose()
        updateCheckDisposable?.dispose()
    }

    /**
     * Notifies the presenter that we wish to switch to a different tab at the specified position.
     * If the position is not in the model, this method will do nothing.
     *
     * @param position the position of the tab to switch to.
     */
    fun tabChanged(position: Int) {
        if (position < 0 || position >= tabsModel.size()) {
            logger.log(TAG, "tabChanged invalid position: $position")
            return
        }

        logger.log(TAG, "tabChanged: $position")
        onTabChanged(tabsModel.switchToTab(position))
    }

    /**
+     * Open a new tab with the specified URL. You can choose to show the tab or load it in the
     * background.
     *
     * @param tabInitializer the tab initializer to run after the tab as been created.
     * @param show whether or not to switch to this tab after opening it.
     * @return true if we successfully created the tab.
     */
    fun newTab(tabInitializer: TabInitializer, show: Boolean): Boolean {


        logger.log(TAG, "New tab, show: $show")

        val startingTab = tabsModel.newTab(view as Activity, tabInitializer, isIncognito)
        if (tabsModel.size() == 1) {
            startingTab.resumeTimers()
        }

        view.notifyTabViewAdded()
        view.updateTabNumber(tabsModel.size())

        if (show) {
            onTabChanged(tabsModel.switchToTab(tabsModel.last()))
        }

        return true
    }

    /**
    +     * Open a new tab with the specified URL at a specific index. You can choose to show the tab or load it in the
     * background.
     *
     * @param tabInitializer the tab initializer to run after the tab as been created.
     * @param show whether or not to switch to this tab after opening it.
     * @return true if we successfully created the tab.
     */
    fun newTabAtPosition(tabInitializer: TabInitializer, show: Boolean, index: Int): Boolean {


        logger.log(TAG, "New tab, show: $show")

        val startingTab = tabsModel.newTabAtPosition(view as Activity, tabInitializer, isIncognito, index)
        if (tabsModel.size() == 1) {
            startingTab.resumeTimers()
        }

        view.notifyTabViewAdded()
        view.updateTabNumber(tabsModel.size())

        if (show) {
            onTabChanged(tabsModel.switchToTab(index))
        }

        return true
    }

    fun onAutoCompleteItemPressed() {
        tabsModel.currentTab?.requestFocus()
    }

    fun findInPage(query: String): FindResults? {
        return tabsModel.currentTab?.find(query)
    }

    companion object {
        private const val TAG = "BrowserPresenter"
        private const val DAY_SECONDS = 24 * 60 * 60

        // Scheme allowlist lives in the Rust core (PORT02).
        internal fun isIntentUrlAllowed(url: String): Boolean =
            ArktosCore.isIntentUrlAllowed(url)
    }

}
