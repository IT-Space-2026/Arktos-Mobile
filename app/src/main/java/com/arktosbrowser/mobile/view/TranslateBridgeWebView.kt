package com.arktosbrowser.mobile.view

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.arktosbrowser.mobile.translate.TranslateBridge

/**
 * Installs the `ArktosTranslate` postMessage object on [webView].
 * Must run before the first navigation: the object is injected
 * into matching frames when a page loads, so registering after a
 * page is already loaded would only take effect on the next load.
 *
 * The WebView-facing half of [TranslateBridge] — it lives in the
 * adapter package because `WebViewCompat`/`WebView` are backend
 * types; the wire-format and HTTP side stays engine-neutral in
 * `translate/`.
 */
fun TranslateBridge.register(webView: WebView) {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
        WebViewCompat.addWebMessageListener(webView, JS_OBJECT_NAME, setOf("*"), listener(this))
    } else {
        logMessageListenerUnsupported()
    }
}

private const val JS_OBJECT_NAME = "ArktosTranslate"

private fun listener(bridge: TranslateBridge) =
    WebViewCompat.WebMessageListener { _, message, _, _, replyProxy ->
        val data = message.data ?: return@WebMessageListener
        bridge.dispatchMessage(data) { payload ->
            runCatching { replyProxy.postMessage(payload) }
        }
    }
