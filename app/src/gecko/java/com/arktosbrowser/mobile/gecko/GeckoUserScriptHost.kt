// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.gecko

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import com.arktosbrowser.mobile.database.javascript.JavaScriptRepository
import com.arktosbrowser.mobile.di.MainScheduler
import com.arktosbrowser.mobile.di.NetworkScheduler
import com.arktosbrowser.mobile.log.Logger
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.uniffi.ArktosStore
import com.arktosbrowser.mobile.userscript.UserScriptBootstrap
import com.arktosbrowser.mobile.view.GmConnectPolicy
import com.arktosbrowser.mobile.view.ProxyUtils
import com.arktosbrowser.mobile.view.UserScriptEngine
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.disposables.CompositeDisposable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

/**
 * ENG05 — the GeckoView userscript surface: the same engine-neutral
 * [UserScriptBootstrap] the WebView backend injects via
 * `addDocumentStartJavaScript`, delivered here through a built-in
 * WebExtension (`assets/extensions/arktos_userscripts/`).
 *
 * Channels:
 *  * App → extension: a persistent native port
 *    (`runtime.connectNative("browser")` opened by the extension's
 *    background page) carries `{type:"sync", bootstrap}`; the
 *    background registers it via `browser.contentScripts.register`
 *    at `document_start` for `<all_urls>`.
 *  * Page → app: the bootstrap calls
 *    `browser.runtime.sendNativeMessage` straight from the content
 *    script; GeckoView routes those to the per-session
 *    `WebExtension.SessionController` message delegate installed by
 *    [attachTo], stamping `sender.url`/`isTopLevel` with the real
 *    page frame — the same GM bridge ops the WebView's
 *    `window.arktosGM` handles (SEC06 policy checks included).
 *
 * The process-wide script set is re-synced lazily —
 * [UserScriptEngine.scriptSetVersion] is the same staleness counter
 * the WebView tabs poll.
 */
