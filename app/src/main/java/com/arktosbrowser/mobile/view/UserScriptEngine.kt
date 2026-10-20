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
import com.arktosbrowser.mobile.database.javascript.JavaScriptEntry
import com.arktosbrowser.mobile.database.javascript.JavaScriptRepository
import com.arktosbrowser.mobile.di.MainScheduler
import com.arktosbrowser.mobile.di.NetworkScheduler
import com.arktosbrowser.mobile.log.Logger
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.uniffi.ArktosStore
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
import org.json.JSONArray
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
) {

    private var registeredScript: ScriptHandler? = null
    private var appliedVersion = -1L
    private val disposables = CompositeDisposable()

    /**
     * SEC06 — host-side copy of each installed script's declared
     * `@connect` domains plus its include/exclude rules, rebuilt with
     * the bootstrap. The GM bridge consults this before honoring any
     * op: the script must be installed, its rules must match the
     * posting page, and an `xhr` target must pass
     * [GmConnectPolicy.hostAllowed]. Trusting the script name alone
     * would let any page inherit an installed script's power — page
     * JavaScript can reach `window.arktosGM` directly.
     */
    private class ScriptPolicy(
        val connects: List<String>,
        val includes: List<String>,
        val excludes: List<String>
    )

    @Volatile
    private var scriptPolicies: Map<String, ScriptPolicy> = emptyMap()

    // @require bodies cached per script — key on name, invalidated on
    // version change.
    private val requirementCache = ConcurrentHashMap<String, Pair<String?, String>>()

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
                .map { entries -> buildBootstrap(entries) ?: "" }
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

    private fun buildBootstrap(entries: List<JavaScriptEntry>): String? {
        scriptPolicies = entries.associate { e ->
            e.name to ScriptPolicy(csv(e.connects), csv(e.include), csv(e.exclude))
        }
        if (entries.isEmpty()) return null
        val sb = StringBuilder("(function(){\n'use strict';\n").append(RUNTIME)
        for (e in entries) {
            val meta = JSONObject()
                .put("name", e.name)
                .put("version", e.version ?: "")
                .put("includes", JSONArray(csv(e.include)))
                .put("excludes", JSONArray(csv(e.exclude)))
                .put("runAt", e.runAt ?: "")
                .put("noframes", e.noFrames)
                .put("grants", JSONArray(csv(e.permissions)))
                // Stored as JSON text per key — a string the runtime
                // parses into the synchronous value snapshot.
                .put("values", valuesJson(e.name))
            val body = requirementsFor(e) + "\n" + unescape(e.code)
            sb.append("__arktosExec(")
                .append(meta.toString()).append(',')
                .append(JSONObject.quote(body))
                .append(");\n")
        }
        return sb.append("})();").toString()
    }

    private fun csv(raw: String?): List<String> =
        raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    private fun valuesJson(script: String): String = try {
        val vals = store.gmValuesForScript(script)
        vals.entries.joinToString(",", "{", "}") {
            JSONObject.quote(it.key) + ":" + it.value
        }
    } catch (e: Exception) {
        "{}"
    }

    private fun requirementsFor(e: JavaScriptEntry): String {
        val reqs = csv(e.requirements)
        if (reqs.isEmpty()) return ""
        requirementCache[e.name]?.let { if (it.first == e.version) return it.second }
        val proxy = ProxyUtils.connectionProxy(userPreferences)
        val sb = StringBuilder()
        for (url in reqs) {
            try {
                val client = http.newBuilder()
                    .apply { if (proxy != null) proxy(proxy) }
                    .build()
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    resp.body?.string()?.let { sb.append(it).append('\n') }
                }
            } catch (ex: Exception) {
                logger.log(TAG, "@require fetch failed for ${e.name}: $url")
            }
        }
        return sb.toString().also { requirementCache[e.name] = e.version to it }
    }

    /**
     * The exact unescape chain the old injection path applied to
     * `JavaScriptEntry.code` — the stored body still carries the
     * `document.body.innerText` escapes.
     */
    private fun unescape(code: String): String = code
        .replace("""\"""", """"""")
        .replace("\\n", System.lineSeparator())
        .replace("\\t", "")
        .replace("\\u003C", "<")
        .replace("""/"""", """"""")
        .replace("""//"""", """/"""")
        .replace("""\\'""", """\'""")
        .replace("""\\""""", """\""""")

    private fun reply(replyProxy: JavaScriptReplyProxy, payload: JSONObject) {
        mainScheduler.scheduleDirect {
            runCatching { replyProxy.postMessage(payload.toString()) }
        }
    }

    /**
     * SEC06 — true when [script] is installed and its include/exclude
     * rules match the posting page. [pageUrl] is the committed
     * top-frame URL for main-frame posts; [sourceOrigin] is the
     * posting frame's origin (the `/`-suffixed variant catches
     * host-only patterns like `^\w+://example.com/`).
     */
    private fun scriptPermitted(script: String, sourceOrigin: Uri?, pageUrl: String?): Boolean {
        val policy = scriptPolicies[script] ?: return false
        val origin = sourceOrigin?.toString().orEmpty()
        val candidates = listOfNotNull(pageUrl, origin.takeIf { it.isNotEmpty() }, "$origin/")
        return candidates.any {
            GmConnectPolicy.runsOn(policy.includes, policy.excludes, it)
        }
    }

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
        val connects = scriptPolicies.getValue(script).connects
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
         * Whether this WebView can do document-start injection. When
         * false the web client keeps the legacy evaluateJavascript path.
         */
        fun documentStartSupported(): Boolean =
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    }
}

// Page-side runtime: include/exclude gating (full-match semantics to
// mirror Java's String.matches), @run-at deferral, the GM_* API
// surface gated by each script's @grant list, and the synchronous
// value-store mirror.
private const val RUNTIME = """
var __arktosSeq = 0, __arktosCbs = {};
if (window.arktosGM) {
    window.arktosGM.onmessage = function(ev) {
        var m; try { m = JSON.parse(ev.data); } catch (e) { return; }
        var cb = __arktosCbs[m.id]; if (!cb) return;
        if (m.final) delete __arktosCbs[m.id];
        try { cb(m); } catch (e) {}
    };
}
function __arktosSend(msg) {
    if (window.arktosGM) window.arktosGM.postMessage(JSON.stringify(msg));
}
function __arktosMatches(patterns, url) {
    for (var i = 0; i < patterns.length; i++) {
        try {
            if (new RegExp('^(?:' + patterns[i] + ')$').test(url)) return true;
        } catch (e) {}
    }
    return false;
}
function __arktosApiArgs(meta) {
    var g = meta.grants || [];
    var want = function() {
        for (var i = 0; i < arguments.length; i++)
            if (g.indexOf(arguments[i]) >= 0) return true;
        return false;
    };
    var vals = {}; try { vals = JSON.parse(meta.values || '{}'); } catch (e) {}
    var script = meta.name;
    var setV = function(k, v) {
        vals[k] = JSON.stringify(v);
        __arktosSend({op:'set', script:script, key:k, value:vals[k]});
    };
    var getV = function(k, d) { return (k in vals) ? JSON.parse(vals[k]) : d; };
    var delV = function(k) { delete vals[k]; __arktosSend({op:'del', script:script, key:k}); };
    var listV = function() { return Object.keys(vals); };
    var xhr = function(details) {
        var id = ++__arktosSeq;
        __arktosCbs[id] = function(m) {
            var r = {readyState: m.readyState || 4, responseHeaders: m.responseHeaders || '',
                     status: m.status || 0, statusText: m.statusText || '',
                     responseText: m.responseText || '', response: m.responseText || '',
                     finalUrl: m.finalUrl || details.url, context: details.context};
            try { if (details.onreadystatechange) details.onreadystatechange(r); } catch (e) {}
            if (m.error) { try { if (details.onerror) details.onerror(r); } catch (e) {} return; }
            if (m.final) { try { if (details.onload) details.onload(r); } catch (e) {} }
        };
        __arktosSend({op:'xhr', id:id, script:script, method: details.method || 'GET', url: details.url,
                      headers: details.headers || {}, data: details.data || null});
        return {abort: function() {
            __arktosSend({op:'xhr-abort', id:id});
            delete __arktosCbs[id];
        }};
    };
    var GM = {
        xmlHttpRequest: function(d) {
            return new Promise(function(resolve) {
                d.onload = resolve; d.onerror = resolve; xhr(d);
            });
        },
        setValue: function(k, v) { setV(k, v); return Promise.resolve(); },
        getValue: function(k, d) { return Promise.resolve(getV(k, d)); },
        deleteValue: function(k) { delV(k); return Promise.resolve(); },
        listValues: function() { return Promise.resolve(listV()); }
    };
    var info = {script: {name: meta.name, version: meta.version},
                scriptHandler: 'Arktos Mobile', version: '1.0'};
    return [
        (want('GM') || want('GM.xmlHttpRequest')) ? GM : undefined,
        info,
        want('unsafeWindow') ? window : undefined,
        (want('GM_xmlhttpRequest') || want('GM.xmlHttpRequest')) ? xhr : undefined,
        (want('GM_setValue') || want('GM.setValue')) ? setV : undefined,
        (want('GM_getValue') || want('GM.getValue')) ? getV : undefined,
        (want('GM_deleteValue') || want('GM.deleteValue')) ? delV : undefined,
        (want('GM_listValues') || want('GM.listValues')) ? listV : undefined,
        want('GM_setClipboard') ? function(t) { __arktosSend({op:'clipboard', script:script, text:String(t)}); } : undefined,
        want('GM_openInTab') ? function(u) { __arktosSend({op:'openTab', script:script, url:String(u)}); } : undefined,
        want('GM_notification') ? function() {} : undefined
    ];
}
function __arktosExec(meta, code) {
    var href = location.href;
    if (!/^https?:/i.test(href)) return;
    if (meta.noframes && window.top !== window.self) return;
    var inc = (meta.includes && meta.includes.length) ? meta.includes : ['.*'];
    if (!__arktosMatches(inc, href)) return;
    if (meta.excludes && meta.excludes.length && __arktosMatches(meta.excludes, href)) return;
    var run = function() {
        try {
            var fn = new Function('GM', 'GM_info', 'unsafeWindow', 'GM_xmlhttpRequest',
                'GM_setValue', 'GM_getValue', 'GM_deleteValue', 'GM_listValues',
                'GM_setClipboard', 'GM_openInTab', 'GM_notification', code);
            fn.apply(window, __arktosApiArgs(meta));
        } catch (e) { console.error('[userscript:' + meta.name + ']', e); }
    };
    if (meta.runAt === 'document-start' || meta.runAt === 'context-menu') { run(); return; }
    if (document.readyState === 'loading') {
        if (meta.runAt === 'document-idle')
            document.addEventListener('DOMContentLoaded', function() { setTimeout(run, 0); });
        else
            document.addEventListener('DOMContentLoaded', run);
    } else if (meta.runAt === 'document-idle') {
        setTimeout(run, 0);
    } else {
        run();
    }
}
"""
