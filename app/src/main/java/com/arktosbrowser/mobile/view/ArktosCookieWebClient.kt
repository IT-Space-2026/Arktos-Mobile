package com.arktosbrowser.mobile.view

import android.annotation.TargetApi
import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.MailTo
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.core.content.FileProvider
import androidx.webkit.WebViewFeature
import com.arktosbrowser.mobile.AppTheme
import com.arktosbrowser.mobile.BuildConfig
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.adblock.BlocklistLoader
import com.arktosbrowser.mobile.adblock.RankListLoader
import com.arktosbrowser.mobile.adblock.allowlist.AllowListModel
import com.arktosbrowser.mobile.browser.SiteSettings
import com.arktosbrowser.mobile.constant.FILE
import com.arktosbrowser.mobile.controller.UIController
import com.arktosbrowser.mobile.database.javascript.JavaScriptEntry
import com.arktosbrowser.mobile.database.javascript.JavaScriptRepository
import com.arktosbrowser.mobile.di.DatabaseScheduler
import com.arktosbrowser.mobile.di.injector
import com.arktosbrowser.mobile.extensions.resizeAndShow
import com.arktosbrowser.mobile.extensions.snackbar
import com.arktosbrowser.mobile.html.ChangelogPage
import com.arktosbrowser.mobile.js.*
import com.arktosbrowser.mobile.log.Logger
import com.arktosbrowser.mobile.network.ConnectivityGate
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.rust.ArktosCore
import com.arktosbrowser.mobile.search.SafeSearch
import com.arktosbrowser.mobile.ssl.SslState
import com.arktosbrowser.mobile.ssl.SslWarningGate
import com.arktosbrowser.mobile.ssl.SslWarningPreferences
import com.arktosbrowser.mobile.uniffi.RequestVerdict
import com.arktosbrowser.mobile.utils.ERROR_PAGE_ORIGIN
import com.arktosbrowser.mobile.utils.IntentUtils
import com.arktosbrowser.mobile.utils.ProxyUtils
import com.arktosbrowser.mobile.rust.ArktosCore.buildErrorPage
import com.arktosbrowser.mobile.utils.Utils
import com.arktosbrowser.mobile.utils.isGeneratedPageUrl
import com.arktosbrowser.mobile.utils.isSpecialUrl
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.subjects.PublishSubject
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.*
import java.net.URISyntaxException
import java.net.URL
import java.util.*
import java.util.regex.Pattern
import javax.inject.Inject
import javax.net.ssl.HttpsURLConnection
import kotlin.math.abs