class GeckoUserScriptHost(
    private val engine: GeckoEngine,
    private val javascriptRepository: JavaScriptRepository,
    private val bootstrap: UserScriptBootstrap,
    private val store: ArktosStore,
    private val userPreferences: UserPreferences,
    private val logger: Logger,
    private val context: Context,
    @NetworkScheduler private val networkScheduler: Scheduler,
    @MainScheduler private val mainScheduler: Scheduler
) {

    private var extension: WebExtension? = null
    private var port: WebExtension.Port? = null
    private var appliedVersion = -1L
    private val disposables = CompositeDisposable()

    // Sessions awaiting the extension install result — weakly held so
    // closed tabs drop out on their own.
    private val pendingSessions =
        java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(java.util.WeakHashMap<GeckoSession, Boolean>())
        )

    private val http = OkHttpClient()
    private val inflight = ConcurrentHashMap<Long, Call>()
    private val openInTab = AtomicReference<(String) -> Unit> {}

    /** Set by each [GeckoPage] so `GM_openInTab` reaches the tab shell. */
    fun setOpenInTab(onOpenInTab: (String) -> Unit) {
        openInTab.set(onOpenInTab)
    }

    /**
     * Called by each [GeckoPage] after session creation: installs the
     * per-session `SessionController` message delegate that receives
     * the bootstrap's `sendNativeMessage` ops. Queued when the
     * extension hasn't resolved yet — [install] attaches the pending
     * set once it does.
     */
    fun attachTo(session: GeckoSession) {
        val ext = extension
        if (ext == null) {
            pendingSessions.add(session)
            return
        }
        session.webExtensionController
            .setMessageDelegate(ext, contentMessageDelegate, NATIVE_APP)
    }

    /**
     * Install (or confirm) the built-in userscript extension and hook
     * the native-messaging delegate. Idempotent — `ensureBuiltIn`
     * resolves fast when the extension is already present.
     */
    fun install() {
        engine.runtime.webExtensionController
            .ensureBuiltIn(EXTENSION_LOCATION, EXTENSION_ID)
            .accept({ ext ->
                if (ext == null) {
                    logger.log(TAG, "userscript extension install returned null")
                    return@accept
                }
                extension = ext
                // The extension-level delegate receives the background
                // page's connectNative port (the script-set sync push).
                ext.setMessageDelegate(portDelegate, NATIVE_APP)
                // Sessions created before the install resolved get
                // their per-session message delegate now.
                pendingSessions.forEach { attachTo(it) }
                pendingSessions.clear()
                // Private-mode sessions must run scripts too — the
                // incognito process has its own runtime, so allow the
                // extension into private browsing.
                engine.runtime.webExtensionController
                    .setAllowedInPrivateBrowsing(ext, true)
                syncScripts()
            }, { error ->
                logger.log(TAG, "userscript extension install failed: $error")
            })
    }

    /** Re-push the script set when another tab changed it. */
    fun syncIfStale() {
        if (extension != null && appliedVersion != UserScriptEngine.scriptSetVersion()) {
            syncScripts()
        }
    }

    private fun syncScripts() {
        appliedVersion = UserScriptEngine.scriptSetVersion()
        disposables.add(
            javascriptRepository.lastHundredVisitedJavaScriptEntries()
                .subscribeOn(networkScheduler)
                .map { entries ->
                    bootstrap.build(entries, UserScriptBootstrap.Transport.GECKO) ?: ""
                }
                .observeOn(mainScheduler)
                .subscribe({ code ->
                    port?.postMessage(
                        JSONObject().put("type", "sync").put("bootstrap", code)
                    )
                }, { error ->
                    logger.log(TAG, "userscript sync failed: $error")
                })
        )
    }

    /** Extension-level delegate: the background's `connectNative` port. */
    private val portDelegate = object : WebExtension.MessageDelegate {
        override fun onConnect(p: WebExtension.Port) {
            port = p
            p.setDelegate(object : WebExtension.PortDelegate {
                override fun onPortMessage(message: Any, port: WebExtension.Port) = Unit
                override fun onDisconnect(port: WebExtension.Port) {
                    if (this@GeckoUserScriptHost.port == port) {
                        this@GeckoUserScriptHost.port = null
                    }
                }
            })
            syncScripts()
        }
    }

    /**
     * Per-session delegate: content-script `sendNativeMessage` calls
     * land here with `sender.url`/`isTopLevel`/`session` describing the
     * real page frame.
     */
    private val contentMessageDelegate = object : WebExtension.MessageDelegate {
        override fun onMessage(
            nativeApp: String,
            message: Any,
            sender: WebExtension.MessageSender
        ): GeckoResult<Any>? {
            val json = message as? JSONObject ?: return null
            return handleOp(json, sender)
        }
    }

    /**
     * The GM bridge ops — a direct port of the WebView path in
     * `view/UserScriptEngine`: installed-script + origin gating first
     * (SEC06), `@connect` enforcement for `xhr` targets.
     */
    private fun handleOp(
        json: JSONObject,
        sender: WebExtension.MessageSender
    ): GeckoResult<Any>? {
        val sourceOrigin = sender.url?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val pageUrl = sender.url?.takeIf { sender.isTopLevel }
        when (json.optString("op")) {
            "set" -> {
                val script = json.optString("script")
                val key = json.optString("key")
                val value = json.optString("value")
                if (bootstrap.permitted(script, sourceOrigin, pageUrl)) {
                    networkScheduler.scheduleDirect {
                        runCatching { store.gmSetValue(script, key, value) }
                    }
                }
            }
            "del" -> {
                val script = json.optString("script")
                val key = json.optString("key")
                if (bootstrap.permitted(script, sourceOrigin, pageUrl)) {
                    networkScheduler.scheduleDirect {
                        runCatching { store.gmDeleteValue(script, key) }
                    }
                }
            }
            "xhr" -> {
                val result = GeckoResult<Any>()
                networkScheduler.scheduleDirect {
                    performXhr(result, json, sourceOrigin, pageUrl)
                }
                return result
            }
            "xhr-abort" -> inflight.remove(json.optLong("id", -1))?.cancel()
            "clipboard" -> {
                val text = json.optString("text")
                if (bootstrap.permitted(json.optString("script"), sourceOrigin, pageUrl)) {
                    mainScheduler.scheduleDirect {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("userscript", text))
                    }
                }
            }
            "openTab" -> {
                val url = json.optString("url")
                if (bootstrap.permitted(json.optString("script"), sourceOrigin, pageUrl)
                    && (url.startsWith("http://") || url.startsWith("https://"))) {
                    mainScheduler.scheduleDirect { openInTab.get().invoke(url) }
                }
            }
        }
        return GeckoResult.fromValue(JSONObject())
    }

    private fun rejectXhr(result: GeckoResult<Any>, id: Long, reason: String) {
        result.complete(
            JSONObject().put("id", id).put("final", true).put("error", true)
                .put("status", 0).put("statusText", reason)
        )
    }

    private fun performXhr(
        result: GeckoResult<Any>,
        json: JSONObject,
        sourceOrigin: Uri?,
        pageUrl: String?
    ) {
        val id = json.optLong("id", -1)
        val script = json.optString("script")
        if (!bootstrap.permitted(script, sourceOrigin, pageUrl)) {
            rejectXhr(result, id, "unknown userscript or origin")
            return
        }
        val url = json.optString("url")
        val connects = bootstrap.connectsOf(script)
        if (!GmConnectPolicy.hostAllowed(connects, sourceOrigin?.host, GmConnectPolicy.hostOf(url))) {
            logger.log(TAG, "GM xhr blocked by @connect: $script -> $url")
            rejectXhr(result, id, "blocked by @connect")
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
            result.complete(
                JSONObject().put("id", id).put("final", true).put("error", true)
                    .put("status", 0)
            )
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
                result.complete(
                    JSONObject().put("id", id).put("final", true)
                        .put("status", resp.code)
                        .put("statusText", resp.message)
                        .put("responseText", body)
                        .put("response", body)
                        .put("responseHeaders", headerBlock)
                        .put("finalUrl", resp.request.url.toString())
                        .put("readyState", 4)
                )
            }
        } catch (e: Exception) {
            result.complete(
                JSONObject().put("id", id).put("final", true).put("error", true)
                    .put("status", 0).put("statusText", "")
            )
        } finally {
            inflight.remove(id)
        }
    }

    companion object {
        private const val TAG = "GeckoUserScriptHost"
        private const val MAX_XHR_BODY = 4 * 1024 * 1024

        /** Native app name the extension uses in `connectNative`/`sendNativeMessage`. */
        const val NATIVE_APP = "browser"
        const val EXTENSION_ID = "arktos-userscripts@arktosbrowser.mobile"
        const val EXTENSION_LOCATION =
            "resource://android/assets/extensions/arktos_userscripts/"
    }
}
