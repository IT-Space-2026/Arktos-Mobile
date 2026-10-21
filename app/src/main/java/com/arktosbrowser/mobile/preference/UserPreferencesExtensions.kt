// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.preference

import com.arktosbrowser.mobile.constant.DESKTOP_USER_AGENT
import com.arktosbrowser.mobile.constant.MOBILE_USER_AGENT
import com.arktosbrowser.mobile.engine.Engines
import android.app.Application

/**
 * The token the system WebView appends to its default user agent to mark it as
 * a WebView rather than a standalone browser.
 */
const val WEBVIEW_UA_MARKER = "; wv"

/**
 * Remove the WebView marker from a user agent string. Applied once here rather
 * than mid-navigation: changing the user agent while a page loads makes the
 * WebView reload the previously committed entry, which ate the first
 * navigation after every app resume (upstream #309).
 */
fun stripWebViewMarker(userAgent: String): String =
    userAgent.replace(WEBVIEW_UA_MARKER, "")

/**
 * Return the user agent chosen by the user or the custom user agent entered by the user.
 */
fun UserPreferences.userAgent(application: Application): String =
    stripWebViewMarker(
        when (val choice = userAgentChoice) {
            1 -> Engines.backend.systemUserAgent(application)
            2 -> DESKTOP_USER_AGENT
            3 -> MOBILE_USER_AGENT
            4 -> userAgentString.takeIf(String::isNotEmpty) ?: " "
            else -> throw UnsupportedOperationException("Unknown userAgentChoice: $choice")
        }
    )
