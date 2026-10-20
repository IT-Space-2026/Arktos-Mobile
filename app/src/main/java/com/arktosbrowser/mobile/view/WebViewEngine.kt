package com.arktosbrowser.mobile.view

import android.app.Application
import android.content.Context
import android.os.Build
import android.webkit.WebSettings
import android.webkit.WebView
import android.view.View
import androidx.webkit.WebViewCompat
import com.arktosbrowser.mobile.device.BuildInfo
import com.arktosbrowser.mobile.device.BuildType
import com.arktosbrowser.mobile.engine.Backend
import com.arktosbrowser.mobile.engine.Capabilities
import com.arktosbrowser.mobile.engine.Page
import com.arktosbrowser.mobile.engine.PageRequest
import com.arktosbrowser.mobile.engine.Profile
import com.arktosbrowser.mobile.engine.ProfileOptions
import com.arktosbrowser.mobile.log.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Android WebView [Backend] — the adapter that owns every
 * `android.webkit` touchpoint behind the engine interface.
 *
 * `initialize` carries the process-wide WebView setup `BrowserApp`
 * used to do inline (incognito data-directory suffix, dev-tools flag);
 * it must still run before the first page is created, exactly like the
 * calls it replaces.
 *
 * WebView has a single storage partition per process, so
 * [defaultProfile] and [createProfile] both return [WebViewProfile]
 * wrappers over the shared `CookieManager`/`WebStorage`/`WebViewDatabase`;
 * the incognito browser runs in the `:incognito` process, which gets its
 * own partition via the data-directory suffix.
 */
@Singleton
class WebViewEngine @Inject constructor(
    private val application: Application,
    private val homePageInitializer: HomePageInitializer,
    private val incognitoPageInitializer: IncognitoPageInitializer,
    private val bookmarkPageInitializer: BookmarkPageInitializer,
    private val downloadPageInitializer: DownloadPageInitializer,
    private val historyPageInitializer: HistoryPageInitializer,
    private val buildInfo: BuildInfo,
    private val logger: Logger
) : Backend {

    override val id = "webview"
    override val displayName = "Android WebView"
    override val capabilities = Capabilities(
        devTools = true,
        perPageSettings = true,
        findInPage = true,
        lifecycleManagement = true,
        socksProxy = false, // proxy support is HTTP-CONNECT via netcipher, no SOCKS
        extensions = true,
        downloadDelegate = true,
        certificateErrorDetails = true
    )

    // Whether this process is the :incognito one — its WebView data lives
    // under a private directory suffix, so the default profile is the OTR
    // partition. Determined in [initialize] because getProcessName needs 28+.
    private var incognitoProcess = false

    override fun initialize(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 28) {
            incognitoProcess =
                Application.getProcessName() == "${context.packageName}:incognito"
            if (incognitoProcess) {
                WebView.setDataDirectorySuffix("incognito")
            }
        }
        if (buildInfo.buildType == BuildType.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        return true
    }

    override fun systemUserAgent(context: Context): String =
        WebSettings.getDefaultUserAgent(context)

    override val defaultProfile: Profile by lazy {
        WebViewProfile(application, incognitoProcess, "default")
    }

    override fun createProfile(options: ProfileOptions): Profile =
        WebViewProfile(application, options.offTheRecord, options.storageName ?: "default")

    /**
     * The page for the WebView backend — [ArktosCookieView]. Callers that
     * hold the interface see it as a [Page]; the view layer may downcast
     * back for WebView-only surface (initializers, transport plumbing).
     */
    override fun createPage(request: PageRequest): Page = ArktosCookieView(
        request.activity,
        request.initializer,
        request.profile.isOffTheRecord,
        homePageInitializer,
        incognitoPageInitializer,
        bookmarkPageInitializer,
        downloadPageInitializer,
        historyPageInitializer,
        logger
    )

    override fun createView(page: Page): View? = (page as? ArktosCookieView)?.webView

    // The installed WebView package version for the About screen —
    // the only caller-facing "engine note" the WebView backend has.
    override fun engineNote(): String =
        WebViewCompat.getCurrentWebViewPackage(application)?.versionName.orEmpty()
}
