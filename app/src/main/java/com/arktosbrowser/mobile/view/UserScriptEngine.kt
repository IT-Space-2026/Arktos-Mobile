package com.arktosbrowser.mobile.view

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.arktosbrowser.mobile.database.javascript.JavaScriptRepository
import com.arktosbrowser.mobile.di.MainScheduler
import com.arktosbrowser.mobile.di.NetworkScheduler
import com.arktosbrowser.mobile.log.Logger
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.uniffi.ArktosStore
import com.arktosbrowser.mobile.userscript.UserScriptBootstrap
import com.arktosbrowser.mobile.view.ProxyUtils
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.disposables.CompositeDisposable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Modern userscript runtime — the Greasemonkey engine (USCRIPT01).
 *
 * Two androidx.webkit capabilities replace the old
 * `onPageCommitVisible` + `evaluateJavascript` injection:
 *
 *  * `DOCUMENT_START_SCRIPT` (WebView 105+) —
 *    `addDocumentStartJavaScript` registers a bootstrap that runs
 *    before page scripts on every load, so `@run-at document-start`
 *    actually works and userscripts cannot be missed by racing the
 *    commit callback. URL gating happens inside the bootstrap
 *    (include/exclude regexes ship with it); `@require` bodies are
 *    inlined at build time through OkHttp so they honour the
 *    configured proxy.
 *
 *  * `WEB_MESSAGE_LISTENER` (WebView 105+) — a `window.arktosGM`
 *    postMessage bridge that backs `GM_xmlhttpRequest` (proxied
 *    through OkHttp), `GM_setClipboard` and `GM_openInTab`.
 *    `GM_setValue`/`GM_getValue`/`GM_deleteValue`/`GM_listValues`
 *    run off a per-script snapshot embedded in the bootstrap — the
 *    synchronous spec signature needs no round-trip — while writes
 *    are persisted to the Rust `gm_value` store.
 *
 * Everything is best-effort: when a WebView build lacks a feature the
 * corresponding capability degrades (the web client keeps the legacy
 * onPageCommitVisible path as the injection fallback — see
 * [documentStartSupported]).
 *
 * One engine instance lives per `ArktosCookieWebClient`; the
 * registered bootstrap is rebuilt when the script set changes —
 * [notifyScriptsChanged] bumps a process-wide counter and each tab
 * refreshes lazily on its next navigation.
 */
