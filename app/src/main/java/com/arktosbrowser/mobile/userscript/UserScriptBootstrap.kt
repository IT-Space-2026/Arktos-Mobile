// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.userscript

import android.net.Uri
import com.arktosbrowser.mobile.database.javascript.JavaScriptEntry
import com.arktosbrowser.mobile.log.Logger
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.uniffi.ArktosStore
import com.arktosbrowser.mobile.view.GmConnectPolicy
import com.arktosbrowser.mobile.view.ProxyUtils
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * ENG05 — the engine-neutral half of the userscript runtime, extracted
 * from `view/UserScriptEngine` so the GeckoView backend can reuse it.
 *
 * Both backends inject the same document-start bootstrap: a
 * transport-specific preamble (`window.arktosGM.postMessage` on WebView,
 * `browser.runtime.sendMessage` inside a GeckoView
 * `contentScripts.register` script) plus the shared include/exclude
 * gating, `@run-at` handling, `@require` inlining, GM_* API surface and
 * synchronous value-store mirror.
 *
 * The host side also keeps [policies] — each installed script's
 * `@connect` list and include/exclude rules — which the engine's GM
 * bridge checks before honoring any op (SEC06).
 */
@Singleton
class UserScriptBootstrap @Inject constructor(
    private val store: ArktosStore,
    private val userPreferences: UserPreferences,
    private val logger: Logger
) {

    /** How the injected bootstrap reaches the app's native GM bridge. */
    enum class Transport(val preamble: String) {
        WEB_VIEW(TRANSPORT_WEB_VIEW),
        GECKO(TRANSPORT_GECKO)
    }

    class ScriptPolicy(
        val connects: List<String>,
        val includes: List<String>,
        val excludes: List<String>
    )

    @Volatile
    var policies: Map<String, ScriptPolicy> = emptyMap()
        private set

    // @require bodies cached per script — key on name, invalidated on
    // version change.
    private val requirementCache = ConcurrentHashMap<String, Pair<String?, String>>()
    private val http = OkHttpClient()

    /**
     * The complete document-start script for [entries] on [transport],
     * or null when no scripts are installed (the caller unregisters the
     * previous injection in that case).
     */
    fun build(entries: List<JavaScriptEntry>, transport: Transport): String? {
        policies = entries.associate { e ->
            e.name to ScriptPolicy(csv(e.connects), csv(e.include), csv(e.exclude))
        }
        if (entries.isEmpty()) return null
        val sb = StringBuilder("(function(){\n'use strict';\n")
            .append(transport.preamble)
            .append(RUNTIME)
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

    /**
     * SEC06 — true when [script] is installed and its include/exclude
     * rules match the posting page. [pageUrl] is the committed
     * top-frame URL for main-frame posts; [sourceOrigin] is the
     * posting frame's origin (the `/`-suffixed variant catches
     * host-only patterns like `^\w+://example.com/`).
     */
    fun permitted(script: String, sourceOrigin: Uri?, pageUrl: String?): Boolean {
        val policy = policies[script] ?: return false
        val origin = sourceOrigin?.toString().orEmpty()
        val candidates = listOfNotNull(pageUrl, origin.takeIf { it.isNotEmpty() }, "$origin/")
        return candidates.any {
            GmConnectPolicy.runsOn(policy.includes, policy.excludes, it)
        }
    }

    /** The declared `@connect` hosts for [script] — empty when unknown. */
    fun connectsOf(script: String): List<String> =
        policies[script]?.connects ?: emptyList()

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

    companion object {
        private const val TAG = "UserScriptBootstrap"

        /**
         * WebView transport: the `WebMessageListener` postMessage object
         * registered as `window.arktosGM` receives ops and its
         * `onmessage` delivers native replies.
         */
        private const val TRANSPORT_WEB_VIEW = """
if (window.arktosGM) {
    window.arktosGM.onmessage = function(ev) {
        var m; try { m = JSON.parse(ev.data); } catch (e) { return; }
        __arktosDispatch(m);
    };
}
function __arktosSend(msg) {
    if (window.arktosGM) window.arktosGM.postMessage(JSON.stringify(msg));
}
"""

        /**
         * GeckoView transport: the bootstrap runs inside a
         * `contentScripts.register` content script and calls
         * `runtime.sendNativeMessage` directly — GeckoView delivers it
         * to the session's `WebExtension.SessionController` message
         * delegate with `sender.url`/`isTopLevel` describing the real
         * page frame (the SEC06 origin check depends on that — a
         * background relay would stamp the extension URL instead).
         * The promise resolves to the delegate's reply, treated exactly
         * like a `window.arktosGM.onmessage` event.
         */
        private const val TRANSPORT_GECKO = """
function __arktosSend(msg) {
    try {
        browser.runtime.sendNativeMessage('browser', msg).then(function(reply) {
            if (reply != null) __arktosDispatch(reply);
        });
    } catch (e) {}
}
"""

        // Page-side runtime shared by every transport: include/exclude
        // gating (full-match semantics to mirror Java's String.matches),
        // @run-at deferral, the GM_* API surface gated by each script's
        // @grant list, and the synchronous value-store mirror. The
        // transport preamble defines __arktosSend before this runs.
        private const val RUNTIME = """
var __arktosSeq = 0, __arktosCbs = {};
function __arktosDispatch(m) {
    var cb = __arktosCbs[m.id]; if (!cb) return;
    if (m.final) delete __arktosCbs[m.id];
    try { cb(m); } catch (e) {}
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
    }
}
