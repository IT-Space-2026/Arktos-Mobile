// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.gecko

import android.app.Application
import android.content.Context
import android.os.Build
import android.view.View
import com.arktosbrowser.mobile.browser.IncognitoPartition
import com.arktosbrowser.mobile.database.javascript.JavaScriptRepository
import com.arktosbrowser.mobile.device.BuildInfo
import com.arktosbrowser.mobile.device.BuildType
import com.arktosbrowser.mobile.di.MainScheduler
import com.arktosbrowser.mobile.di.NetworkScheduler
import com.arktosbrowser.mobile.engine.Backend
import com.arktosbrowser.mobile.engine.Capabilities
import com.arktosbrowser.mobile.engine.CookieAcceptance
import com.arktosbrowser.mobile.engine.cookieAcceptance
import com.arktosbrowser.mobile.engine.HttpProxy
import com.arktosbrowser.mobile.engine.Page
import com.arktosbrowser.mobile.engine.PageRequest
import com.arktosbrowser.mobile.engine.Profile
import com.arktosbrowser.mobile.engine.ProfileOptions
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
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoPreferenceController
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.StorageController

/**
 * The GeckoView [Backend] — ENG03 skeleton, ENG05 parity pass B.
 *
 * Owns the process-wide [GeckoRuntime] (one per process — the
 * `:incognito` process gets its own, and its sessions are created
 * private-mode so nothing persists to disk), the runtime-level
 * preference sync ([syncRuntimePreferences] — cookie behavior, safe
 * browsing, DNT/Sec-GPC, WebRTC, image blocking, form fill, font
 * size/inflation, dark color scheme, force-zoom) and the proxy applier
 * (`network.proxy.*` gecko prefs). The built-in userscript
 * WebExtension is hosted by [userScriptHost].
 *
 * Deliberately not Dagger-constructed: `BrowserApp` can't name a
 * flavor-scoped type, so the gecko flavor's `EngineSelector` builds it
 * with the same dependencies the `WebViewEngine` gets injected, pulled
 * from `AppComponent`'s provision methods.
 */
