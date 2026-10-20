package com.arktosbrowser.mobile.view

import android.webkit.WebSettings

/**
 * SEC03 — decides the WebView mixed-content mode. Upstream applied
 * [WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE] in normal browsing,
 * letting an HTTPS page pull HTTP subresources that any network
 * attacker can tamper with. Strict blocking
 * ([WebSettings.MIXED_CONTENT_NEVER_ALLOW]) is now the default for
 * every tab; incognito always blocks regardless of the toggle.
 *
 * The privacy switch ([com.arktosbrowser.mobile.preference.UserPreferences.blockMixedContentEnabled])
 * only relaxes normal browsing back to compatibility mode for legacy
 * sites that still serve mixed subresources.
 */
object MixedContentPolicy {

    /**
     * The [WebSettings.setMixedContentMode] value for a tab. Incognito
     * tabs always reject mixed content; normal tabs reject it unless
     * [blockMixedContent] is off.
     */
    fun mode(isIncognito: Boolean, blockMixedContent: Boolean): Int =
        if (isIncognito || blockMixedContent) {
            WebSettings.MIXED_CONTENT_NEVER_ALLOW
        } else {
            WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

}
