package com.arktosbrowser.mobile.gecko

import android.app.Application
import android.content.Context
import android.os.Build
import android.view.View
import com.arktosbrowser.mobile.device.BuildInfo
import com.arktosbrowser.mobile.device.BuildType
import com.arktosbrowser.mobile.engine.Backend
import com.arktosbrowser.mobile.engine.Capabilities
import com.arktosbrowser.mobile.engine.Page
import com.arktosbrowser.mobile.engine.PageRequest
import com.arktosbrowser.mobile.engine.Profile
import com.arktosbrowser.mobile.engine.ProfileOptions
import com.arktosbrowser.mobile.log.Logger
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.view.BookmarkPageInitializer
import com.arktosbrowser.mobile.view.DownloadPageInitializer
import com.arktosbrowser.mobile.view.HistoryPageInitializer
import com.arktosbrowser.mobile.view.HomePageInitializer
import com.arktosbrowser.mobile.view.IncognitoPageInitializer
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession

/**
 * The GeckoView [Backend] — ENG03 skeleton.
 *
 * Owns the process-wide [GeckoRuntime] (one per process — the
 * `:incognito` process gets its own, and its sessions are created
 * private-mode so nothing persists to disk).
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
    private val logger: Logger
) : Backend {

    override val id = "gecko"
    override val displayName = "GeckoView"
    override val capabilities = Capabilities(
        devTools = true,            // remoteDebuggingEnabled + about:config
        perPageSettings = true,     // per-session GeckoSessionSettings
        findInPage = true,          // SessionFinder
        lifecycleManagement = true, // setActive / setFocused / priority hint
        socksProxy = false,         // proxy plumbing is a parity gap (ENG05)
        extensions = true,          // WebExtension surface exists; wiring is ENG05
        downloadDelegate = true,    // onExternalResponse
        certificateErrorDetails = true // onLoadError carries error codes
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

    override fun initialize(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 28) {
            incognitoProcess =
                Application.getProcessName() == "${context.packageName}:incognito"
        }
        val settings = GeckoRuntimeSettings.Builder()
            .remoteDebuggingEnabled(buildInfo.buildType == BuildType.DEBUG)
            .build()
        runtime = GeckoRuntime.create(context, settings)
        return true
    }

    override fun systemUserAgent(context: Context): String =
        GeckoSession.getDefaultUserAgent()

    override val defaultProfile: Profile by lazy {
        GeckoProfile(this, "default", incognitoProcess)
    }

    override fun createProfile(options: ProfileOptions): Profile =
        GeckoProfile(this, options.storageName ?: "default",
            options.offTheRecord || incognitoProcess)

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
        logger
    )

    override fun createView(page: Page): View? = (page as? GeckoPage)?.ensureView()

    // The installed GeckoView milestone for the About screen.
    override fun engineNote(): String =
        "GeckoView ${org.mozilla.geckoview.BuildConfig.MOZ_APP_VERSION}"
}