class ArktosCookieWebClient(
        private val activity: Activity,
        private val arktosCookieView: ArktosCookieView
) : WebViewClient() {

    private val uiController: UIController
    private val intentUtils = IntentUtils(activity)
    private val emptyResponseByteArray: ByteArray = byteArrayOf()
    private var lastBlockedDomain = ""

    private var knownUndetectedVideoUrls: Array<String> = arrayOf("xvideos.com")

    private var badsslList: MutableList<String> = ArrayList()
    private val httpsUpgradedHosts = mutableSetOf<String>()
    private var badsslErrors: MutableList<SslError> = ArrayList<SslError>()

    private val titleBlocked = activity.getString(R.string.blocked_title)
    private val reloadBlocked = activity.getString(R.string.error_reload)
    private val hintBlocked = activity.getString(R.string.page_blocked_hint)

    private var zoomed = false
    private var first = true
    private var errored = false
    private var initScale = 0f

    // UI01 — non-empty marks a non-LIGHT theme: the Rust page builder
    // emits <html class="arktos-dark"> from that signal. The fragment
    // still appends verbatim; no !important so it can't clobber the
    // card CSS.
    private var color = "<style>body{background-color:#424242;} h1{color:#ffffff;}</style>"

    @Inject internal lateinit var proxyUtils: ProxyUtils
    @Inject internal lateinit var userPreferences: UserPreferences
    @Inject internal lateinit var sslWarningPreferences: SslWarningPreferences
    @Inject internal lateinit var whitelistModel: AllowListModel
    @Inject internal lateinit var logger: Logger
    @Inject internal lateinit var textReflowJs: TextReflow
    @Inject internal lateinit var invertPageJs: InvertPage
    @Inject internal lateinit var darkMode: DarkMode
    @Inject internal lateinit var translatePage: TranslatePage
    @Inject internal lateinit var noAMP: BlockAMP
    @Inject internal lateinit var cookieBlock: CookieBlock
    @Inject internal lateinit var setWidenView: SetWidenViewport
    @Inject internal lateinit var javascriptRepository: JavaScriptRepository
    @Inject @field:DatabaseScheduler internal lateinit var databaseScheduler: Scheduler
    @Inject internal lateinit var blocklistLoader: BlocklistLoader
    @Inject internal lateinit var rankListLoader: RankListLoader
    @Inject internal lateinit var siteSettings: SiteSettings
    @Inject internal lateinit var connectivityGate: ConnectivityGate

    @Volatile private var isRunning = false
    private var zoomScale = 0.0f

    private var currentUrl: String = ""

    /**
     * The original URL of the page currently replaced by a generated error or
     * interstitial page, or null when the WebView is not showing one. Generated
     * pages commit as `data:` URIs, so the tab uses this to keep reporting and
     * persisting the real address (upstream #281).
     */
    var errorPageOriginalUrl: String? = null
        private set

    var sslState: SslState = SslState.None
        private set(value) {
            sslStateSubject.onNext(value)
            field = value
        }

    private var whitelistIntent = false

    /**
     * One SSL warning per page load (upstream #296). [sslWarningDialog] keeps
     * the currently shown prompt so a new page load can cancel its pending
     * [SslErrorHandler] instead of leaving a stale dialog on screen.
     */
    private val sslWarningGate = SslWarningGate()
    private var sslWarningDialog: Dialog? = null

    private val sslStateSubject: PublishSubject<SslState> = PublishSubject.create()

    init {
        activity.injector.inject(this)
        uiController = activity as UIController
        blocklistLoader.ensureInitialized()
        rankListLoader.ensureInitialized()
    }

    fun sslStateObservable(): Observable<SslState> = sslStateSubject.hide()

    fun updatePreferences() {
        blocklistLoader.syncPrefs()
    }

    fun setWhitelist(a: Boolean){
        whitelistIntent = a
    }

    /**
     * The color passed to generated block/error pages — cleared on the
     * light theme, matching the previous behavior.
     */
    private fun blockPageColor(): String {
        if (userPreferences.useTheme == AppTheme.LIGHT ||
            userPreferences.useTheme == AppTheme.ARKTOS_LIGHT) {
            color = ""
        }
        return color
    }

    private fun blockedPageResponse(html: String): WebResourceResponse =
        WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray()))

    /**
     * The offline variant of the generated error page (upstream #323).
     * The reload button navigates back to [url], which re-runs the
     * connectivity check — still offline shows this page again, a
     * restored link loads the page for real.
     */
    private fun offlineErrorPage(url: String): String =
        buildErrorPage(
            blockPageColor(),
            activity.getString(R.string.error_offline_title),
            activity.getString(R.string.error_offline_message),
            activity.getString(R.string.error_page_hint),
            activity.getString(R.string.error_reload),
            true,
            "window.location.href = '" + url + "';"
        )

    private fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(emptyResponseByteArray))

    /**
     * CHLOG01 — serves the bundled changelog out of assets for the
     * `arktos://changelog` chrome URL. `arktos://changelog[ / ]` returns
     * the page itself with the arktos-dark class baked in on dark
     * themes (same signal as the error pages, no JavaScript needed);
     * `arktos://changelog/<asset>` returns bundled artwork referenced
     * by the page. Null for every other request.
     */
    private fun changelogResponse(requestUrl: String): WebResourceResponse? {
        if (requestUrl == ChangelogPage.URL || requestUrl == "${ChangelogPage.URL}/") {
            val html = activity.assets.open("changelog.html").bufferedReader()
                .use { it.readText() }
                .let { ChangelogPage.applyDarkTheme(it, blockPageColor().isNotEmpty()) }
            return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray()))
        }
        val asset = ArktosCore.changelogAssetName(requestUrl)
        if (asset.isNotEmpty()) {
            return try {
                WebResourceResponse(ChangelogPage.mimeForAsset(asset), "UTF-8", activity.assets.open(asset))
            } catch (e: IOException) {
                emptyResponse()
            }
        }
        return null
    }

    private fun adBlockResponse(request: WebResourceRequest, requestUrl: String): WebResourceResponse? {
        if (request.isForMainFrame && request.url.host.toString() != lastBlockedDomain) {
            lastBlockedDomain = request.url.host.toString()
            return blockedPageResponse(ArktosCore.buildBlockPage(blockPageColor(), activity.resources.getString(R.string.page_blocked), activity.resources.getString(R.string.page_blocked_adblocker), requestUrl, activity.resources.getString(R.string.page_blocked_hint), activity.resources.getString(R.string.proceed), activity.resources.getString(R.string.go_back), true))
        } else if (request.isForMainFrame) {
            return null
        }

        return emptyResponse()
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val requestUrl = request.url.toString()
        // arktos:// chrome pages are served before any verdict — the
        // changelog must load even with every blocklist enabled.
        changelogResponse(requestUrl)?.let { return it }
        return when (ArktosCore.requestVerdict(requestUrl, currentUrl, request.isForMainFrame)) {
            // No network route → answer the main-frame request with the
            // offline page right away instead of letting the load fail
            // into the generic error page (upstream #323).
            RequestVerdict.ALLOW ->
                if (request.isForMainFrame
                    && ConnectivityGate.isNetworkPageRequest(requestUrl)
                    && !connectivityGate.isConnected()) {
                    blockedPageResponse(offlineErrorPage(requestUrl))
                } else {
                    super.shouldInterceptRequest(view, request)
                }
            RequestVerdict.BLOCK_ADS ->
                if (whitelistModel.isUrlAllowedAds(currentUrl)) {
                    super.shouldInterceptRequest(view, request)
                } else {
                    adBlockResponse(request, requestUrl)
                }
            RequestVerdict.BLOCK_SITE -> blockedPageResponse(
                ArktosCore.buildErrorPage(blockPageColor(), titleBlocked, "NET::SITE_BLOCKED_BY_LIST", hintBlocked, reloadBlocked, false))
            RequestVerdict.BLOCK_MALWARE -> blockedPageResponse(
                ArktosCore.buildErrorPage(blockPageColor(), activity.getString(R.string.malware_title), activity.getString(R.string.malware_message), hintBlocked, reloadBlocked, false))
            RequestVerdict.BLOCK_LOCAL_NETWORK ->
                if (request.isForMainFrame) {
                    blockedPageResponse(ArktosCore.buildErrorPage(blockPageColor(), titleBlocked, activity.getString(R.string.page_blocked_lan), hintBlocked, reloadBlocked, false))
                } else {
                    emptyResponse()
                }
        }
    }

    @Suppress("OverridingDeprecatedMember")
    @TargetApi(Build.VERSION_CODES.KITKAT_WATCH)
    override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? {
        changelogResponse(url)?.let { return it }
        // No main-frame signal on this legacy overload — page-level
        // verdicts (malware/site block) can't fire here anyway.
        val verdict = ArktosCore.requestVerdict(url, currentUrl, false)
        if (verdict == RequestVerdict.BLOCK_LOCAL_NETWORK ||
            (verdict == RequestVerdict.BLOCK_ADS && !whitelistModel.isUrlAllowedAds(currentUrl))) {
            return emptyResponse()
        }
        if(url.contains("detectPopBlock.js")){
            return emptyResponse()
        }
        return null
    }

    /**
     * PORT01 — userscript parsing lives in the Rust core
     * (`ArktosCore.parseUserScript`); this installs the result. Include
     * patterns come back as Java-regex strings and are compile-checked
     * here, matching upstream's `Pattern.compile` try/catch drop of
     * malformed patterns.
     */
    private fun installExtension(text: String){
        val meta = ArktosCore.parseUserScript(text)
        if (!meta.hasMetadataHeader) {
            Log.d(TAG, "Header (start) parser error")
        }
        val name = meta.name
        if (name == null) {
            logger.log(TAG, "userscript without a @name — not installing")
            return
        }
        val inc = meta.includes
            .filter { pattern -> runCatching { Pattern.compile(pattern) }.isSuccess }
            .joinToString(",")
        val reqs = meta.requires.joinToString(",")

        javascriptRepository.addJavaScriptIfNotExists(JavaScriptEntry(name, "", meta.version, meta.author, inc, "", "", "", meta.code, reqs))
                .subscribeOn(databaseScheduler)
                .subscribe { aBoolean: Boolean? ->
                    if (!aBoolean!!) {
                        logger.log("ArktosCookieWebClient", "error saving script to database")
                    }
                }
    }


    override fun onPageFinished(view: WebView, url: String) {
        if(url.contains(BuildConfig.APPLICATION_ID + "/files/homepage.html")  || url.contains(BuildConfig.APPLICATION_ID + "/files/private.html")) {
            val preferenceArray = arrayListOf(userPreferences.link1, userPreferences.link2, userPreferences.link3, userPreferences.link4)
            for(i in 1..4){
                view.evaluateJavascript("(function() {"
                        + "window.link" + i + "var = " + org.json.JSONObject.quote(preferenceArray[i - 1]) + ";"
                        + "})();", null)
            }
        }

        if(url.contains(".user.js") && view.isShown && userPreferences.javaScriptEnabled){
            val builder = MaterialAlertDialogBuilder(activity)
            builder.setTitle(activity.resources.getString(R.string.install_userscript))
            builder.setMessage(activity.resources.getString(R.string.install_userscript_description))
            builder.setPositiveButton(R.string.yes){ dialog, which ->
                view.evaluateJavascript("""(function() {
                return document.body.innerText;
                })()""".trimMargin()) {
                    val extensionSource = it.substring(1, it.length - 1)
                    installExtension(extensionSource)
                    if(uiController.getTabModel().allTabs.size > 1) {
                        uiController.tabCloseClicked(uiController.getTabModel().positionOf(uiController.getTabModel().currentTab))
                    }
                }

            }
            builder.setNegativeButton("No"){ _, _ ->
            }
            val dialog: androidx.appcompat.app.AlertDialog = builder.create()
            dialog.show()
        }

        if (view.isShown) {
            uiController.updateUrl(url, false)

            uiController.setBackButtonEnabled(view.canGoBack())
            uiController.setForwardButtonEnabled(view.canGoForward())
            view.postInvalidate()
        }

        if(userPreferences.forceZoom){
            // Same guard as WidenViewport.js — create the meta tag when the
            // page has none, instead of throwing on querySelector's null.
            view.loadUrl(
                    "javascript:(function() { var m = document.querySelector('meta[name=\"viewport\"]'); if (!m) { m = document.createElement('meta'); m.name = 'viewport'; document.head.appendChild(m); } m.setAttribute(\"content\",\"width=device-width\"); })();"
            )
        }



        if (view.title == null || view.title.isNullOrEmpty()) {
            arktosCookieView.titleInfo.setTitle(activity.getString(R.string.untitled))
        } else {
            arktosCookieView.titleInfo.setTitle(view.title)
        }
        if (arktosCookieView.invertPage) {
            view.evaluateJavascript(invertPageJs.provideJs(activity), null)
        }
        if (userPreferences.darkWebAddonEnabled(activity) && !WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            view.evaluateJavascript(darkMode.provideJs(activity), null)
        }

        if(userPreferences.cookieBlockEnabled){
            view.evaluateJavascript(cookieBlock.provideJs(activity), null)
        }

        if(userPreferences.noAmp){
            view.evaluateJavascript(noAMP.provideJs(activity), null)
        }
    }

        fun stringContainsItemFromList(inputStr: String, items: Array<String>): Boolean {
            for (i in items.indices) {
                if (inputStr.contains(items[i])) {
                    return true
                }
            }
            return false
        }

    override fun onPageCommitVisible(view: WebView?, url: String?) {
        var jsList = emptyList<JavaScriptEntry>()
        javascriptRepository.lastHundredVisitedJavaScriptEntries()
            .subscribe { list ->
                jsList = list
            }

        for(i in jsList){
            for(x in i.include!!.split(",")) {
                if (view?.url?.matches(x.toRegex()) == true) {
                    GlobalScope.launch {
                        var requirementJS = ""
                        if (!i.requirements.isNullOrEmpty()) {
                            val requirementList = (i.requirements as CharSequence).split(",").map { it.trim() }
                            try {
                                for (req in requirementList) {
                                    val requirementUrl = URL(req)
                                    val urlConnection: HttpsURLConnection = requirementUrl.openConnection() as HttpsURLConnection
                                    val br = BufferedReader(InputStreamReader(urlConnection.getInputStream()))
                                    var line: String?
                                    val builder = java.lang.StringBuilder()
                                    while (br.readLine().also { line = it } != null) {
                                        builder.append(line)
                                    }
                                    requirementJS += builder
                                }

                                activity.runOnUiThread {
                                    view.evaluateJavascript(
                                        requirementJS, null
                                    )
                                }

                            } catch (e: IOException) {
                                e.printStackTrace()
                            }

                            activity.runOnUiThread {
                                view.evaluateJavascript(
                                    i.code.replace("""\"""", """"""")
                                        .replace("\\n", System.lineSeparator())
                                        .replace("\\t", "")
                                        .replace("\\u003C", "<")
                                        .replace("""/"""", """"""")
                                        .replace("""//"""", """/"""")
                                        .replace("""\\'""", """\'""")
                                        .replace("""\\""""", """\"""""), null
                                )
                            }
                        }
                    }

                    break
                }
            }
        }
        super.onPageCommitVisible(view, url)
    }

    override fun onLoadResource(view: WebView?, url: String?) {
        //TODO: explore whether this fixes patchy js load on cached reload
        if(arktosCookieView.toggleDesktop){
            view?.evaluateJavascript(setWidenView.provideJs(activity), null)
        }
        if(userPreferences.cookieBlockEnabled){
            view?.evaluateJavascript(cookieBlock.provideJs(activity), null)
        }
        super.onLoadResource(view, url)
    }

    fun isPackageInstalled(packageName: String, packageManager: PackageManager): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun reloadIncludingErrorPage(view: WebView?){
        if(errored){
            view?.loadUrl(currentUrl)
        }
        else{
            view?.reload()
        }
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        sslWarningDialog?.cancel()
        sslWarningDialog = null
        sslWarningGate.reset()
        if (url != errorPageOriginalUrl && !url.isGeneratedPageUrl()) {
            errorPageOriginalUrl = null
        }

        // Forced SafeSearch backstop (upstream #327): any navigation that
        // reached a search engine without the strict parameter — redirects
        // or script navigations that bypassed shouldOverrideUrlLoading —
        // is reloaded with it, like the HTTPS upgrade below.
        val safeUrl = SafeSearch.enforcedUrl(url, userPreferences.safeSearchForced)
        if (safeUrl != url) {
            view.loadUrl(safeUrl)
            return
        }

        errored = true
        if(isPackageInstalled(activity.resources.getString(R.string.ytdl_package_name), activity.packageManager) && stringContainsItemFromList(url, knownUndetectedVideoUrls)){
            activity.findViewById<FrameLayout>(R.id.download_button).visibility = View.VISIBLE
        }
        else{
            activity.findViewById<FrameLayout>(R.id.download_button).visibility = View.GONE
        }

        zoomed = false
        first = true
        if (!url.isGeneratedPageUrl()) {
            currentUrl = url
        }
        view.settings.javaScriptEnabled = siteSettings.javaScriptEnabled(url)
        // Cookie acceptance is app-global, not per-WebView — resolve the
        // effective value for the loading origin at each page start so a
        // per-site block sticks for the current origin only (#305).
        CookieManager.getInstance().setAcceptCookie(siteSettings.cookiesEnabled(url))

        if(userPreferences.noAmp){
            view.evaluateJavascript(noAMP.provideJs(activity), null)
            if(url.contains("&ampcf=1")){
                view.evaluateJavascript("window.location.replace(" + org.json.JSONObject.quote(url.replace("&ampcf=1", "")) + ");", null)
            }
        }

        if ((userPreferences.forceHTTPSenabled || userPreferences.preferHTTPSenabled) && url.startsWith("http://")) {
            val host = Uri.parse(url).host.orEmpty()
            if (host !in httpsUpgradedHosts) {
                //Upgrade to HTTPS once per host; if the site has no HTTPS the
                //page fails to load and the user retries the original URL,
                //which then falls through without re-upgrading (no ping-pong).
                httpsUpgradedHosts += host
                view.loadUrl(url.replaceFirst("http://", "https://"))
                return
            } else if (userPreferences.forceHTTPSenabled) {
                view.settings.javaScriptEnabled = true
                if(userPreferences.resolvedTheme(activity) == AppTheme.LIGHT ||
                        userPreferences.resolvedTheme(activity) == AppTheme.ARKTOS_LIGHT){
                    color = ""
                }
                val title = activity.getString(R.string.https_title)
                val reload = activity.getString(R.string.error_reload)
                errorPageOriginalUrl = url
                view.loadDataWithBaseURL(ERROR_PAGE_ORIGIN, buildErrorPage(color, title, "NET::ERR_HTTPS_NOT_SUPPORTED", activity.getString(R.string.error_page_hint), reload, true), "text/html; charset=utf-8", "UTF-8", null)
                view.invalidate()
                view.settings.javaScriptEnabled = siteSettings.javaScriptEnabled(url)
            }
        }

            if(url.contains(BuildConfig.APPLICATION_ID + "/files/homepage.html")){
                view.evaluateJavascript("""(function() {
                return localStorage.getItem("shouldUpdate");
                })()""".trimMargin()) {
                    if(it.substring(1, it.length - 1) == "yes"){
                        view.evaluateJavascript("""(function() {
                                return localStorage.getItem("link1");
                                })()""".trimMargin()) {
                            userPreferences.link1 = it.substring(1, it.length - 1)
                            view.evaluateJavascript("""(function() {
                                                    localStorage.setItem("shouldUpdate", "no");
                                                    })()"""){}
                        }
                        view.evaluateJavascript("""(function() {
                            return localStorage.getItem("link2");
                            })()""".trimMargin()) {
                            userPreferences.link2 = it.substring(1, it.length - 1)
                            view.evaluateJavascript("""(function() {
                                            localStorage.setItem("shouldUpdate", "no");
                                            })()"""){}
                        }
                        view.evaluateJavascript("""(function() {
                            return localStorage.getItem("link3");
                            })()""".trimMargin()) {
                            userPreferences.link3 = it.substring(1, it.length - 1)
                            view.evaluateJavascript("""(function() {
                                            localStorage.setItem("shouldUpdate", "no");
                                            })()"""){}
                        }
                        view.evaluateJavascript("""(function() {
                            return localStorage.getItem("link4");
                            })()""".trimMargin()) {
                            userPreferences.link4 = it.substring(1, it.length - 1)
                            view.evaluateJavascript("""(function() {
                                            localStorage.setItem("shouldUpdate", "no");
                                            })()"""){}
                        }
                        Handler(Looper.getMainLooper()).postDelayed({
                            uiController.newTabButtonClicked()
                        }, 100)
                    }
                }
        }
        if(url.contains("https://homepage")){
            uiController.newTabButtonClicked()
            uiController.tabCloseClicked(0)
        }

        // Only set the SSL state if there isn't an error for the current URL.
        if (!badsslList.contains(url)) {
            sslState = if (URLUtil.isHttpsUrl(url)) {
                SslState.Valid
            } else {
                SslState.None
            }
        } else{
            sslState = SslState.Invalid(badsslErrors[badsslList.indexOf(url)])
        }

        arktosCookieView.titleInfo.setFavicon(null)
        if (arktosCookieView.isShown) {
            uiController.updateUrl(url, true)
            uiController.showActionBar()
            uiController.showActionBar()
        }
        uiController.tabChanged(arktosCookieView)
    }

    override fun onReceivedHttpAuthRequest(
            view: WebView,
            handler: HttpAuthHandler,
            host: String,
            realm: String
    ) {
        MaterialAlertDialogBuilder(activity).apply {
            val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_auth_request, null)

            val realmLabel = dialogView.findViewById<TextView>(R.id.auth_request_realm_textview)
            val name = dialogView.findViewById<EditText>(R.id.auth_request_username_edittext)
            val password = dialogView.findViewById<EditText>(R.id.auth_request_password_edittext)

            realmLabel.text = activity.getString(R.string.label_realm, realm)

            setView(dialogView)
            setTitle(R.string.title_sign_in)
            setCancelable(true)
            setPositiveButton(R.string.title_sign_in) { _, _ ->
                val user = name.text.toString()
                val pass = password.text.toString()
                handler.proceed(user.trim(), pass.trim())
                logger.log(TAG, "Attempting HTTP Authentication")
            }
            setNegativeButton(R.string.action_cancel) { _, _ ->
                handler.cancel()
            }
        }.resizeAndShow()
    }

    override fun onReceivedError(webview: WebView, errorCode: Int, error: String, failingUrl: String) {
        if(errorCode != -1) {
            errored = true
            Thread.sleep(500)
            webview.settings.javaScriptEnabled = true
            if (userPreferences.resolvedTheme(activity) == AppTheme.LIGHT ||
                    userPreferences.resolvedTheme(activity) == AppTheme.ARKTOS_LIGHT) {
                color = ""
            }
            val reloadCode = "window.location.href = '" + failingUrl + "';"
            // A connectivity loss gets its own wording so the user sees
            // "no internet connection" rather than a generic failure
            // (upstream #323).
            val offline = ConnectivityGate.isOfflineError(connectivityGate.isConnected(), error)
            val title = activity.getString(
                if (offline) R.string.error_offline_title else R.string.error_title)
            val errorText =
                if (offline) activity.getString(R.string.error_offline_message) else error
            val reload = activity.getString(R.string.error_reload)
            webview.loadUrl("about:blank")
            errorPageOriginalUrl = failingUrl
            webview.loadDataWithBaseURL(failingUrl, buildErrorPage(color, title, errorText, activity.getString(R.string.error_page_hint), reload, true, reloadCode), "text/html", "UTF-8", null)
            uiController.updateUrl(failingUrl, false)
            currentUrl = failingUrl
            webview.settings.javaScriptEnabled = siteSettings.javaScriptEnabled(failingUrl)
        }

    }

    override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) {
        if (first) {
            initScale = newScale
            first=false
            zoomed = false
        }
        else {
            if (newScale>oldScale) {
                zoomed = true
            }
            else {
                if (newScale<initScale) {
                    zoomed = false
                }
            }
        }

      if (view.isShown && arktosCookieView.userPreferences.textReflowEnabled) {
            if (isRunning)
                return
            val changeInPercent = abs(100 - 100 / zoomScale * newScale)
            if (changeInPercent > 2.5f && !isRunning) {
                isRunning = view.postDelayed({
                    zoomScale = newScale

                    val textScale = (newScale / initScale)
                    view.evaluateJavascript(
                        textReflowJs.provideJs(activity).replace(REFLOW_RATIO_TOKEN, textScale.toString())
                    ) { isRunning = false }
                }, 100)
            }
            else{
                view.evaluateJavascript(
                    textReflowJs.provideJs(activity).replace(REFLOW_RATIO_TOKEN, "window.document.documentElement.clientWidth")
                ) { isRunning = false }
            }
        }
    }

    override fun onReceivedSslError(webView: WebView, handler: SslErrorHandler, error: SslError) {

        badsslList.add(error.url)
        badsslErrors.add(error)

        sslState = SslState.Invalid(error)

        when (sslWarningPreferences.recallBehaviorForDomain(webView.url)) {
            SslWarningPreferences.Behavior.PROCEED -> return handler.proceed()
            SslWarningPreferences.Behavior.CANCEL -> return handler.cancel()
            null -> Unit
        }

        val errorCodeMessageCodes = getAllSslErrorMessageCodes(error)

        val stringBuilder = StringBuilder()
        for (messageCode in errorCodeMessageCodes) {
            stringBuilder.append(" - ").append(activity.getString(messageCode)).append('\n')
        }
        val alertMessage = activity.getString(R.string.message_insecure_connection, stringBuilder.toString())

        if(!userPreferences.ssl){
            handler.proceed()
            Toast.makeText(activity, errorCodeMessageCodes.firstOrNull()
                    ?: R.string.message_certificate_untrusted, Toast.LENGTH_SHORT).show()
            return
        }

        // One warning per page load: extra failing requests while the dialog
        // is up are cancelled, and the chosen answer covers the rest of the
        // load (upstream #296 — a dialog per request flickered and froze).
        when (sslWarningGate.claimPrompt()) {
            SslWarningPreferences.Behavior.PROCEED -> return handler.proceed()
            SslWarningPreferences.Behavior.CANCEL -> return handler.cancel()
            null -> Unit
        }

        sslWarningDialog = MaterialAlertDialogBuilder(activity).apply {
            val view = LayoutInflater.from(activity).inflate(R.layout.dialog_ssl_warning, null)
            val dontAskAgain = view.findViewById<CheckBox>(R.id.checkBoxDontAskAgain)
            setTitle(activity.getString(R.string.title_warning))
            setMessage(alertMessage)
            setCancelable(true)
            setView(view)
            setOnCancelListener {
                sslWarningDialog = null
                sslWarningGate.onPromptDismissed(SslWarningPreferences.Behavior.CANCEL)
                handler.cancel()
            }
            setPositiveButton(activity.getString(R.string.action_yes)) { _, _ ->
                if (dontAskAgain.isChecked) {
                    sslWarningPreferences.rememberBehaviorForDomain(webView.url.orEmpty(), SslWarningPreferences.Behavior.PROCEED)
                }
                sslWarningDialog = null
                sslWarningGate.onPromptDismissed(SslWarningPreferences.Behavior.PROCEED)
                handler.proceed()
                webView.clearSslPreferences()
            }
            setNegativeButton(activity.getString(R.string.action_no)) { _, _ ->
                if (dontAskAgain.isChecked) {
                    sslWarningPreferences.rememberBehaviorForDomain(webView.url.orEmpty(), SslWarningPreferences.Behavior.CANCEL)
                }
                sslWarningDialog = null
                sslWarningGate.onPromptDismissed(SslWarningPreferences.Behavior.CANCEL)
                handler.cancel()
            }
        }.resizeAndShow()
    }

    override fun onFormResubmission(view: WebView, dontResend: Message, resend: Message) {
        MaterialAlertDialogBuilder(activity).apply {
            setTitle(activity.getString(R.string.title_form_resubmission))
            setMessage(activity.getString(R.string.message_form_resubmission))
            setCancelable(true)
            setPositiveButton(activity.getString(R.string.action_yes)) { _, _ ->
                resend.sendToTarget()
            }
            setNegativeButton(activity.getString(R.string.action_no)) { _, _ ->
                dontResend.sendToTarget()
            }
        }.resizeAndShow()

    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        shouldOverrideLoading(view, request.url.toString()) || super.shouldOverrideUrlLoading(view, request)

    @Suppress("OverridingDeprecatedMember", "DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
        shouldOverrideLoading(view, url) || super.shouldOverrideUrlLoading(view, url)

    private fun shouldOverrideLoading(view: WebView, url: String): Boolean {
        // Check if configured proxy is available
        if (!proxyUtils.isProxyReady(activity)) {
            // User has been notified
            return true
        }

        val headers = arktosCookieView.requestHeaders

        // Forced SafeSearch (parental controls, upstream #327): a link,
        // redirect or script navigation to a search engine that dropped
        // the strict parameter is re-issued with it restored.
        val safeUrl = SafeSearch.enforcedUrl(url, userPreferences.safeSearchForced)
        if (safeUrl != url) {
            if (headers.isEmpty()) view.loadUrl(safeUrl) else view.loadUrl(safeUrl, headers)
            return true
        }

        if (arktosCookieView.isIncognito || userPreferences.blockIntent && !isMailOrIntent(url, view)) {

            // If we are in incognito, immediately load, we don't want the url to leave the app
            return continueLoadingUrl(view, url, headers)
        }
        if (URLUtil.isAboutUrl(url)) {
            // If this is an about page, immediately load, we don't need to leave the app
            return continueLoadingUrl(view, url, headers)
        }

        return if (isMailOrIntent(url, view) || intentUtils.startActivityForUrl(view, url)) {
            // If it was a mailto: link, or an intent, or could be launched elsewhere, do that
            true
        } else {
            // If none of the special conditions was met, continue with loading the url
            continueLoadingUrl(view, url, headers)
        }

    }

    private fun continueLoadingUrl(webView: WebView, url: String, headers: Map<String, String>): Boolean {
        if (!URLUtil.isNetworkUrl(url)
                && !URLUtil.isFileUrl(url)
                && !URLUtil.isAboutUrl(url)
                && !URLUtil.isDataUrl(url)
                && !URLUtil.isJavaScriptUrl(url)) {
            webView.stopLoading()
            return true
        }
        return when {
            headers.isEmpty() -> false
            else -> {
                webView.loadUrl(url, headers)
                true
            }
        }
    }

    private fun isMailOrIntent(url: String, view: WebView): Boolean {
        if (url.startsWith("mailto:")) {
            val mailTo = MailTo.parse(url)
            val i = Utils.newEmailIntent(mailTo.to, mailTo.subject, mailTo.body, mailTo.cc)
            activity.startActivity(i)
            view.reload()
            return true
        }
        else if (url.startsWith("tel:")){
            val intent = Intent(Intent.ACTION_DIAL)
            intent.data = Uri.parse(url)
            activity.startActivity(intent)
            return true
        }
        else if (url.startsWith("intent://")) {
            // Route through IntentUtils so resolution prefers installed
            // handlers: the pinned app first, then any other installed app
            // that handles the link, then browser_fallback_url, then the
            // store (upstream #316).
            return intentUtils.startActivityForUrl(view, url)
        }
        else if (url.startsWith("gh4b://") || url.contains("://oauth")) {
            val intent = try {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            } catch (ignored: URISyntaxException) {
                null
            }

            if (intent != null) {
                intent.addCategory(Intent.CATEGORY_BROWSABLE)
                intent.component = null
                intent.selector = null
                try {
                    activity.startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    logger.log(TAG, "ActivityNotFoundException")
                }

                return true
            }
        } else if (URLUtil.isFileUrl(url) && !url.isSpecialUrl()) {
            val file = File(url.replace(FILE, ""))

            if (file.exists()) {
                val newMimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ArktosCore.fileExtension(file.toString()))

                val intent = Intent(Intent.ACTION_VIEW)
                intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                val contentUri = FileProvider.getUriForFile(activity, BuildConfig.APPLICATION_ID + ".fileprovider", file)
                intent.setDataAndType(contentUri, newMimeType)

                try {
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    println("ArktosCookieWebClient: cannot open downloaded file")
                }

            } else {
                activity.snackbar(R.string.message_open_download_fail)
            }
            return true
        }
        return false
    }

    private fun getAllSslErrorMessageCodes(error: SslError): List<Int> {
        val errorCodeMessageCodes = ArrayList<Int>(1)

        if (error.hasError(SslError.SSL_DATE_INVALID)) {
            errorCodeMessageCodes.add(R.string.message_certificate_date_invalid)
        }
        if (error.hasError(SslError.SSL_EXPIRED)) {
            errorCodeMessageCodes.add(R.string.message_certificate_expired)
        }
        if (error.hasError(SslError.SSL_IDMISMATCH)) {
            errorCodeMessageCodes.add(R.string.message_certificate_domain_mismatch)
        }
        if (error.hasError(SslError.SSL_NOTYETVALID)) {
            errorCodeMessageCodes.add(R.string.message_certificate_not_yet_valid)
        }
        if (error.hasError(SslError.SSL_UNTRUSTED)) {
            errorCodeMessageCodes.add(R.string.message_certificate_untrusted)
        }
        if (error.hasError(SslError.SSL_INVALID)) {
            errorCodeMessageCodes.add(R.string.message_certificate_invalid)
        }

        return errorCodeMessageCodes
    }

    companion object {

        private const val TAG = "ArktosCookieWebClient"
        private const val REFLOW_RATIO_TOKEN = "%%RATIO%%"

    }
}
