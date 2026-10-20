package com.arktosbrowser.mobile.engine

// ENG-M01/ENG-M02 — engine-neutral browser-engine interface, mirroring
// src/engine/engineinterface.h in IT-Space-2026/arktos (desktop).
//
// This package is the boundary every backend implements. Today the
// only backend is the Android WebView adapter in view/ (WebViewEngine
// + WebViewProfile; ArktosCookieView is the Page). The app registers
// it through [Engines] and talks to these types — no file outside the
// adapter may reference the platform webview API (the audit lives in
// .devin/ENGINE.md, enforced by .devin/check-engine-audit.sh).
//
// Design rules (carried over from the desktop sketch):
//  * Only Kotlin/stdlib + platform-neutral types cross the boundary.
//    An engine-specific type (WebView, WebResourceRequest, Servo
//    handle) reaching this file is a smell — wrap it in the backend.
//    The platform webview package in this package is a hard violation.
//  * Callbacks, not suspend/Flow, so the semantics match engines that
//    report asynchronously on their own threads.
//  * Granularity follows the WebView reality today; names will move
//    again when a second backend lands.

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslCertificate
import android.os.Bundle
import android.view.View
import com.arktosbrowser.mobile.ssl.SslState
import io.reactivex.rxjava3.core.Observable

// ---- load / navigation -------------------------------------------------

enum class LoadStatus {
    Started,
    Succeeded,
    Failed
}

enum class NavigationType {
    LinkClicked,
    Typed,
    FormSubmitted,
    BackOrForward,
    Reload,
    Redirect,
    Other
}

// ---- request policy ----------------------------------------------------

// Engine-neutral resource classification.  The blocklist engine is
// keyed on these values, so the enum must survive an engine swap —
// each backend maps its own type set in.
enum class ResourceType {
    MainFrame,
    SubFrame,
    Stylesheet,
    Script,
    Image,
    FontResource,
    SubResource,
    Object,
    Media,
    Worker,
    SharedWorker,
    Prefetch,
    Favicon,
    Xhr,
    Ping,
    ServiceWorker,
    CspReport,
    PluginResource,
    NavigationPreload,
    WebSocket,
    Unknown
}

enum class RequestAction {
    Allow,
    Block,
    Redirect
}

data class NavigationRequest(
    val url: String,
    val isMainFrame: Boolean,
    val isRedirect: Boolean,
    val type: NavigationType,
    val resourceType: ResourceType
)

data class RequestDecision(
    val action: RequestAction,
    val redirectUrl: String? = null
) {
    companion object {
        val ALLOW = RequestDecision(RequestAction.Allow)
        val BLOCK = RequestDecision(RequestAction.Block)
    }
}

// Navigations and subresource requests are gated through this.
// Implementations must be thread-safe: engines fire request decisions
// on IO/net threads, not the main thread.
interface RequestPolicy {
    fun decide(request: NavigationRequest): RequestDecision
}

// ---- user scripts ------------------------------------------------------

enum class InjectionPoint {
    DocumentStart,
    DocumentEnd,
    Idle
}

data class UserScript(
    val name: String,
    val source: String,
    val injectionPoint: InjectionPoint,
    val forMainFrameOnly: Boolean
)

// ---- engine-neutral callback types --------------------------------------

// Drop-in stand-in for the WebView ValueCallback type so UI plumbing
// (file chooser, JS results) never exposes the WebView type. Same
// shape on purpose: `onReceiveValue(value)`.
fun interface ValueCallback<in T> {
    fun onReceiveValue(value: T?)
}

// Stand-in for WebChromeClient.CustomViewCallback: the engine calls
// [onCustomViewHidden] when the page leaves fullscreen on its own.
fun interface CustomViewCallback {
    fun onCustomViewHidden()
}

// ---- misc value types --------------------------------------------------

data class FindResult(
    val activeMatchOrdinal: Int,
    val numberOfMatches: Int,
    val done: Boolean
)

data class CertificateErrorInfo(
    val url: String,
    val errorDescription: String,
    val isOverridable: Boolean
)

enum class CertificateErrorAction {
    Cancel,
    Ignore,
    Isolate
}

data class ContextMenuInfo(
    val linkUrl: String?,
    val imageUrl: String?,
    val selectedText: String?,
    val isEditable: Boolean
)

enum class StandardAction {
    Back,
    Forward,
    Stop,
    Reload,
    Cut,
    Copy,
    Paste,
    SelectAll
}

data class HistoryEntry(
    val index: Int,
    val url: String,
    val title: String,
    val lastVisitTime: Long
)

