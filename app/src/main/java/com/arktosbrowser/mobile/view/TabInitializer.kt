// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.view

import android.app.Activity
import android.os.Bundle
import android.os.Message
import androidx.appcompat.app.AlertDialog
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.browser.TabsManager
import com.arktosbrowser.mobile.constant.SCHEME_BOOKMARKS
import com.arktosbrowser.mobile.constant.SCHEME_HOMEPAGE
import com.arktosbrowser.mobile.di.DiskScheduler
import com.arktosbrowser.mobile.di.MainScheduler
import com.arktosbrowser.mobile.engine.Page
import com.arktosbrowser.mobile.engine.PageInitializer
import com.arktosbrowser.mobile.extensions.resizeAndShow
import com.arktosbrowser.mobile.html.HtmlPageFactory
import com.arktosbrowser.mobile.html.bookmark.BookmarkPageFactory
import com.arktosbrowser.mobile.html.download.DownloadPageFactory
import com.arktosbrowser.mobile.html.history.HistoryPageFactory
import com.arktosbrowser.mobile.html.homepage.HomePageFactory
import com.arktosbrowser.mobile.html.incognito.IncognitoPageFactory
import com.arktosbrowser.mobile.preference.UserPreferences
import dagger.Reusable
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.kotlin.subscribeBy
import javax.inject.Inject

/**
 * An initializer that is run on a [ArktosCookieView] after it is
 * created. All initializers implement the engine-neutral
 * [PageInitializer] contract — they see the [Page] interface, not the
 * underlying WebView.
 */
typealias TabInitializer = PageInitializer

/**
 * An initializer that loads a [url].
 */
class UrlInitializer(private val url: String) : TabInitializer {

    override fun initialize(page: Page, headers: Map<String, String>) {
        page.load(url, headers)
    }

}

/**
 * An initializer that displays the page set as the user's homepage preference.
 */
@Reusable
class HomePageInitializer @Inject constructor(
    private val userPreferences: UserPreferences,
    private val startPageInitializer: StartPageInitializer,
    private val bookmarkPageInitializer: BookmarkPageInitializer
) : TabInitializer {

    override fun initialize(page: Page, headers: Map<String, String>) {
        val homepage = userPreferences.homepage

        when (homepage) {
            SCHEME_HOMEPAGE -> startPageInitializer
            SCHEME_BOOKMARKS -> bookmarkPageInitializer
            else -> UrlInitializer(homepage)
        }.initialize(page, headers)
    }

}

/**
 * An initializer that displays the page set as the user's incognito homepage preference.
 */
@Reusable
class IncognitoPageInitializer @Inject constructor(
        private val userPreferences: UserPreferences,
        private val startIncognitoPageInitializer: StartIncognitoPageInitializer,
        private val bookmarkPageInitializer: BookmarkPageInitializer
) : TabInitializer {

    override fun initialize(page: Page, headers: Map<String, String>) {
        val homepage = userPreferences.homepage

        when (homepage) {
            SCHEME_HOMEPAGE -> startIncognitoPageInitializer
            SCHEME_BOOKMARKS -> bookmarkPageInitializer
            else -> UrlInitializer(homepage)
        }.initialize(page, headers)
    }

}

/**
 * An initializer that displays the start page.
 */
@Reusable
class StartPageInitializer @Inject constructor(
    homePageFactory: HomePageFactory,
    @DiskScheduler diskScheduler: Scheduler,
    @MainScheduler foregroundScheduler: Scheduler
) : HtmlPageFactoryInitializer(homePageFactory, diskScheduler, foregroundScheduler)

/**
 * An initializer that displays the start incognito page.
 */
@Reusable
class StartIncognitoPageInitializer @Inject constructor(
        incognitoPageFactory: IncognitoPageFactory,
        @DiskScheduler diskScheduler: Scheduler,
        @MainScheduler foregroundScheduler: Scheduler
) : HtmlPageFactoryInitializer(incognitoPageFactory, diskScheduler, foregroundScheduler)

/**
 * An initializer that displays the bookmark page.
 */