class GeckoEngine(
    private val application: Application,
    private val buildInfo: BuildInfo,
    private val homePageInitializer: HomePageInitializer,
    private val incognitoPageInitializer: IncognitoPageInitializer,
    private val bookmarkPageInitializer: BookmarkPageInitializer,
    private val downloadPageInitializer: DownloadPageInitializer,
    private val historyPageInitializer: HistoryPageInitializer,
    private val userPreferences: UserPreferences,
    private val logger: Logger,
    // ENG04 — the WebView-client parity collaborators each page needs
    // (context menus, allowlist verdicts, connectivity probe, per-site
    // settings, blocklist warm-up).
    private val dialogBuilder: com.arktosbrowser.mobile.dialog.LightningDialogBuilder,
    private val allowListModel: com.arktosbrowser.mobile.adblock.allowlist.AllowListModel,
    private val connectivityGate: com.arktosbrowser.mobile.network.ConnectivityGate,
    private val siteSettings: com.arktosbrowser.mobile.browser.SiteSettings,
    private val blocklistLoader: com.arktosbrowser.mobile.adblock.BlocklistLoader,
    private val rankListLoader: com.arktosbrowser.mobile.adblock.RankListLoader,
    // ENG05 — storage/proxy/scripts parity collaborators.
    private val javascriptRepository: JavaScriptRepository,
    private val userScriptBootstrap: UserScriptBootstrap,
    private val arktosStore: ArktosStore,
    internal val proxyUtils: ProxyUtils,
    @NetworkScheduler private val networkScheduler: Scheduler,
    @MainScheduler private val mainScheduler: Scheduler
) : Backend {

    override val id = "gecko"
    override val displayName = "GeckoView"
    override val capabilities = Capabilities(
        devTools = true,            // remoteDebuggingEnabled + about:config
        perPageSettings = true,     // per-session GeckoSessionSettings
        findInPage = true,          // SessionFinder
        lifecycleManagement = true, // setActive / setFocused / priority hint
        socksProxy = false,         // HTTP proxy only, via gecko prefs
        extensions = true,          // built-in userscript WebExtension (ENG05)
        downloadDelegate = true,    // onExternalResponse
        certificateErrorDetails = true, // onLoadError carries error codes
        httpProxy = true,           // network.proxy.* via GeckoPreferenceController
        textEncoding = false,       // no charset-override surface on GeckoView
        renderingControl = false,   // no software/invert rendering pipeline switch
        shrinkToFit = false,        // no loadWithOverviewMode equivalent
        devToolsActivity = false,   // no launchable dev-ui; remote debugging is build-type bound
        sendsIdentifyingHeaders = false // gecko emits no X-Requested-With/X-Wap-Profile
    )

    // Whether this process is the :incognito one — its pages are created
    // with usePrivateMode so Gecko keeps no disk state, mirroring the
    // WebView backend's data-directory-suffix isolation.
    private var incognitoProcess = false

    /**
     * The process-wide runtime. Created in [initialize] — GeckoRuntime
     * may only be created once per process.
     */
    internal lateinit var runtime: GeckoRuntime
        private set

    /** The built-in userscript WebExtension host — created with the runtime. */
    internal lateinit var userScriptHost: GeckoUserScriptHost
        private set

    /**
     * The host when the runtime exists, else null — `::isInitialized`
     * on a `lateinit` is only readable inside this class, so callers
     * use this guard.
     */
    internal fun userScriptHostOrNull(): GeckoUserScriptHost? =
        if (::userScriptHost.isInitialized) userScriptHost else null

    // The last proxy handed to applyProxy, re-applied when the runtime
    // (re)appears — updates can arrive before initialize.
    private var appliedProxy: HttpProxy? = null
    private var proxyRequested = false

    override fun initialize(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 28) {
            incognitoProcess = IncognitoPartition.isIncognitoProcess(
                context.packageName, Application.getProcessName()
            )
        }
        val settings = GeckoRuntimeSettings.Builder()
            .remoteDebuggingEnabled(buildInfo.buildType == BuildType.DEBUG)
            .build()
        runtime = GeckoRuntime.create(context, settings)
        if (incognitoProcess) {
            // INCOG01 — private-mode sessions never persist page data,
            // so the WebView-style partition wipe has no equivalent
            // here; this clears whatever else the runtime itself
            // stored (caches, leftovers from a session whose process
            // died without a clean shutdown) so a swipe-away cannot
            // carry state into the next incognito session either.
            runtime.storageController.clearData(StorageController.ClearFlags.ALL)
        }
        userScriptHost = GeckoUserScriptHost(
            this, javascriptRepository, userScriptBootstrap, arktosStore,
            userPreferences, logger, application, networkScheduler, mainScheduler
        )
        syncRuntimePreferences()
        if (proxyRequested) applyProxy(appliedProxy)
        userScriptHost.install()
        return true
    }

    override fun systemUserAgent(context: Context): String =
        GeckoSession.getDefaultUserAgent()

    override val defaultProfile: Profile by lazy {
        GeckoProfile(this, "default", incognitoProcess,
            acceptsCookiesInitial = userPreferences.cookiesEnabled)
    }

    override fun createProfile(options: ProfileOptions): Profile =
        GeckoProfile(this, options.storageName ?: "default",
            options.offTheRecord || incognitoProcess,
            acceptsCookiesInitial = userPreferences.cookiesEnabled)

    override fun createPage(request: PageRequest): Page = GeckoPage(
        request.activity,
        this,
        request.profile as GeckoProfile,
        request.initializer,
        homePageInitializer,
        incognitoPageInitializer,
        bookmarkPageInitializer,
        downloadPageInitializer,
        historyPageInitializer,
        userPreferences,
        logger,
        dialogBuilder,
        allowListModel,
        connectivityGate,
        siteSettings,
        blocklistLoader,
        rankListLoader
    )

    /**
     * The one display widget the engine owns — Gecko sessions bind to
     * it and unbind from it as the shell swaps tabs. This is the
     * mozilla-components `EngineView` model: per-tab GeckoViews lose
     * their compositor surface on detach/reattach and come back black,
     * so the view must stay attached and the session must swap inside
     * it.
     */
    private var sharedView: GeckoView? = null
    private var boundPage: GeckoPage? = null

    /**
     * Bind [page]'s session to the shared [GeckoView] and return it.
     * The previously bound page loses its `view` reference — `isShown`
     * must not report a view it no longer owns — and its session loses
     * content focus.
     */
    internal fun acquireView(page: GeckoPage): GeckoView {
        sharedView?.let { existing ->
            if (existing.context !== page.activity) {
                // The view was built on an Activity that has since
                // been destroyed (rotation, restart) — holding it
                // leaks that Activity and paints on a dead surface.
                boundPage?.view = null
                boundPage = null
                existing.releaseSession()
                sharedView = null
            }
        }
        val gv = sharedView
            ?: GeckoView(page.activity).also { sharedView = it }
        if (boundPage !== page) {
            boundPage?.let {
                it.view = null
                if (it.session.isOpen) it.session.setFocused(false)
            }
            if (gv.session !== page.session) {
                gv.releaseSession()
                gv.setSession(page.session)
            }
            page.session.setFocused(true)
            boundPage = page
        }
        page.view = gv
        return gv
    }

    override fun createView(page: Page): View? =
        (page as? GeckoPage)?.let { acquireView(it) }

    // The installed GeckoView milestone for the About screen.
    override fun engineNote(): String =
        "GeckoView ${org.mozilla.geckoview.BuildConfig.MOZ_APP_VERSION}"

    /**
     * ENG05 — HTTP proxy through gecko prefs (Orbot/I2P/manual all
     * resolve to an HTTP CONNECT proxy upstream, like the WebView
     * `ProxyController` rule). `share_proxy_settings` applies the
     * HTTP entry to every scheme.
     */
    override fun applyProxy(proxy: HttpProxy?) {
        appliedProxy = proxy
        proxyRequested = true
        if (!::runtime.isInitialized) return
        if (proxy == null) {
            setGeckoPref(PREF_PROXY_TYPE, 0)
            for (name in PROXY_PREFS) {
                GeckoPreferenceController.clearGeckoUserPref(name).accept(
                    { }, { logger.log(TAG, "could not clear $name: $it") }
                )
            }
        } else {
            setGeckoPref(PREF_PROXY_TYPE, PROXY_TYPE_MANUAL)
            setGeckoPref(PREF_PROXY_HTTP, proxy.host)
            setGeckoPref(PREF_PROXY_HTTP_PORT, proxy.port)
            setGeckoPref(PREF_PROXY_SHARE, true)
        }
    }

    /**
     * The `acceptCookie`/`third-party` prefs expressed as a gecko
     * `ContentBlocking.CookieBehavior` — called whenever either input
     * changes (profile setter, preference sync). The fold is the
     * shared [cookieAcceptance] helper so it can never diverge from
     * the WebView side.
     */
    internal fun syncCookieBehavior() {
        if (!::runtime.isInitialized) return
        val behavior = when (cookieAcceptance(
            defaultProfile.acceptsCookies,
            userPreferences.blockThirdPartyCookiesEnabled
        )) {
            CookieAcceptance.NONE -> ContentBlocking.CookieBehavior.ACCEPT_NONE
            CookieAcceptance.FIRST_PARTY_ONLY ->
                ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY
            CookieAcceptance.ALL -> ContentBlocking.CookieBehavior.ACCEPT_ALL
        }
        runtime.settings.contentBlocking.setCookieBehavior(behavior)
        runtime.settings.contentBlocking.setCookieBehaviorPrivateMode(behavior)
    }

    /**
     * ENG05 — re-apply every runtime-mappable preference. Runs once in
     * [initialize] and again from [GeckoPage.initializePreferences] on
     * resume, mirroring how the WebView pushes prefs into `WebSettings`.
     */
    internal fun syncRuntimePreferences() {
        if (!::runtime.isInitialized) return

        syncCookieBehavior()
        runtime.settings.contentBlocking.setSafeBrowsing(
            if (userPreferences.blockMalwareEnabled) {
                ContentBlocking.SafeBrowsing.DEFAULT
            } else {
                ContentBlocking.SafeBrowsing.NONE
            }
        )

        // The WebView ladder is percent-based; gecko takes a factor.
        runtime.settings.fontSizeFactor =
            com.arktosbrowser.mobile.browser.TextZoom
                .zoomPercent(userPreferences.textSize) / 100f
        // Text reflow is the WebExtension content script
        // (text_reflow.js) driven by browser.storage through
        // [GeckoUserScriptHost.syncReflowPref] — font inflation is
        // Gecko's own rough equivalent and would double-apply on top
        // of the element-tagging reflow.
        runtime.settings.fontInflationEnabled = false
        userScriptHostOrNull()?.syncReflowPref()
        runtime.settings.preferredColorScheme =
            if (userPreferences.darkWebAddonEnabled(application)) {
                GeckoRuntimeSettings.COLOR_SCHEME_DARK
            } else {
                GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM
            }

        // Gecko prefs — full-coverage DNT + Sec-GPC (SEC17's WebView
        // header-injection gap doesn't exist here: gecko attaches both
        // headers to every request, subresources included).
        setGeckoPref(PREF_DNT, userPreferences.doNotTrackEnabled)
        setGeckoPref(PREF_GPC, userPreferences.doNotTrackEnabled)
        setGeckoPref(PREF_WEBRTC, userPreferences.webRtcEnabled)
        // permissions.default.image: 1 = allow all, 2 = block all.
        setGeckoPref(PREF_IMAGE_BLOCKING, if (userPreferences.blockImagesEnabled) 2 else 1)
        setGeckoPref(PREF_FORMFILL,
            userPreferences.savePasswordsEnabled && !incognitoProcess)
        setGeckoPref(PREF_FORCE_ZOOM, userPreferences.forceZoom)
    }

    private fun setGeckoPref(name: String, value: Boolean) =
        GeckoPreferenceController.setGeckoPref(
            name, value, GeckoPreferenceController.PREF_BRANCH_USER
        ).accept({ }, { logger.log(TAG, "could not set $name: $it") })

    private fun setGeckoPref(name: String, value: Int) =
        GeckoPreferenceController.setGeckoPref(
            name, value, GeckoPreferenceController.PREF_BRANCH_USER
        ).accept({ }, { logger.log(TAG, "could not set $name: $it") })

    private fun setGeckoPref(name: String, value: String) =
        GeckoPreferenceController.setGeckoPref(
            name, value, GeckoPreferenceController.PREF_BRANCH_USER
        ).accept({ }, { logger.log(TAG, "could not set $name: $it") })

    companion object {
        private const val TAG = "GeckoEngine"

        private const val PROXY_TYPE_MANUAL = 1
        private const val PREF_PROXY_TYPE = "network.proxy.type"
        private const val PREF_PROXY_HTTP = "network.proxy.http"
        private const val PREF_PROXY_HTTP_PORT = "network.proxy.http_port"
        private const val PREF_PROXY_SHARE = "network.proxy.share_proxy_settings"
        private val PROXY_PREFS = listOf(
            PREF_PROXY_TYPE, PREF_PROXY_HTTP, PREF_PROXY_HTTP_PORT, PREF_PROXY_SHARE
        )

        private const val PREF_DNT = "privacy.donottrackheader.enabled"
        private const val PREF_GPC = "privacy.globalprivacycontrol.enabled"
        private const val PREF_WEBRTC = "media.peerconnection.enabled"
        private const val PREF_IMAGE_BLOCKING = "permissions.default.image"
        private const val PREF_FORMFILL = "browser.formfill.enable"
        private const val PREF_FORCE_ZOOM = "browser.ui.zoom.force-user-scalable"
    }
}
