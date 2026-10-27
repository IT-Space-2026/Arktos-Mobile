// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.gecko

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Bundle
import android.util.Base64
import android.view.View
import com.arktosbrowser.mobile.AppTheme
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.adblock.BlocklistLoader
import com.arktosbrowser.mobile.adblock.RankListLoader
import com.arktosbrowser.mobile.adblock.allowlist.AllowListModel
import com.arktosbrowser.mobile.browser.SiteSettings
import com.arktosbrowser.mobile.browser.TabsManager
import com.arktosbrowser.mobile.controller.UIController
import com.arktosbrowser.mobile.download.DownloadsPageActions
import com.arktosbrowser.mobile.dialog.LightningDialogBuilder
import com.arktosbrowser.mobile.dialog.LightningDialogBuilder.NewTab
import com.arktosbrowser.mobile.html.ChangelogPage
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
import com.arktosbrowser.mobile.network.ConnectivityGate
import com.arktosbrowser.mobile.network.FrameNavigation
import com.arktosbrowser.mobile.network.NavigationDecision
import com.arktosbrowser.mobile.network.NavigationPolicy
import com.arktosbrowser.mobile.network.NavigationPolicyConfig
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.preference.userAgent
import com.arktosbrowser.mobile.rust.ArktosCore
import com.arktosbrowser.mobile.search.SafeSearch
import com.arktosbrowser.mobile.ssl.SslState
import com.arktosbrowser.mobile.utils.isGeneratedPageUrl
import com.arktosbrowser.mobile.utils.isSpecialUrl
import com.arktosbrowser.mobile.utils.isFileUrl
import com.arktosbrowser.mobile.utils.isBookmarkUrl
import com.arktosbrowser.mobile.utils.isDownloadsUrl
import com.arktosbrowser.mobile.utils.resolveTabUrl
import com.arktosbrowser.mobile.view.BookmarkPageInitializer
import com.arktosbrowser.mobile.view.DownloadPageInitializer
import com.arktosbrowser.mobile.view.HistoryPageInitializer
import com.arktosbrowser.mobile.view.HomePageInitializer
import com.arktosbrowser.mobile.view.IncognitoPageInitializer
import com.arktosbrowser.mobile.view.LightningDownloadListener
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.PublishSubject
import java.io.File
import java.nio.charset.Charset
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoPreferenceController
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse

/**
 * A tab on the GeckoView backend — a [GeckoSession] + a lazily created
 * [GeckoView] behind the [Page] contract.
 *
 * ENG04 parity surface: the navigation delegates run the shared
 * [NavigationPolicy] (HTTPS-only consent marker, SafeSearch, strict/
 * prefer HTTPS, external-app handoff, request verdicts, offline pages,
 * referrer trimming), `onLoadError` serves the generated SSL/generic
 * interstitials as `data:` pages, the content delegate routes context
 * menus to the same long-press dialogs the WebView uses and external
 * responses to `DownloadHandler` via `LightningDownloadListener`, the
 * prompt/permission delegates cover JS dialogs, file pickers,
 * HTTP auth, runtime/content/media permissions, `SessionFinder`
 * backs find-in-page, and `SessionState` rides `saveState`.
 *
 * `Profile.requestPolicy` still runs ahead of the shared policy so
 * backend-level verdicts (the engine seam contract) keep working.
 *
 * Remaining gaps (ENG05/deferred): favicons (no delegate), JS
 * evaluation + userscript injection (WebExtensions), cookie get/set,
 * proxy plumbing, printing, per-request subresource blocking (GeckoView
 * exposes frame navigations only — subresource filtering needs a
 * WebExtension), and certificate-error *bypass* (GeckoView cannot
 * proceed past bad certs — the interstitial can only go back).
 */