enum class LifecycleState {
    Active,
    Frozen,
    Discarded
}

// ---- downloads ---------------------------------------------------------

interface DownloadRequest {
    val url: String
    val suggestedFileName: String
    val mimeType: String
    val isPaused: Boolean
    val receivedBytes: Long
    val totalBytes: Long
    val isFinished: Boolean

    fun accept(filePath: String)
    fun cancel()
    fun pause()
    fun resume()
}

// ---- named page attributes ----------------------------------------------

// Well-known names for [Page.setPageAttribute]. Backends honour what
// they can and ignore the rest — the stringly-typed surface exists
// because engine settings do not line up 1:1.
object PageAttributes {
    const val JAVASCRIPT_ENABLED = "javascript.enabled"
}

// ---- page ---------------------------------------------------------------

// Post-construction hook run on a freshly created [Page], e.g. loading
// the homepage or restoring serialized state. The WebView backend runs
// it once the underlying view is fully initialized; [headers] are the
// request headers the load should carry.
fun interface PageInitializer {
    fun initialize(page: Page, headers: Map<String, String>)
}

data class PageRequest(
    val activity: Activity,
    val profile: Profile,
    val initializer: PageInitializer
)

// One tab's worth of engine surface. ArktosCookieView implements this
// for the WebView backend.
interface Page {

    fun load(url: String, headers: Map<String, String> = emptyMap())
    fun loadData(
        baseUrl: String?,
        data: String,
        mimeType: String,
        encoding: String,
        historyUrl: String? = null
    )
    fun stop()
    fun reload()
    val url: String

    val canGoBack: Boolean
    val canGoForward: Boolean
    fun back()
    fun forward()

    fun historyItems(): List<HistoryEntry>
    fun goToHistoryEntry(entry: HistoryEntry)

    // Serialized page state for tab persistence. Engines without a
    // WebView-style state blob may return an empty bundle.
    fun saveState(): Bundle
    fun restoreState(state: Bundle)

    var zoomFactor: Float
    val scrollPosition: Pair<Float, Float>

    fun findText(subString: String, backwards: Boolean)
    fun runJavaScript(source: String, callback: (String?) -> Unit)

    fun insertScript(script: UserScript)
    fun removeScript(name: String)
    fun scripts(): List<UserScript>

    fun download(url: String)

    // Clear the engine's shared page cache for this page.
    fun clearCache()

    val isLoading: Boolean
    val recentlyAudible: Boolean
    val isOffTheRecord: Boolean

    // Named, stringly-typed on purpose: engine settings surfaces do
    // not line up 1:1 (Servo has no per-page JS toggle, e.g.), so the
    // backend decides what it honours. See [PageAttributes].
    fun setPageAttribute(name: String, on: Boolean)

    fun setLifecycleState(state: LifecycleState)
    val lifecycleState: LifecycleState

    // -1 where the engine exposes no renderer PID (Servo today).
    fun renderProcessId(): Long

    // ---- signals -------------------------------------------------------
    // The Kotlin shell wires these like the desktop wires Qt signals.
    // Signals without a backing WebView hook stay unset on the WebView
    // backend (cert errors and permission prompts still run through the
    // existing dialog machinery).

    var onLoadStatusChanged: ((LoadStatus) -> Unit)?
    var onUrlChanged: ((String) -> Unit)?
    var onTitleChanged: ((String) -> Unit)?
    var onProgressChanged: ((Int) -> Unit)?       // -1 where unsupported (Servo: spinner only)
    var onFaviconChanged: (() -> Unit)?
    var onDownloadRequested: ((DownloadRequest) -> Unit)?
    var onCertificateError: ((CertificateErrorInfo) -> CertificateErrorAction)?
    var onPermissionRequest: ((String) -> Boolean)?
    var onContextMenu: ((ContextMenuInfo) -> Unit)?
    var onCrashed: (() -> Unit)?

    // ---- shell-facing tab surface -------------------------------------
    // The Kotlin tab shell (TabsManager, BrowserPresenter,
    // BrowserActivity, the tab lists) drives these members on whatever
    // Page the backend produced, so they are part of the contract — a
    // backend without an equivalent stubs them (GeckoView has no
    // favicon delegate → null; no WebView-style cert object → null).

    /** Stable id used by the tab-list adapters (View.generateViewId()). */
    val id: Int

    /** Whether this page is the currently displayed tab. */
    var isForegroundTab: Boolean

    /** Marks tabs opened from an external intent (close-on-back UX). */
    var isNewTab: Boolean

