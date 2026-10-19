package com.arktosbrowser.mobile.view

import android.os.Message
import android.webkit.WebView

/**
 * Validates the transport [Message] a [android.webkit.WebChromeClient] receives
 * in `onCreateWindow` for `target="_blank"` / `window.open` requests.
 *
 * The WebView expects the app to deliver the message to a freshly created
 * WebView via [WebView.WebViewTransport]. If the payload is missing or isn't a
 * transport (a recycled or malformed message), the request must not be claimed:
 * `onCreateWindow` returns `false` so the WebView loads the link in the current
 * window instead of crashing or leaving a dead popup (upstream #306 — picture
 * links wrapped in `target="_blank"` anchors).
 */
object NewWindowTransport {

    /**
     * Returns true when [message] carries a deliverable
     * [WebView.WebViewTransport] payload.
     */
    fun isDeliverable(message: Message?): Boolean =
        message?.obj is WebView.WebViewTransport

}