@Reusable
class BookmarkPageInitializer @Inject constructor(
    bookmarkPageFactory: BookmarkPageFactory,
    @DiskScheduler diskScheduler: Scheduler,
    @MainScheduler foregroundScheduler: Scheduler
) : HtmlPageFactoryInitializer(bookmarkPageFactory, diskScheduler, foregroundScheduler)

/**
 * An initializer that displays the download page.
 */
@Reusable
class DownloadPageInitializer @Inject constructor(
    downloadPageFactory: DownloadPageFactory,
    @DiskScheduler diskScheduler: Scheduler,
    @MainScheduler foregroundScheduler: Scheduler
) : HtmlPageFactoryInitializer(downloadPageFactory, diskScheduler, foregroundScheduler)

/**
 * An initializer that displays the history page.
 */
@Reusable
class HistoryPageInitializer @Inject constructor(
    historyPageFactory: HistoryPageFactory,
    @DiskScheduler diskScheduler: Scheduler,
    @MainScheduler foregroundScheduler: Scheduler
) : HtmlPageFactoryInitializer(historyPageFactory, diskScheduler, foregroundScheduler)

/**
 * An initializer that loads the url built by the [HtmlPageFactory].
 */
abstract class HtmlPageFactoryInitializer(
    private val htmlPageFactory: HtmlPageFactory,
    @DiskScheduler private val diskScheduler: Scheduler,
    @MainScheduler private val foregroundScheduler: Scheduler
) : TabInitializer {

    override fun initialize(page: Page, headers: Map<String, String>) {
        htmlPageFactory
            .buildPage()
            .subscribeOn(diskScheduler)
            .observeOn(foregroundScheduler)
            .subscribeBy(onSuccess = { page.load(it, headers) })
    }

}

/**
 * An initializer that sets the page's backing WebView as the target of
 * the [resultMessage]. Used for `target="_blank"` links.
 */
class ResultMessageInitializer(private val resultMessage: Message) : TabInitializer {

    override fun initialize(page: Page, headers: Map<String, String>) {
        // Checked again here (onCreateWindow pre-validates): a missing or wrong
        // payload must abort tab creation so the caller can decline the window
        // request instead of crashing on the cast (upstream #306).
        val transport = resultMessage.obj as? android.webkit.WebView.WebViewTransport
            ?: throw IllegalArgumentException("New-window message has no WebViewTransport payload")
        transport.webView = page.asWebView()
        resultMessage.sendToTarget()
    }

}

/**
 * An initializer that restores the page state using the [bundle]. Bundles holding a saved URL
 * instead of serialized engine state (e.g. generated error pages, which commit as `data:` URIs)
 * load that URL directly.
 */
class BundleInitializer(private val bundle: Bundle) : TabInitializer {

    override fun initialize(page: Page, headers: Map<String, String>) {
        // Engine state first — it carries scroll + history; the saved
        // URL is the fallback when the bundle holds no usable state or
        // its Parcelable can't unmarshal.
        if (!page.restoreState(bundle)) {
            bundle.getString(TabsManager.URL_KEY)?.let { page.load(it, headers) }
        }
    }

}

/**
 * An initializer that does not load anything into the page.
 */
class NoOpInitializer : TabInitializer {

    override fun initialize(page: Page, headers: Map<String, String>) = Unit

}

/**
 * Ask the user's permission before loading the [url] and load the homepage instead if they deny
 * permission. Useful for scenarios where another app may attempt to open a malicious URL in the
 * browser via an intent.
 */
class PermissionInitializer(
    private val url: String,
    private val activity: Activity,
    private val homePageInitializer: HomePageInitializer
) : TabInitializer {

    override fun initialize(page: Page, headers: Map<String, String>) {
        AlertDialog.Builder(activity).apply {
            setTitle(R.string.title_warning)
            setMessage(R.string.message_blocked_local)
            setCancelable(false)
            setOnDismissListener {
                homePageInitializer.initialize(page, headers)
            }
            setNegativeButton(android.R.string.cancel, null)
            setPositiveButton(R.string.action_open) { _, _ ->
                UrlInitializer(url).initialize(page, headers)
            }
        }.resizeAndShow()
    }

}