    val title: String
    val favicon: Bitmap?
    val sslCertificate: SslCertificate?
    val isShown: Boolean
    val progress: Int

    /** Security state of the current page; `SslState.None` where unsupported. */
    fun currentSslState(): SslState

    /** Emits subsequent security-state changes; `Observable.never()` where unsupported. */
    fun sslStateObservable(): Observable<SslState>

    /** Requests the blocker stopped on this page load — the shield badge count. */
    fun blockedThisPage(): Int

    // ---- lifecycle the shell drives -----------------------------------

    /** Re-apply preference-dependent page settings (runs on resume). */
    fun initializePreferences()

    fun onPause()
    fun onResume()
    fun onDestroy()
    fun pauseTimers()
    fun resumeTimers()

    // ---- focus / visibility --------------------------------------------

    fun requestFocus()
    fun setVisibility(visible: Int)

    // ---- navigation conveniences ----------------------------------------

    /**
     * Load [url] through the page's own policy gate (proxy readiness,
     * forced SafeSearch, base request headers) — the shell's normal
     * navigate call; [load] stays the verbatim-headers variant used by
     * initializers.
     */
    fun loadUrl(url: String)

    fun goBack() = back()
    fun goForward() = forward()

    fun loadHomePage()
    fun loadBookmarkPage()
    fun loadDownloadsPage()
    fun loadHistoryPage()

    /** Start a find-in-page session; backends without it return a no-op result. */
    fun find(text: String): FindResults

    /** Print the page via the system print service; no-op where unsupported. */
    fun createWebPagePrint()
}

// ---- profile -------------------------------------------------------------

enum class StorageArea(val flag: Int) {
    Cookies(1),
    DiskCache(2),
    LocalStorage(4),
    IndexedDb(8),
    SessionOnly(16),
    FormData(32),
    HttpAuth(64),
    SslDecisions(128),
    AppPrivateData(256),
    All(0x1FF)
}

data class ProfileOptions(
    val storageName: String?,
    val offTheRecord: Boolean
)

// What a profile is: a cookie/storage partition + request policy +
// user agent + script collection.  Incognito tabs = OTR profile.
//
// The cookie surface lives here because the profile IS the cookie
// partition — get/set accept-cookie and per-URL cookie access route
// through it so callers never touch the engine's cookie manager.
interface Profile {
    val isOffTheRecord: Boolean
    val storageName: String

    var userAgent: String

    var requestPolicy: RequestPolicy?

    fun insertScript(script: UserScript)
    fun removeScript(name: String)
    fun scripts(): List<UserScript>

    fun clear(areas: Set<StorageArea>)

    // Wipe one origin's site data (cookies for the origin + DOM
    // storage), e.g. the site panel's "clear site data".
    fun clearSiteData(url: String)

    // Cookie surface -------------------------------------------------------

    var acceptsCookies: Boolean
    fun getCookie(url: String): String?
    fun setCookie(url: String, value: String)
    fun flushCookies()

    fun setProfileAttribute(name: String, value: Any?)
}

// ---- backend -------------------------------------------------------------

data class Capabilities(
    val devTools: Boolean,
    val perPageSettings: Boolean,
    val findInPage: Boolean,
    val lifecycleManagement: Boolean,
    val socksProxy: Boolean,
    val extensions: Boolean,
    val downloadDelegate: Boolean,
    val certificateErrorDetails: Boolean
)

interface Backend {
    val id: String              // "webview" | "servo" | "gecko" | ...
    val displayName: String     // "Android WebView"
    val capabilities: Capabilities

    // Process-wide one-time setup (data-directory suffix, debug
    // switches). Must run before the first page is created.
    fun initialize(context: Context): Boolean

    // The UA string the engine would emit with no override — used to
    // resolve the "default UA" preference outside the adapter.
    fun systemUserAgent(context: Context): String

    // Process-wide default storage partition. On WebView this is the
    // shared CookieManager/WebStorage partition; incognito runs in its
    // own process, so the same object is the incognito partition there.
    val defaultProfile: Profile

    fun createProfile(options: ProfileOptions): Profile
    fun createPage(request: PageRequest): Page

    // Android counterpart of the desktop's createView: hands the
    // platform widget to the UI shell.
    fun createView(page: Page): View?

    // Non-empty when createPage cannot work (e.g. Tor on Servo —
    // no SOCKS support upstream, see desktop .devin/SERVO-SPIKE.md).
    fun pageUnavailableReason(): String = ""
    fun engineNote(): String = ""
}