class GeckoPage(
    internal val activity: Activity,
    private val engine: GeckoEngine,
    private val profile: GeckoProfile,
    private val initializer: PageInitializer,
    private val homePageInitializer: HomePageInitializer,
    private val incognitoPageInitializer: IncognitoPageInitializer,
    private val bookmarkPageInitializer: BookmarkPageInitializer,
    private val downloadPageInitializer: DownloadPageInitializer,
    private val historyPageInitializer: HistoryPageInitializer,
    private val userPreferences: UserPreferences,
    private val logger: Logger,
    private val dialogBuilder: LightningDialogBuilder,
    private val allowListModel: AllowListModel,
    private val connectivityGate: ConnectivityGate,
    private val siteSettings: SiteSettings,
    blocklistLoader: BlocklistLoader,
    rankListLoader: RankListLoader
) : Page {

    override val id = View.generateViewId()

    internal val session: GeckoSession

    // The display widget — the engine's shared GeckoView while this
    // page is bound to it, null while another page holds the view.
    // See GeckoEngine.acquireView.
    internal var view: GeckoView? = null

    private val uiController: UIController = activity as UIController
    private val errorPages = GeckoErrorPages(activity, userPreferences)
    private val navigationPolicy = NavigationPolicy()
    private val externalLauncher = GeckoExternalLauncher(activity, this, logger)

    private var committedUrl = ""
    private var currentUrl = ""
    private var currentTitle = ""
    private var backable = false
    private var forwardable = false
    private var loading = false

    /**
     * Whether this session has ever committed a real document —
     * the staleness signal. `loadCompleted` is useless for it: an
     * aborted restore (finishInitialization's stop) or an empty
     * SessionState still reports onPageStop success.
     */
    private var hasCommittedDoc = false

    /**
     * Our own abort signal — set when the shell calls [stop] (the
     * finishInitialization background-tab pass, or a user stop), cleared
     * when a new document commits. This must not rely on onPageStop:
     * whether an aborted load still reports the callback is engine
     * behavior, and a miss leaves loading/hasCommittedDoc looking
     * healthy on a tab that never finished loading.
     */
    private var loadAborted = false
    private var pageProgress = 100
    private var sslState: SslState = SslState.None
    private var latestSessionState: GeckoSession.SessionState? = null
    private var latestHistory: GeckoSession.HistoryDelegate.HistoryList? = null
    private val sslSubject = PublishSubject.create<SslState>()
    private val pageScripts = mutableListOf<UserScript>()

    /**
     * The real URL a generated error/interstitial page replaced — the
     * `data:` commit must never be reported or persisted (upstream
     * #281, mirrors `errorPageOriginalUrl` on the WebView client).
     */
    private var generatedPageOriginalUrl: String? = null

    /** Verdict-denied sub-frame loads this page — the shield count. */
    private var blockedCountThisPage = 0

    /** The content process for this session died — reload on re-select. */
    private var contentCrashed = false

    /**
     * A page load finished successfully at least once — the signal
     * that this session holds real content. Tabs restored in the
     * background get their load aborted by `TabsManager`'s
     * `finishInitialization` stop() pass, so they must reload on
     * re-select — this flag is how the page knows.
     */
    private var loadCompleted = false

    init {
        // Same warm-up as the WebView client — the Rust verdict engine
        // must be ready before the first request.
        blocklistLoader.ensureInitialized()
        rankListLoader.ensureInitialized()

        session = GeckoSession(
            GeckoSessionSettings.Builder()
                .usePrivateMode(profile.isOffTheRecord)
                .suspendMediaWhenInactive(true)
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
        session.promptDelegate = GeckoPromptDelegate(
            activity, uiController, logger, userPreferences, profile.isOffTheRecord
        )
        session.permissionDelegate =
            GeckoPermissionDelegate(
                activity,
                uiController,
                userPreferences,
                store = if (profile.isOffTheRecord) {
                    null
                } else {
                    engine.permissionStore
                }
            )
        session.finder.setDisplayFlags(
            GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL or
                GeckoSession.FINDER_DISPLAY_DIM_PAGE
        )
        // The per-session userscript message delegate +
        // GM_openInTab's channel to the tab shell (ENG05).
        engine.userScriptHostOrNull()?.let { host ->
            host.attachTo(session)
            host.setOpenInTab { url ->
                uiController.handleNewTab(NewTab.FOREGROUND, url)
            }
        }
        initializePreferences()
        initializer.initialize(this, emptyMap())
    }

    /** The widget the shell adds to the layout for this page. */
    internal fun ensureView(): GeckoView = engine.acquireView(this)

    // ---- load / navigation ---------------------------------------------

    override fun load(url: String, headers: Map<String, String>) {
        // arktos:// chrome pages and bundled file:// generated pages are
        // served as data loads *here*, before they ever reach a session
        // navigation — serving them from inside onLoadRequest (nested
        // load on a pending navigation) stalls the pipeline for seconds.
        if (url.startsWith("arktos://")) {
            if (ArktosCore.isChangelogUrl(url)) {
                serveBundledAsset("changelog.html", ChangelogPage.URL, applyTheme = true)
            }
            return
        }
        if (url.isFileUrl() && url.isSpecialUrl()) {
            val file = File(url.removePrefix("file://"))
            runCatching { file.readText() }.getOrNull()?.let { html ->
                generatedPageOriginalUrl = url
                session.load(
                    GeckoSession.Loader().data(
                        inlineAssetUrls(html).toByteArray(Charsets.UTF_8), "text/html"
                    )
                )
            }
            return
        }
        // Track the intended URL eagerly — onLocationChange only
        // reports committed loads, so an aborted first load (the
        // finishInitialization stop()) would otherwise leave the tab
        // addressless and unrecoverable. A real navigation also ends
        // any generated-page tracking; otherwise a reload fired before
        // the commit would re-issue the stale interstitial URL.
        generatedPageOriginalUrl = null
        committedUrl = url
        currentUrl = url
        session.load(
            GeckoSession.Loader()
                .uri(url)
                .apply {
                    if (headers.isNotEmpty()) {
                        additionalHeaders(headers)
                        headerFilter(GeckoSession.HEADER_FILTER_UNRESTRICTED_UNSAFE)
                    }
                }
        )
    }

    /**
     * Serve a bundled asset as a generated page — shared by the direct
     * load path and the onLoadRequest backstop for in-page chrome links.
     */
    private fun serveBundledAsset(asset: String, originalUrl: String, applyTheme: Boolean) {
        runCatching {
            activity.assets.open(asset).use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()?.let { raw ->
            generatedPageOriginalUrl = originalUrl
            val dark = applyTheme && userPreferences.useTheme != AppTheme.LIGHT &&
                userPreferences.useTheme != AppTheme.ARKTOS_LIGHT
            session.load(
                GeckoSession.Loader().data(
                    inlineAssetUrls(ChangelogPage.applyDarkTheme(raw, dark))
                        .toByteArray(Charsets.UTF_8), "text/html"
                )
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
        // resource resolution are a parity gap.
        val charset = runCatching { Charset.forName(encoding) }.getOrNull()
            ?: Charsets.UTF_8
        session.load(
            GeckoSession.Loader()
                .data(inlineAssetUrls(data).toByteArray(charset), mimeType)
        )
    }

    /**
     * A `data:` document has no base URL and gecko has no
     * `shouldInterceptRequest`-style subresource surface — so bundled
     * artwork referenced the WebView way (`file:///android_asset/<x>`)
     * or as `arktos://<page>/<x>` chrome links is inlined here as a
     * `data:` URI. The page renders identically on both engines.
     */
    private fun inlineAssetUrls(html: String): String =
        ASSET_URL_PATTERN.replace(html) { match ->
            val asset = match.groupValues[1]
            runCatching {
                val bytes = activity.assets.open(asset).use { it.readBytes() }
                "data:${ChangelogPage.mimeForAsset(asset)};base64," +
                    Base64.encodeToString(bytes, Base64.NO_WRAP)
            }.getOrNull() ?: match.value
        }

    override fun stop() {
        // A deliberate abort — the tab may hold a committed location
        // but no finished document; recover on next select.
        if (loading) loadAborted = true
        session.stop()
    }

    override fun reload() {
        // Same proxy-readiness gate as the WebView's reload.
        if (!engine.proxyUtils.isProxyReady(activity)) return
        // Reloading a generated error/interstitial page reloads the
        // `data:` document — re-issue the real navigation instead,
        // like `reloadIncludingErrorPage` on the WebView client.
        generatedPageOriginalUrl?.let { session.loadUri(it) } ?: session.reload()
    }

    override val url: String
        get() = resolveTabUrl(committedUrl, generatedPageOriginalUrl)

    /** Mirrors `isShowingErrorPage` on ArktosCookieView. */
    private val isShowingGeneratedPage: Boolean
        get() = generatedPageOriginalUrl != null && committedUrl.isGeneratedPageUrl()

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

    /**
     * Save the session. While a generated page is shown, only its
     * original URL is persisted — a `data:` history entry must never
     * be serialized (upstream #281), so the restore path reloads the
     * real URL via `BundleInitializer`/`UrlInitializer`.
     */
    override fun saveState(): Bundle =
        Bundle(GeckoSession.SessionState::class.java.classLoader).also {
            // The URL rides along in every saved bundle: it is the
            // restore fallback when the SessionState can't unparcel —
            // a session-state failure must never cost the tab itself.
            if (url.isNotBlank()) {
                it.putString(TabsManager.URL_KEY, url)
            }
            if (currentTitle.isNotBlank()) {
                it.putString(TabsManager.TITLE_KEY, currentTitle)
            }
            if (!isShowingGeneratedPage) {
                latestSessionState?.let { state -> it.putParcelable(STATE_KEY, state) }
            }
        }

    override fun restoreState(state: Bundle): Boolean {
        // SessionState is a GeckoView Parcelable — the bundle needs this
        // class's loader to unparcel it. An unparcel or restore failure
        // reports false so the caller falls back to a URL load.
        state.classLoader = GeckoSession.SessionState::class.java.classLoader
        // Persisted titles let the tab strip show the saved name
        // before anything commits — the WebView backend gets the same
        // result for free from its serialized history.
        state.getString(TabsManager.TITLE_KEY)?.let { currentTitle = it }
        val sessionState = runCatching {
            androidx.core.os.BundleCompat.getParcelable(
                state, STATE_KEY, GeckoSession.SessionState::class.java
            )
        }.getOrNull() ?: return false
        // Extract the current entry's URL from the state itself — a
        // SessionState that "reproduced" as empty (geckoview logs
        // "Can't reproduce session state from Parcel" internally and
        // yields a state with no history) restores to a blank tab, so
        // the URL is the only dependable payload.
        val stateUrl = runCatching {
            sessionState.getOrNull(sessionState.currentIndex)?.uri
        }.getOrNull()
        // Keep the restored state — finishInitialization's stop() can
        // abort the restore's load on background tabs, and
        // reloadIfStale re-issues recovery on select.
        latestSessionState = sessionState
        (state.getString(TabsManager.URL_KEY) ?: stateUrl)?.let {
            currentUrl = it
            committedUrl = it
        }
        // The restore's commit resets this — an aborted/empty restore
        // leaves the flag false so the tab reads as stale on select.
        hasCommittedDoc = false
        if (runCatching { session.restoreState(sessionState) }.isFailure) {
            return false
        }
        return true
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

    /**
     * Context-menu/explicit downloads — the same
     * `LightningDownloadListener` handoff the WebView uses: the
     * confirm dialog, the DownloadRisk warning and the
     * `DownloadManager` enqueue all ride along.
     */
    override fun download(url: String) {
        LightningDownloadListener(activity).onDownloadStart(url, userAgent(), "", "", 0)
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
        set(isForeground) {
            field = isForeground
            uiController.tabChanged(this)
        }

    override var isNewTab: Boolean = false

    override val title: String
        get() = currentTitle.ifEmpty { activity.getString(R.string.untitled) }

    override fun restoreTitle(title: String) {
        if (title.isNotBlank()) currentTitle = title
    }

    // GeckoView has no favicon delegate — tab icons come from a fetch
    // service (ENG05 gap).
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

    override fun blockedThisPage(): Int = blockedCountThisPage

    /**
     * Re-apply preference-dependent settings — JS, per-session UA and
     * viewport mode, plus the runtime-level sync the engine owns
     * (mixed-content pref, cookie behavior, safe browsing, DNT/GPC,
     * WebRTC, image blocking, font ladder, dark color scheme, ENG05).
     * The mixed-content pref is runtime-global, so incognito's
     * always-block rule wins by re-applying on every session's init —
     * documented approximation of the per-WebView setting (SEC03).
     */
    override fun initializePreferences() {
        session.settings.setAllowJavascript(userPreferences.javaScriptEnabled)
        session.settings.setUserAgentOverride(
            userPreferences.userAgent(activity.application)
        )
        session.settings.setViewportMode(
            if (userPreferences.useWideViewPortEnabled) {
                GeckoSessionSettings.VIEWPORT_MODE_MOBILE
            } else {
                GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
            }
        )
        engine.syncRuntimePreferences()
        applyMixedContentPref()
        engine.userScriptHostOrNull()?.syncIfStale()
    }

    private fun applyMixedContentPref() {
        val block = profile.isOffTheRecord || userPreferences.blockMixedContentEnabled
        GeckoPreferenceController.setGeckoPref(
            PREF_BLOCK_DISPLAYED_MIXED_CONTENT, block,
            GeckoPreferenceController.PREF_BRANCH_USER
        ).accept(
            { },
            { logger.log(TAG, "could not set $PREF_BLOCK_DISPLAYED_MIXED_CONTENT: $it") }
        )
    }

    override fun onPause() = session.setActive(false)

    override fun onResume() = session.setActive(true)

    /**
     * A gecko session keeps its content in the tab process — when that
     * process is killed (`onKill`, e.g. under memory pressure) or crashes
     * (`onCrash`), the session's compositor dies with it and a re-bound
     * GeckoView only shows a dead surface. A killed session also closes,
     * so recovery re-opens it and restores the last SessionState (keeps
     * scroll + history) rather than plain reload. The
     * `finishInitialization` stop() abort of a restored tab's first load
     * takes the same path via the never-loaded branch.
     */
    override fun reloadIfStale() {
        // Stale signals, in order of certainty: a crashed/killed
        // session, a deliberate abort whose load never finished, or no
        // committed document at all. `loading` only vetoes while the
        // tab isn't known-stale — an in-flight load isn't disturbed,
        // but a stuck flag can't veto a tab that already lost its load.
        if (!contentCrashed && !loadAborted && !hasCommittedDoc && loading && session.isOpen) {
            return
        }
        if (!contentCrashed && !loadAborted && hasCommittedDoc && session.isOpen) {
            return
        }
        val wasCrashed = contentCrashed
        contentCrashed = false
        loadAborted = false
        val state = latestSessionState
        if (!session.isOpen) {
            session.open(engine.runtime)
            if (state != null) session.restoreState(state) else session.loadUri(currentUrl)
        } else if (wasCrashed && state != null) {
            // Crash/kill recovery — re-apply the state to keep scroll.
            session.restoreState(state)
        } else if (currentUrl.isNotBlank()) {
            // Aborted load or a restore that produced nothing — the
            // tracked URL is dependable where re-restoring a possibly
            // empty SessionState is not; reload() on a session with no
            // committed entry is a no-op.
            session.loadUri(currentUrl)
        } else if (state != null) {
            session.restoreState(state)
        } else {
            reload()
        }
    }

    override fun onDestroy() {
        view?.let { v ->
            v.releaseSession()
            (v as? ArktosGeckoView)?.let {
                if (it.pendingSession == session) it.pendingSession = null
            }
        }
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
        // Same gate as the WebView's loadUrl: when I2P is configured
        // but its tunnels are down, the load must wait for them.
        if (!engine.proxyUtils.isProxyReady(activity)) return

        // Forced SafeSearch (upstream #327) applies on the gecko flavor
        // too. Save-Data rides the embedder-load header set — the same
        // coverage the WebView's requestHeaders achieve (DNT/GPC need
        // no header here: the gecko prefs already put them on every
        // request including subresources).
        val headers = if (userPreferences.saveDataEnabled) {
            mapOf(HEADER_SAVEDATA to "on")
        } else {
            emptyMap()
        }
        load(SafeSearch.enforcedUrl(url, userPreferences.safeSearchForced), headers)
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
        if (!desktopMode) {
            // MOBILE mode restores the engine UA — re-apply the
            // preference override so a custom UA survives the toggle.
            session.settings.setUserAgentOverride(
                userPreferences.userAgent(activity.application)
            )
        }
    }

    /** The UA downloads carry — the session override or the runtime default. */
    private fun userAgent(): String =
        session.settings.userAgentOverride ?: GeckoSession.getDefaultUserAgent()

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

    // ---- the navigation-policy plumbing ------------------------------------

    /**
     * The preference snapshot the shared [NavigationPolicy] decides
     * against — resolved per navigation so settings flips apply at once.
     */
    private fun policyConfig(): NavigationPolicyConfig =
        NavigationPolicyConfig(
            safeSearchForced = userPreferences.safeSearchForced,
            forceHttps = userPreferences.forceHTTPSenabled,
            preferHttps = userPreferences.preferHTTPSenabled,
            referrerMode = userPreferences.referrerMode,
            blockIntent = userPreferences.blockIntent,
            incognito = profile.isOffTheRecord,
            connected = connectivityGate.isConnected(),
            adsAllowedOnPage = allowListModel.isUrlAllowedAds(currentUrl)
        )

    /**
     * Serve a generated interstitial in place of [originalUrl] — the
     * `Loader.data` equivalent of the WebView's `blockedPageResponse`/
     * `loadDataWithBaseURL` paths.
     */
    private fun serveInterstitial(
        page: NavigationDecision.GeneratedPage,
        originalUrl: String
    ) {
        generatedPageOriginalUrl = originalUrl
        session.load(
            GeckoSession.Loader().data(
                inlineAssetUrls(errorPages.interstitialHtml(page, originalUrl))
                    .toByteArray(Charsets.UTF_8), "text/html"
            )
        )
    }

    /**
     * Re-issue [url] as an embedder load — SafeSearch rewrites, HTTPS
     * upgrades, the consent-marker target and the referrer takeover.
     * [referrer] rides `Loader.referrer`; a null referrer on an
     * embedder-initiated load sends no Referer header (the STRIP
     * behavior).
     */
    private fun reissue(url: String, referrer: String?) {
        session.load(
            GeckoSession.Loader().uri(url).apply {
                referrer?.let { referrer(Uri.parse(it)) }
            }
        )
    }

    // ---- delegates → Page signals ----------------------------------------

    private inner class GeckoNavigationDelegate : GeckoSession.NavigationDelegate {
        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
            hasUserGesture: Boolean
        ) {
            if (url == null) return
            // A blank commit before any real document means an aborted
            // or empty restore (upgrade-restore can "reproduce" an
            // empty SessionState and commit about:blank) — keep the
            // tracked URL or reloadIfStale would re-load the blank.
            val emptyRestoreCommit = url == "about:blank" && !hasCommittedDoc
            if (!emptyRestoreCommit) committedUrl = url
            if (url.isNotBlank() && url != "about:blank") {
                // A real document committed — whatever the earlier
                // abort left behind, the tab is healthy again.
                hasCommittedDoc = true
                loadAborted = false
            }
            if (url.isGeneratedPageUrl()) {
                // Generated commit — keep reporting the original URL
                // (upstream #281); the real address stays in currentUrl.
            } else if (!emptyRestoreCommit) {
                generatedPageOriginalUrl = null
                currentUrl = url
                navigationPolicy.onNavigationCommitted(url, false)
            }
            val reported = this@GeckoPage.url
            onUrlChanged?.invoke(reported)
            if (isShown) {
                uiController.updateUrl(reported, loading)
            }
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            backable = canGoBack
            if (isShown) uiController.setBackButtonEnabled(canGoBack)
        }

        override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
            forwardable = canGoForward
            if (isShown) uiController.setForwardButtonEnabled(canGoForward)
        }

        override fun onLoadRequest(
            session: GeckoSession,
            request: GeckoSession.NavigationDelegate.LoadRequest
        ): GeckoResult<AllowOrDeny> {
            // A target=_blank / window.open load carries TARGET_WINDOW_NEW —
            // onNewSession owns those; everything else is a main-frame
            // navigation through the shared policy.
            if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
                return GeckoResult.fromValue(AllowOrDeny.DENY)
            }
            // arktos:// chrome pages (the changelog). Deny the
            // navigation, then serve the bundled HTML on the next
            // main-loop turn — a synchronous session.load() inside
            // onLoadRequest stalls the navigation pipeline (the
            // seconds-long changelog freeze). load() pre-intercepts
            // these URLs, so this is only the in-page-link backstop.
            if (request.uri.startsWith("arktos://")) {
                if (request.uri == DownloadsPageActions.CLEAR_URL) {
                    DownloadsPageActions.clearAll(activity) {
                        activity.window.decorView.post { loadDownloadsPage() }
                    }
                }
                if (ArktosCore.isChangelogUrl(request.uri)) {
                    activity.window.decorView.post {
                        serveBundledAsset("changelog.html", ChangelogPage.URL, applyTheme = true)
                    }
                }
                return GeckoResult.fromValue(AllowOrDeny.DENY)
            }

            // Bundled app pages (the start/bookmark/history/incognito/
            // download pages) live under filesDir — GeckoView can't open
            // `file:///data/...` paths, so they're served as data loads
            // instead, posted for the same reason as above.
            if (request.uri.isFileUrl() && request.uri.isSpecialUrl()) {
                val file = File(request.uri.removePrefix("file://"))
                runCatching { file.readText() }.getOrNull()?.let { html ->
                    generatedPageOriginalUrl = request.uri
                    activity.window.decorView.post {
                        session.load(
                            GeckoSession.Loader().data(
                                inlineAssetUrls(html).toByteArray(Charsets.UTF_8), "text/html"
                            )
                        )
                    }
                }
                return GeckoResult.fromValue(AllowOrDeny.DENY)
            }

            // The engine-seam policy first (ENG02 contract), then the
            // composed parity gates.
            profile.requestPolicy?.let { policy ->
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
                when (decision.action) {
                    RequestAction.Allow -> Unit
                    RequestAction.Block -> return GeckoResult.fromValue(AllowOrDeny.DENY)
                    RequestAction.Redirect -> {
                        decision.redirectUrl?.let { reissue(it, null) }
                        return GeckoResult.fromValue(AllowOrDeny.DENY)
                    }
                }
            }

            val nav = FrameNavigation(
                url = request.uri,
                isMainFrame = true,
                isRedirect = request.isRedirect,
                hasUserGesture = request.hasUserGesture,
                // The page that initiated the load — referrer source and
                // verdict context, `triggerUrl`/`currentUrl` on WebView.
                triggerUrl = request.triggerUri ?: currentUrl.takeIf {
                    !request.isDirectNavigation
                }
            )
            return when (val decision =
                navigationPolicy.decideMainFrame(nav, policyConfig()) { url ->
                    externalLauncher.launch(url)
                }
            ) {
                NavigationDecision.Proceed -> GeckoResult.fromValue(AllowOrDeny.ALLOW)
                NavigationDecision.Deny -> GeckoResult.fromValue(AllowOrDeny.DENY)
                is NavigationDecision.Redirect -> {
                    reissue(decision.url, decision.referrer)
                    GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                is NavigationDecision.Interstitial -> {
                    serveInterstitial(decision.page, decision.originalUrl)
                    GeckoResult.fromValue(AllowOrDeny.DENY)
                }
            }
        }

        override fun onSubframeLoadRequest(
            session: GeckoSession,
            request: GeckoSession.NavigationDelegate.LoadRequest
        ): GeckoResult<AllowOrDeny> {
            return when (
                navigationPolicy.decideSubFrame(
                    request.uri, currentUrl,
                    allowListModel.isUrlAllowedAds(currentUrl)
                )
            ) {
                NavigationDecision.Proceed -> GeckoResult.fromValue(AllowOrDeny.ALLOW)
                else -> {
                    blockedCountThisPage++
                    GeckoResult.fromValue(AllowOrDeny.DENY)
                }
            }
        }

        /**
         * `target=_blank`/`window.open` — the WebView's `onCreateWindow`
         * makes a new tab for the transport; gecko wants a session, so
         * deny it and hand the URL to the shell's tab factory instead
         * (the ResultMessage/WebViewTransport transport has no gecko
         * equivalent — documented gap).
         */
        override fun onNewSession(
            session: GeckoSession,
            uri: String
        ): GeckoResult<GeckoSession> {
            if (uri.isNotEmpty()) {
                // `supportMultipleWindows` parity (popupsEnabled): with
                // new-window support off the navigation stays in this
                // session instead of spawning a tab (ENG05).
                if (userPreferences.popupsEnabled) {
                    uiController.handleNewTab(NewTab.FOREGROUND, uri, true)
                } else {
                    session.loadUri(uri)
                }
            }
            return GeckoResult.fromValue(null)
        }

        /**
         * The SSL/generic error path — `onReceivedError` +
         * `onReceivedSslError` parity: a certificate failure produces
         * the warning interstitial (GeckoView offers no
         * `SslErrorHandler.proceed`, so bypass is impossible —
         * documented gap), other failures the offline/generic page.
         */
        override fun onLoadError(
            session: GeckoSession,
            uri: String?,
            error: WebRequestError
        ): GeckoResult<String>? {
            val url = uri ?: return null
            val html = if (isCertificateError(error)) {
                sslState = SslState.Invalid(sslErrorFor(error))
                sslSubject.onNext(sslState)
                // The signal exists for the seam; the action is ignored
                // because GeckoView can't proceed past cert errors.
                onCertificateError?.invoke(
                    CertificateErrorInfo(url, "error ${error.code}", false)
                )
                errorPages.certErrorPage(url, error)
            } else {
                generatedPageOriginalUrl = url
                errorPages.loadErrorPage(
                    url, error,
                    ConnectivityGate.isOfflineError(
                        connectivityGate.isConnected(), "error ${error.code}"
                    )
                )
            }
            return GeckoResult.fromValue(GeckoErrorPages.dataUri(inlineAssetUrls(html)))
        }

        private fun isCertificateError(error: WebRequestError): Boolean =
            error.category == WebRequestError.ERROR_CATEGORY_SECURITY

        /**
         * Map the gecko cert error onto a WebView-shaped `SslError` so
         * `SslState.Invalid` keeps working for the shell UI — the best
         * fidelity gecko's category/code surface allows.
         */
        private fun sslErrorFor(error: WebRequestError): SslError {
            val code = when (error.code) {
                WebRequestError.ERROR_SECURITY_BAD_CERT -> SslError.SSL_UNTRUSTED
                WebRequestError.ERROR_BAD_HSTS_CERT -> SslError.SSL_DATE_INVALID
                else -> SslError.SSL_INVALID
            }
            @Suppress("DEPRECATION")
            return SslError(code, sslCertificateOrStub())
        }

        private fun sslCertificateOrStub(): SslCertificate {
            val x509 = latestSecurity?.certificate
            @Suppress("DEPRECATION")
            return if (x509 != null) {
                SslCertificate(x509)
            } else {
                SslCertificate("", "", "", "")
            }
        }
    }

    /** The last security info — for cert detail on the error path. */
    private var latestSecurity: GeckoSession.ProgressDelegate.SecurityInformation? = null

    private inner class GeckoProgressDelegate : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            loading = true
            pageProgress = 0
            blockedCountThisPage = 0
            // Per-site JS override, like the WebView client's onPageStarted.
            session.settings.setAllowJavascript(siteSettings.javaScriptEnabled(url))
            onLoadStatusChanged?.invoke(LoadStatus.Started)
            if (isShown) {
                uiController.updateUrl(this@GeckoPage.url, true)
                uiController.showActionBar()
            }
            uiController.tabChanged(this@GeckoPage)
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            loading = false
            pageProgress = 100
            if (success) {
                loadCompleted = true
            } else {
                // Aborted/failed — the location commit may already have
                // set hasCommittedDoc, but no document finished; the
                // tab is stale and must re-issue its load on select.
                hasCommittedDoc = false
            }
            onLoadStatusChanged?.invoke(
                if (success) LoadStatus.Succeeded else LoadStatus.Failed
            )
            if (isShown) {
                uiController.updateUrl(this@GeckoPage.url, false)
                uiController.setBackButtonEnabled(backable)
                uiController.setForwardButtonEnabled(forwardable)
            }
        }

        override fun onProgressChange(session: GeckoSession, progress: Int) {
            pageProgress = progress
            onProgressChanged?.invoke(progress)
            if (isShown) uiController.updateProgress(progress)
        }

        override fun onSecurityChange(
            session: GeckoSession,
            info: GeckoSession.ProgressDelegate.SecurityInformation
        ) {
            latestSecurity = info
            // Broken TLS surfaces through onLoadError instead; here
            // Valid/None mirrors the WebView's page-start/finish states.
            sslState = if (info.isSecure) SslState.Valid else SslState.None
            sslSubject.onNext(sslState)
        }

        override fun onSessionStateChange(
            session: GeckoSession,
            state: GeckoSession.SessionState
        ) {
            latestSessionState = state
            // This fires after the navigation that produced the state
            // — saving here captures the CURRENT entry, whereas the
            // updateUrl-triggered saves always run one navigation
            // behind (upstream #281-class staleness). Only the shown
            // page reports; background tabs' states ride the same
            // saveState sweep whenever any updateUrl fires.
            if (isShown) {
                uiController.updateUrl(this@GeckoPage.url, loading)
            }
        }
    }

    private inner class GeckoContentDelegate : GeckoSession.ContentDelegate {
        override fun onTitleChange(session: GeckoSession, title: String?) {
            currentTitle = title.orEmpty()
            onTitleChanged?.invoke(this@GeckoPage.title)
            uiController.tabChanged(this@GeckoPage)
            if (this@GeckoPage.url.isNotEmpty()) {
                uiController.updateHistory(title, this@GeckoPage.url)
            }
        }

        /**
         * Long-press → the same dialogs the WebView's `longClickPage`
         * shows: bookmark/download entries on their special pages,
         * the image menu, and the link menu.
         */
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
            val pageUrl = this@GeckoPage.url
            if (pageUrl.isSpecialUrl()) {
                val target = element.linkUri ?: element.srcUri ?: return
                if (pageUrl.isBookmarkUrl()) {
                    dialogBuilder.showLongPressedDialogForBookmarkUrl(
                        activity, uiController, target
                    )
                } else if (pageUrl.isDownloadsUrl()) {
                    dialogBuilder.showLongPressedDialogForDownloadUrl(
                        activity, uiController, target
                    )
                }
                return
            }
            val link = element.linkUri
            // TYPE_IMAGE covers both bare images and images inside a
            // link — linkUri disambiguates (SRC_IMAGE_ANCHOR vs IMAGE).
            val media = element.srcUri.takeIf {
                element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE ||
                    element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_VIDEO ||
                    element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_AUDIO
            }
            when {
                link != null && media != null ->
                    dialogBuilder.showLongPressImageDialog(
                        activity, uiController, link, media, userAgent()
                    )
                media != null ->
                    dialogBuilder.showLongPressImageDialog(
                        activity, uiController, media, media, userAgent()
                    )
                link != null ->
                    dialogBuilder.showLongPressLinkDialog(activity, uiController, link)
            }
        }

        /**
         * `onExternalResponse` — a response Gecko isn't rendering gets
         * the full `LightningDownloadListener` flow: confirm dialog,
         * DownloadRisk warning, DownloadManager enqueue.
         */
        override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
            val url = response.uri.orEmpty()
            onDownloadRequested?.invoke(GeckoDownloadRequest(response))
            LightningDownloadListener(activity).onDownloadStart(
                url,
                userAgent(),
                response.headerValue("Content-Disposition").orEmpty(),
                response.headerValue("Content-Type")?.substringBefore(';')?.trim()
                    .orEmpty(),
                response.headerValue("Content-Length")?.toLongOrNull() ?: -1L
            )
        }

        override fun onCrash(session: GeckoSession) {
            contentCrashed = true
            onCrashed?.invoke()
            if (isShown) reloadIfStale()
        }

        /**
         * The tab's content process was killed (e.g. memory pressure) —
         * distinct from onCrash: the compositor is dead but no crash is
         * reported, and the session closes. `reloadIfStale` re-opens it
         * when the tab is next selected; the foreground session is
         * resurrected right away.
         */
        override fun onKill(session: GeckoSession) {
            contentCrashed = true
            if (isShown) reloadIfStale()
        }

        /** `window.close()` — the WebView's `onCloseWindow` parity. */
        override fun onCloseRequest(session: GeckoSession) {
            uiController.onCloseWindow(this@GeckoPage)
        }

        /**
         * Fullscreen video — the GeckoView itself goes into the shell's
         * fullscreen container (the standard GeckoView pattern; the
         * WebView supplies a dedicated custom view instead).
         */
        override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
            try {
                if (fullScreen) {
                    ensureView()
                    view?.let { v ->
                        uiController.onShowCustomView(
                            v, callback = { session.exitFullScreen() }
                        )
                    }
                } else {
                    uiController.onHideCustomView()
                }
            } catch (e: Exception) {
                // A failed reparent/display-bind must not kill the app —
                // decline fullscreen instead (IllegalStateException seen
                // in the field on a stale window attach).
                logger.log(TAG, "fullscreen toggle failed", e)
                if (fullScreen) {
                    runCatching { session.exitFullScreen() }
                }
            }
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
     * signal surface; the DownloadManager handoff rides
     * `LightningDownloadListener` directly.
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

        override fun accept(filePath: String) = Unit // no stream handoff
        override fun cancel() = Unit
        override fun pause() = Unit
        override fun resume() = Unit
    }

    companion object {
        private const val TAG = "GeckoPage"
        private const val STATE_KEY = "GECKO_SESSION_STATE"

        // Bundled-artwork references inside generated pages — see
        // inlineAssetUrls.
        private val ASSET_URL_PATTERN =
            Regex("(?:file:///android_asset/|arktos://[^/\"'\\s]+/)([A-Za-z0-9._/-]+)")

        /** Mirrors `ArktosCookieView.HEADER_SAVEDATA`. */
        private const val HEADER_SAVEDATA = "Save-Data"

        /** Gecko pref toggled by the mixed-content setting (SEC03). */
        private const val PREF_BLOCK_DISPLAYED_MIXED_CONTENT =
            "security.mixed_content.block_displayed_content"
    }
}

private fun WebResponse.headerValue(name: String): String? =
    headers?.entries?.firstOrNull { it.key.equals(name, true) }?.value