class UserScriptEngine @Inject constructor(
    private val javascriptRepository: JavaScriptRepository,
    private val store: ArktosStore,
    private val userPreferences: UserPreferences,
    private val logger: Logger,
    private val context: Context,
    @NetworkScheduler private val networkScheduler: Scheduler,
    @MainScheduler private val mainScheduler: Scheduler,
    // ENG05 — the engine-neutral bootstrap builder/policy store shared
    // with the GeckoView backend; the WebView-side GM bridge consults
    // `bootstrap.policies` before honoring any op (SEC06).
    private val bootstrap: UserScriptBootstrap
) {

    private var registeredScript: ScriptHandler? = null
    private var appliedVersion = -1L
    private val disposables = CompositeDisposable()

    private val http = OkHttpClient()
    private val inflight = ConcurrentHashMap<Long, Call>()
    private val openInTab = AtomicReference<(String) -> Unit> {}

    /**
     * Attach the runtime to [webView]. Registers the `arktosGM`
     * message listener when supported and installs the document-start
     * bootstrap for the current script set.
     */
    fun register(webView: WebView, onOpenInTab: (String) -> Unit) {
        openInTab.set(onOpenInTab)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, BRIDGE_OBJECT, setOf("*"), bridge)
        }
        refreshScripts(webView)
    }

    /** Rebuild the bootstrap when another tab or settings changed the script set. */
    fun refreshIfStale(webView: WebView) {
        if (appliedVersion != changeCounter.get()) {
            refreshScripts(webView)
        }
    }

    private fun refreshScripts(webView: WebView) {
        if (!documentStartSupported()) return
        appliedVersion = changeCounter.get()
        disposables.add(
            javascriptRepository.lastHundredVisitedJavaScriptEntries()
                .subscribeOn(networkScheduler)
                .map { entries ->
                    bootstrap.build(entries, UserScriptBootstrap.Transport.WEB_VIEW) ?: ""
                }
                .observeOn(mainScheduler)
                .subscribe({ bootstrap ->
                    registeredScript?.remove()
                    registeredScript = bootstrap.takeIf { it.isNotEmpty() }?.let {
                        WebViewCompat.addDocumentStartJavaScript(webView, it, setOf("*"))
                    }
                }, { error ->
                    logger.log(TAG, "userscript bootstrap failed: $error")
                })
        )
    }

    private fun reply(replyProxy: JavaScriptReplyProxy, payload: JSONObject) {
        mainScheduler.scheduleDirect {
            runCatching { replyProxy.postMessage(payload.toString()) }
        }
    }

    /**
     * SEC06 — true when [script] is installed and its include/exclude
     * rules match the posting page (delegated to the shared
     * [UserScriptBootstrap] policy store).
     */
    private fun scriptPermitted(script: String, sourceOrigin: Uri?, pageUrl: String?): Boolean =
        bootstrap.permitted(script, sourceOrigin, pageUrl)

    private val bridge = WebViewCompat.WebMessageListener { view, message, sourceOrigin, isMainFrame, replyProxy ->
        val json = runCatching { JSONObject(message.data ?: return@WebMessageListener) }
            .getOrNull() ?: return@WebMessageListener
        val pageUrl = if (isMainFrame) view.url else null
        when (json.optString("op")) {
            "set" -> {
                val script = json.optString("script")
                val key = json.optString("key")
                val value = json.optString("value")
                if (scriptPermitted(script, sourceOrigin, pageUrl)) {
                    networkScheduler.scheduleDirect {
                        runCatching { store.gmSetValue(script, key, value) }
                    }
                }
            }
            "del" -> {
                val script = json.optString("script")
                val key = json.optString("key")
                if (scriptPermitted(script, sourceOrigin, pageUrl)) {
                    networkScheduler.scheduleDirect {
                        runCatching { store.gmDeleteValue(script, key) }
                    }
                }
            }
            "xhr" -> {
                val id = json.optLong("id", -1)
                networkScheduler.scheduleDirect {
                    performXhr(id, json, replyProxy, sourceOrigin, pageUrl)
                }
            }
            "xhr-abort" -> inflight.remove(json.optLong("id", -1))?.cancel()
            "clipboard" -> {
                val text = json.optString("text")
                if (scriptPermitted(json.optString("script"), sourceOrigin, pageUrl)) {
                    mainScheduler.scheduleDirect {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("userscript", text))
                    }
                }
            }
            "openTab" -> {
                val url = json.optString("url")
                if (scriptPermitted(json.optString("script"), sourceOrigin, pageUrl)
                    && (url.startsWith("http://") || url.startsWith("https://"))) {
                    mainScheduler.scheduleDirect { openInTab.get().invoke(url) }
                }
            }
        }
    }

    private fun rejectXhr(replyProxy: JavaScriptReplyProxy, id: Long, reason: String) {
        reply(replyProxy, JSONObject()
            .put("id", id).put("final", true).put("error", true)
            .put("status", 0).put("statusText", reason))
    }

    private fun performXhr(
        id: Long,
        json: JSONObject,
        replyProxy: JavaScriptReplyProxy,
        sourceOrigin: Uri?,
        pageUrl: String?
    ) {
        val script = json.optString("script")
        if (!scriptPermitted(script, sourceOrigin, pageUrl)) {
            rejectXhr(replyProxy, id, "unknown userscript or origin")
            return
        }
        val url = json.optString("url")
        val connects = bootstrap.connectsOf(script)
        if (!GmConnectPolicy.hostAllowed(connects, sourceOrigin?.host, GmConnectPolicy.hostOf(url))) {
            logger.log(TAG, "GM xhr blocked by @connect: $script -> $url")
            rejectXhr(replyProxy, id, "blocked by @connect")
            return
        }
        val request = try {
            val builder = Request.Builder().url(url)
            val headers = json.optJSONObject("headers")
            headers?.keys()?.forEach { name ->
                builder.header(name, headers.optString(name))
            }
            val method = json.optString("method", "GET").uppercase()
            val data = json.optString("data").takeUnless { it.isEmpty() || it == "null" }
            if (data != null && method != "GET" && method != "HEAD") {
                builder.method(method, data.toRequestBody("text/plain".toMediaTypeOrNull()))
            } else {
                builder.method(method, null)
            }
            builder.build()
        } catch (e: Exception) {
            reply(replyProxy, JSONObject()
                .put("id", id).put("final", true).put("error", true).put("status", 0))
            return
        }
        val proxy = ProxyUtils.connectionProxy(userPreferences)
        val client = http.newBuilder()
            .apply { if (proxy != null) proxy(proxy) }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val call = client.newCall(request)
        inflight[id] = call
        try {
            call.execute().use { resp ->
                val body = resp.body?.string()?.take(MAX_XHR_BODY) ?: ""
                val headerBlock = resp.headers.toMultimap()
                    .flatMap { (name, values) -> values.map { "$name: $it" } }
                    .joinToString("\r\n")
                reply(replyProxy, JSONObject()
                    .put("id", id).put("final", true)
                    .put("status", resp.code)
                    .put("statusText", resp.message)
                    .put("responseText", body)
                    .put("response", body)
                    .put("responseHeaders", headerBlock)
                    .put("finalUrl", resp.request.url.toString())
                    .put("readyState", 4))
            }
        } catch (e: Exception) {
            reply(replyProxy, JSONObject()
                .put("id", id).put("final", true).put("error", true)
                .put("status", 0).put("statusText", ""))
        } finally {
            inflight.remove(id)
        }
    }

    companion object {
        private const val TAG = "UserScriptEngine"
        private const val BRIDGE_OBJECT = "arktosGM"
        private const val MAX_XHR_BODY = 4 * 1024 * 1024

        /** Bumped whenever the script set changes; each tab re-registers lazily. */
        private val changeCounter = AtomicLong()

        fun notifyScriptsChanged() {
            changeCounter.incrementAndGet()
        }

        /**
         * Current script-set version — the GeckoView userscript host
         * (ENG05) polls this the same way WebView tabs do.
         */
        fun scriptSetVersion(): Long = changeCounter.get()

        /**
         * Whether this WebView can do document-start injection. When
         * false the web client keeps the legacy evaluateJavascript path.
         */
        fun documentStartSupported(): Boolean =
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    }
}
