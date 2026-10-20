package com.arktosbrowser.mobile.browser

import android.app.SearchManager
import android.content.Intent
import com.arktosbrowser.mobile.rust.ArktosCore
import com.arktosbrowser.mobile.utils.smartUrlFilter

/**
 * SEC09 — single ingress point for the intents `MainActivity`'s
 * exported intent-filters admit. Every URL an external intent asks the
 * browser to open flows through [extractUrl] — on both the cold-start
 * (`TabsManager.initializeTabs`) and warm (`BrowserPresenter
 * .onNewIntent`) paths — so the scheme allowlist can't be sidestepped
 * by whichever entry point happens to run first.
 *
 * `ACTION_WEB_SEARCH` (`SearchManager.QUERY`) and
 * `ACTION_PROCESS_TEXT` (`EXTRA_PROCESS_TEXT`) carry attacker-chosen
 * plain text, so they are run through [smartUrlFilter] like typed
 * input: real URLs pass through, everything else becomes a search.
 * All other actions contribute `dataString` only — taken raw, never
 * searched — and the result is scheme-allowlisted so `javascript:`,
 * `data:` and other exotic payloads can't reach a tab.
 */
object IncomingIntents {

    /**
     * Returns the URL [intent] asks the browser to open, or null when
     * the intent carries nothing — or nothing the browser may load.
     * [searchUrl] is the configured search template including the query
     * placeholder; [onRejected] is invoked with the scheme of a dropped
     * candidate so callers can log without this object touching Android
     * logging (keeps the policy unit-testable on the JVM).
     */
    fun extractUrl(
        intent: Intent?,
        searchUrl: String,
        onRejected: (String) -> Unit = {}
    ): String? {
        val url = when (intent?.action) {
            Intent.ACTION_WEB_SEARCH ->
                intent.getStringExtra(SearchManager.QUERY)?.asSearchOrUrl(searchUrl)
            Intent.ACTION_PROCESS_TEXT ->
                intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT)?.asSearchOrUrl(searchUrl)
            else -> intent?.dataString
        }?.takeIf { it.isNotBlank() } ?: return null
        if (isAllowed(url)) {
            return url
        }
        onRejected(url.substringBefore(':'))
        return null
    }

    /** Typed/selected text → resolved URL or a search for the text. */
    private fun String.asSearchOrUrl(searchUrl: String): String? =
        takeIf { it.isNotBlank() }?.let { smartUrlFilter(it, true, searchUrl) }

    /** Scheme allowlist — the actual check lives in the Rust core. */
    internal fun isAllowed(url: String): Boolean = ArktosCore.isIntentUrlAllowed(url)
}
