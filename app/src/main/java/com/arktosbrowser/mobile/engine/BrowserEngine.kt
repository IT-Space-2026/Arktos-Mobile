package com.arktosbrowser.mobile.engine

// ENG-M01 — engine-neutral browser-engine interface sketch (UNUSED).
//
// Groundwork for a future selectable-engine effort, mirroring
// src/engine/engineinterface.h in IT-Space-2026/arktos (desktop).
// The WebView host stays the only backend today; a Servo backend may
// become viable once the desktop ENG03 spike findings are validated
// against the Android embedding surface.  Nothing in the app
// references this package yet — it exists only to pin the boundary
// every backend must implement.  The coupling audit lives in
// .devin/ENGINE.md.
//
// Design rules (carried over from the desktop sketch):
//  * Only Kotlin/stdlib types cross the boundary.  An engine-specific
//    type (WebView, WebResourceRequest, Servo handle) reaching this
//    file is a smell — wrap it in the backend instead.
//  * Callbacks, not suspend/Flow, so the semantics match engines that
//    report asynchronously on their own threads.
//  * Everything here is a sketch — names and granularity will move
//    when the real refactor happens.

import android.view.View

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

// ---- page ---------------------------------------------------------------

// One tab's worth of engine surface.  Today ArktosCookieView is this
// object informally; an engine refactor would make it implement this
// interface and add a Servo-backed sibling.
interface Page {

    fun load(url: String)
    fun stop()
    fun reload()
    val url: String

    val canGoBack: Boolean
    val canGoForward: Boolean
    fun back()
    fun forward()

    fun historyItems(): List<HistoryEntry>
    fun goToHistoryEntry(entry: HistoryEntry)

    var zoomFactor: Float
    val scrollPosition: Pair<Float, Float>

    fun findText(subString: String, backwards: Boolean)
    fun runJavaScript(source: String, callback: (String?) -> Unit)

    fun insertScript(script: UserScript)
    fun removeScript(name: String)
    fun scripts(): List<UserScript>

    fun download(url: String)

    val isLoading: Boolean
    val recentlyAudible: Boolean
    val isOffTheRecord: Boolean

    // Named, stringly-typed on purpose: engine settings surfaces do
    // not line up 1:1 (Servo has no per-page JS toggle, e.g.), so the
    // backend decides what it honours.
    fun setPageAttribute(name: String, on: Boolean)

    fun setLifecycleState(state: LifecycleState)
    val lifecycleState: LifecycleState

    // -1 where the engine exposes no renderer PID (Servo today).
    fun renderProcessId(): Long

    // ---- signals -------------------------------------------------------
    // The Kotlin shell wires these like the desktop wires Qt signals.

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
}

// ---- profile -------------------------------------------------------------

enum class StorageArea(val flag: Int) {
    Cookies(1),
    DiskCache(2),
    LocalStorage(4),
    IndexedDb(8),
    SessionOnly(16),
    All(0x1F)
}

data class ProfileOptions(
    val storageName: String?,
    val offTheRecord: Boolean
)

// What a profile is: a cookie/storage partition + request policy +
// user agent + script collection.  Incognito tabs = OTR profile.
interface Profile {
    val isOffTheRecord: Boolean
    val storageName: String

    var userAgent: String

    var requestPolicy: RequestPolicy?

    fun insertScript(script: UserScript)
    fun removeScript(name: String)
    fun scripts(): List<UserScript>

    fun clear(areas: Set<StorageArea>)

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
    val id: String              // "webview" | "servo" | ...
    val displayName: String     // "Android WebView"
    val capabilities: Capabilities

    fun initialize(): Boolean

    fun createProfile(options: ProfileOptions): Profile
    fun createPage(profile: Profile): Page

    // Android counterpart of the desktop's createView: hands the
    // platform widget to the UI shell.
    fun createView(page: Page): View?

    // Non-empty when createPage cannot work (e.g. Tor on Servo —
    // no SOCKS support upstream, see desktop .devin/SERVO-SPIKE.md).
    fun pageUnavailableReason(): String = ""
    fun engineNote(): String = ""
}
