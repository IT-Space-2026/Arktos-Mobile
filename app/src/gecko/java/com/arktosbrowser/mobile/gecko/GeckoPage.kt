package com.arktosbrowser.mobile.gecko

import android.app.Activity
import android.graphics.Bitmap
import android.net.http.SslCertificate
import android.os.Bundle
import android.view.View
import com.arktosbrowser.mobile.engine.CertificateErrorAction
import com.arktosbrowser.mobile.engine.CertificateErrorInfo
import com.arktosbrowser.mobile.engine.ContextMenuInfo
import com.arktosbrowser.mobile.engine.DownloadRequest
import com.arktosbrowser.mobile.engine.FindResults
import com.arktosbrowser.mobile.engine.HistoryEntry
import com.arktosbrowser.mobile.engine.LifecycleState
import com.arktosbrowser.mobile.engine.LoadStatus
import com.arktosbrowser.mobile.engine.NavigationRequest
import com.arktosbrowser.mobile.engine.NavigationType
import com.arktosbrowser.mobile.engine.Page
import com.arktosbrowser.mobile.engine.PageAttributes
import com.arktosbrowser.mobile.engine.PageInitializer
import com.arktosbrowser.mobile.engine.RequestAction
import com.arktosbrowser.mobile.engine.ResourceType
import com.arktosbrowser.mobile.engine.UserScript
import com.arktosbrowser.mobile.log.Logger
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.search.SafeSearch
import com.arktosbrowser.mobile.ssl.SslState
import com.arktosbrowser.mobile.view.BookmarkPageInitializer
import com.arktosbrowser.mobile.view.DownloadPageInitializer
import com.arktosbrowser.mobile.view.HistoryPageInitializer
import com.arktosbrowser.mobile.view.HomePageInitializer
import com.arktosbrowser.mobile.view.IncognitoPageInitializer
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.PublishSubject
import java.nio.charset.Charset
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebResponse

/**
 * A tab on the GeckoView backend — a [GeckoSession] + a lazily created
 * [GeckoView] behind the [Page] contract (ENG03 skeleton).
 *
 * Working surface: load/loadData, stop/reload, back/forward/history
 * nav, per-page JS toggle, find-in-page, private-mode storage, and the
 * progress/title/url/security signals reaching the shell through the
 * `Page` callbacks. `Profile.requestPolicy` is consulted on
 * main-frame navigations (Allow/Deny/Redirect).
 *
 * Stubs (feature gaps, tracked by ENG04/ENG05): favicons (GeckoView
 * has no favicon delegate), `SslCertificate`/`SslState.Invalid` (no
 * WebView-style cert object), the cookie get/set surface, downloads
 * (the signal fires; the DownloadManager handoff is unimplemented),
 * JS evaluation + userscript injection (moves to WebExtensions),
 * printing, context-menu/permission/cert-error UX wiring, and the
 * proxy settings.
 */
