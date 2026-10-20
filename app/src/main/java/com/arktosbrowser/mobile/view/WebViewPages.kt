package com.arktosbrowser.mobile.view

import android.webkit.WebView
import com.arktosbrowser.mobile.engine.Page

/**
 * Adapter helpers for working with the WebView backend behind the
 * [Page] interface. `android.webkit` types stay inside `view/` —
 * callers outside the adapter never downcast.
 *
 * @return the backing [WebView] when the page is an [ArktosCookieView],
 * null for any other backend.
 */
internal fun Page.asWebView(): WebView? = (this as? ArktosCookieView)?.webView