class GeckoPage(
    private val activity: Activity,
    private val engine: GeckoEngine,
    private val profile: GeckoProfile,
    private val initializer: PageInitializer,
    private val homePageInitializer: HomePageInitializer,
    private val incognitoPageInitializer: IncognitoPageInitializer,
    private val bookmarkPageInitializer: BookmarkPageInitializer,
    private val downloadPageInitializer: DownloadPageInitializer,
    private val historyPageInitializer: HistoryPageInitializer,
    private val userPreferences: UserPreferences,
    private val logger: Logger
) : Page {

    override val id = View.generateViewId()

    internal val session: GeckoSession

    // The display widget — created lazily by ensureView() so background
    // tabs run headless; released on destroy.
    internal var view: GeckoView? = null
        private set

    private var currentUrl = ""
    private var currentTitle = ""
    private var backable = false
    private var forwardable = false
    private var loading = false
    private var pageProgress = 100
    private var sslState: SslState = SslState.None
    private var latestSessionState: GeckoSession.SessionState? = null
    private var latestHistory: GeckoSession.HistoryDelegate.HistoryList? = null
    private val sslSubject = PublishSubject.create<SslState>()
    private val pageScripts = mutableListOf<UserScript>()

    init {
        session = GeckoSession(
            GeckoSessionSettings.Builder()
                .usePrivateMode(profile.isOffTheRecord)
                .apply {
                    profile.contextId()?.let { contextId(it) }
                    if (profile.userAgent.isNotEmpty()) {
                        userAgentOverride(profile.userAgent)
                    }
                }
                .build()
        )
        session.open(engine.runtime)
        session.navigationDelegate = GeckoNavigationDelegate()
        session.progressDelegate = GeckoProgressDelegate()
        session.contentDelegate = GeckoContentDelegate()
        session.historyDelegate = GeckoHistoryDelegate()
        initializePreferences()
        initializer.initialize(this, emptyMap())
    }

    /** The widget the shell adds to the layout for this page. */
    internal fun ensureView(): GeckoView =
        view ?: GeckoView(activity).also {
            it.setSession(session)
            view = it
        }

    // ---- load / navigation ---------------------------------------------

    override fun load(url: String, headers: Map<String, String>) {
        if (headers.isEmpty()) {
            session.loadUri(url)
        } else {
            session.load(
                GeckoSession.Loader()
                    .uri(url)
                    .additionalHeaders(headers)
                    .headerFilter(GeckoSession.HEADER_FILTER_UNRESTRICTED_UNSAFE)
            )
        }
    }

    override fun loadData(
        baseUrl: String?,
        data: String,
        mimeType: String,
        encoding: String,
        historyUrl: String?
    ) {
        // Loader.data has no base/history URL — pages needing relative
        // resource resolution are a parity gap (ENG04).
        val charset = runCatching { Charset.forName(encoding) }.getOrNull()
            ?: Charsets.UTF_8
        session.load(GeckoSession.Loader().data(data.toByteArray(charset), mimeType))
    }

    override fun stop() = session.stop()

    override fun reload() = session.reload()

    override val url: String
        get() = currentUrl

    override val canGoBack: Boolean
        get() = backable

    override val canGoForward: Boolean
        get() = forwardable

    override fun back() = session.goBack()

    override fun forward() = session.goForward()

    override fun historyItems(): List<HistoryEntry> =
        latestHistory?.mapIndexed { index, item ->
            HistoryEntry(index, item.uri.orEmpty(), item.title.orEmpty(), 0L)
        } ?: emptyList()

    override fun goToHistoryEntry(entry: HistoryEntry) {
        session.gotoHistoryIndex(entry.index)
    }

    override fun saveState(): Bundle {
        val state = latestSessionState
        return Bundle(GeckoSession.SessionState::class.java.classLoader).also {
            if (state != null) {
                it.putParcelable(STATE_KEY, state)
            }
        }
    }

    override fun restoreState(state: Bundle) {
        // SessionState is a GeckoView Parcelable — the bundle needs this
        // class's loader to unparcel it.
        state.classLoader = GeckoSession.SessionState::class.java.classLoader
        @Suppress("DEPRECATION")
        val sessionState = state.getParcelable<GeckoSession.SessionState>(STATE_KEY)
        if (sessionState != null) {
            session.restoreState(sessionState)
        }
    }

    // GeckoView's surface for this is font inflation / display density —
    // stored for the shell; engine application is a parity gap (ENG05).
    override var zoomFactor: Float = 1f

    // No scroll-offset surface exposed — gap.
    override val scrollPosition: Pair<Float, Float>
        get() = Pair(0f, 0f)

    override fun findText(subString: String, backwards: Boolean) {
        session.finder.find(
            subString,
            if (backwards) {
                GeckoSession.FINDER_FIND_BACKWARDS
            } else {
                GeckoSession.FINDER_FIND_FORWARD
            }
        )
    }

    override fun runJavaScript(source: String, callback: (String?) -> Unit) {
        // GeckoView has no evaluateJavascript — the userscript/JS-eval
        // surface moves to WebExtensions (ENG05). Always reply so
        // callers can't stall.
        callback(null)
    }

    override fun insertScript(script: UserScript) {
        pageScripts.removeAll { it.name == script.name }
        pageScripts += script
    }

    override fun removeScript(name: String) {
        pageScripts.removeAll { it.name == name }
    }

    override fun scripts(): List<UserScript> = pageScripts.toList()

    override fun download(url: String) {
        // The onExternalResponse delegate decides whether the response
        // is delivered as a download.
        session.loadUri(url)
    }

    override fun clearCache() {
        engine.runtime.storageController.clearData(StorageController.ClearFlags.ALL_CACHES)
    }

    override val isLoading: Boolean
        get() = loading

    // GeckoView exposes no audible flag — gap.
    override val recentlyAudible: Boolean
        get() = false

    override val isOffTheRecord: Boolean
        get() = profile.isOffTheRecord

    override fun setPageAttribute(name: String, on: Boolean) {
        when (name) {
            PageAttributes.JAVASCRIPT_ENABLED -> session.settings.setAllowJavascript(on)
            else -> Unit
        }
    }

    override fun setLifecycleState(state: LifecycleState) {
        lifecycleState = state
        session.setActive(state == LifecycleState.Active)
    }

    override var lifecycleState: LifecycleState = LifecycleState.Active
        private set

    // GeckoView exposes no renderer PID.
    override fun renderProcessId(): Long = -1L

    // ---- signals --------------------------------------------------------

    override var onLoadStatusChanged: ((LoadStatus) -> Unit)? = null
    override var onUrlChanged: ((String) -> Unit)? = null
    override var onTitleChanged: ((String) -> Unit)? = null
    override var onProgressChanged: ((Int) -> Unit)? = null
    override var onFaviconChanged: (() -> Unit)? = null
    override var onDownloadRequested: ((DownloadRequest) -> Unit)? = null
    override var onCertificateError: ((CertificateErrorInfo) -> CertificateErrorAction)? = null
    override var onPermissionRequest: ((String) -> Boolean)? = null
    override var onContextMenu: ((ContextMenuInfo) -> Unit)? = null
    override var onCrashed: (() -> Unit)? = null

    // ---- shell-facing tab surface ----------------------------------------

    override var isForegroundTab: Boolean = false
    override var isNewTab: Boolean = false

    override val title: String
        get() = currentTitle

    // GeckoView has no favicon delegate — tab icons come from a fetch
    // service (ENG04/05 gap).
    override val favicon: Bitmap?
        get() = null

    // No WebView-style certificate object — gap.
    override val sslCertificate: SslCertificate?
        get() = null

    override val isShown: Boolean
        get() = view?.isShown == true

    override val progress: Int
        get() = pageProgress

    override fun currentSslState(): SslState = sslState

    override fun sslStateObservable(): Observable<SslState> = sslSubject

    // Blocked-request counting rides the blocklist RequestPolicy — ENG04.
    override fun blockedThisPage(): Int = 0

    override fun initializePreferences() {
        session.settings.setAllowJavascript(userPreferences.javaScriptEnabled)
    }

    override fun onPause() = session.setActive(false)

    override fun onResume() = session.setActive(true)

    override fun onDestroy() {
        view?.releaseSession()
        view = null
        if (session.isOpen) {
            session.close()
        }
    }

    override fun pauseTimers() = session.setActive(false)

    override fun resumeTimers() = session.setActive(true)

    override fun requestFocus() {
        view?.requestFocus()
        session.setFocused(true)
    }

    override fun setVisibility(visible: Int) {
        view?.visibility = visible
    }

    override fun loadUrl(url: String) {
        // Forced SafeSearch (upstream #327) applies on the gecko flavor
        // too; the WebView-side proxy-readiness gate has no gecko
        // equivalent yet (ENG05).
        load(SafeSearch.enforcedUrl(url, userPreferences.safeSearchForced), emptyMap())
    }

    override fun loadHomePage() =
        reinitialize(
            if (profile.isOffTheRecord) {
                incognitoPageInitializer
            } else {
                homePageInitializer
            }
        )

    override fun loadBookmarkPage() = reinitialize(bookmarkPageInitializer)

    override fun loadDownloadsPage() = reinitialize(downloadPageInitializer)

    override fun loadHistoryPage() = reinitialize(historyPageInitializer)

    private fun reinitialize(pageInitializer: PageInitializer) =
        pageInitializer.initialize(this, emptyMap())

    private var desktopMode = false

    override fun toggleDesktopUA() {
        desktopMode = !desktopMode
        session.settings.setUserAgentMode(
            if (desktopMode) {
                GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
            } else {
                GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            }
        )
    }

    override fun find(text: String): FindResults {
        session.finder.find(text, GeckoSession.FINDER_FIND_FORWARD)
        return object : FindResults {
            override fun nextResult() {
                session.finder.find(text, GeckoSession.FINDER_FIND_FORWARD)
            }

            override fun previousResult() {
                session.finder.find(text, GeckoSession.FINDER_FIND_BACKWARDS)
            }

            override fun clearResults() {
                session.finder.clear()
            }
        }
    }

    override fun createWebPagePrint() {
        // SessionPdfFileSaver is GeckoView's print surface — wiring is ENG05.
        logger.log(TAG, "createWebPagePrint: unimplemented on gecko")
    }

    // ---- delegates → Page signals ----------------------------------------

    private inner class GeckoNavigationDelegate : GeckoSession.NavigationDelegate {
        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
            hasUserGesture: Boolean
        ) {
            if (url != null) {
                currentUrl = url
                onUrlChanged?.invoke(url)
            }
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            backable = canGoBack
        }

        override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
            forwardable = canGoForward
        }

        override fun onLoadRequest(
            session: GeckoSession,
            request: GeckoSession.NavigationDelegate.LoadRequest
        ): GeckoResult<AllowOrDeny> {
            val policy = profile.requestPolicy
                ?: return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            val decision = policy.decide(
                NavigationRequest(
                    url = request.uri,
                    isMainFrame = true,
                    isRedirect = request.isRedirect,
                    type = if (request.isRedirect) {
                        NavigationType.Redirect
                    } else {
                        NavigationType.Other
                    },
                    resourceType = ResourceType.MainFrame
                )
            )
            return when (decision.action) {
                RequestAction.Allow -> GeckoResult.fromValue(AllowOrDeny.ALLOW)
                RequestAction.Block -> GeckoResult.fromValue(AllowOrDeny.DENY)
                RequestAction.Redirect -> {
                    decision.redirectUrl?.let(session::loadUri)
                    GeckoResult.fromValue(AllowOrDeny.DENY)
                }
            }
        }
    }

    private inner class GeckoProgressDelegate : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            loading = true
            pageProgress = 0
            onLoadStatusChanged?.invoke(LoadStatus.Started)
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            loading = false
            pageProgress = 100
            onLoadStatusChanged?.invoke(
                if (success) LoadStatus.Succeeded else LoadStatus.Failed
            )
        }

        override fun onProgressChange(session: GeckoSession, progress: Int) {
            pageProgress = progress
            onProgressChanged?.invoke(progress)
        }

        override fun onSecurityChange(
            session: GeckoSession,
            info: GeckoSession.ProgressDelegate.SecurityInformation
        ) {
            // No WebView-style SslError to carry — Valid or None is all
            // the SslState enum can express for gecko (ENG04 gap).
            sslState = if (info.isSecure) SslState.Valid else SslState.None
            sslSubject.onNext(sslState)
        }

        override fun onSessionStateChange(
            session: GeckoSession,
            state: GeckoSession.SessionState
        ) {
            latestSessionState = state
        }
    }

    private inner class GeckoContentDelegate : GeckoSession.ContentDelegate {
        override fun onTitleChange(session: GeckoSession, title: String?) {
            currentTitle = title.orEmpty()
            onTitleChanged?.invoke(currentTitle)
        }

        override fun onContextMenu(
            session: GeckoSession,
            screenX: Int,
            screenY: Int,
            element: GeckoSession.ContentDelegate.ContextElement
        ) {
            onContextMenu?.invoke(
                ContextMenuInfo(
                    linkUrl = element.linkUri,
                    imageUrl = element.srcUri.takeIf {
                        element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE
                    },
                    selectedText = element.title,
                    isEditable = false
                )
            )
        }

        override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
            onDownloadRequested?.invoke(GeckoDownloadRequest(response))
        }

        override fun onCrash(session: GeckoSession) {
            onCrashed?.invoke()
        }
    }

    private inner class GeckoHistoryDelegate : GeckoSession.HistoryDelegate {
        override fun onHistoryStateChange(
            session: GeckoSession,
            historyList: GeckoSession.HistoryDelegate.HistoryList
        ) {
            latestHistory = historyList
        }
    }

    /**
     * [DownloadRequest] over GeckoView's `onExternalResponse` — the
     * signal reaches the shell; the DownloadManager handoff is ENG04.
     */
    private class GeckoDownloadRequest(
        private val response: WebResponse
    ) : DownloadRequest {
        override val url: String = response.uri.orEmpty()
        override val suggestedFileName: String =
            response.headerValue("Content-Disposition")
                ?.substringAfter("filename=")?.trim('"', ' ', ';').orEmpty()
        override val mimeType: String =
            response.headerValue("Content-Type")?.substringBefore(';')?.trim().orEmpty()
        override val isPaused: Boolean = false
        override val receivedBytes: Long = 0
        override val totalBytes: Long =
            response.headerValue("Content-Length")?.toLongOrNull() ?: -1L
        override val isFinished: Boolean = false

        private fun WebResponse.headerValue(name: String): String? =
            headers?.entries?.firstOrNull { it.key.equals(name, true) }?.value

        override fun accept(filePath: String) = Unit // ENG04 — no stream handoff yet
        override fun cancel() = Unit
        override fun pause() = Unit
        override fun resume() = Unit
    }

    companion object {
        private const val TAG = "GeckoPage"
        private const val STATE_KEY = "GECKO_SESSION_STATE"
    }
}
