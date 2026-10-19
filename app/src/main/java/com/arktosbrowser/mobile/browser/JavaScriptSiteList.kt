package com.arktosbrowser.mobile.browser

import com.arktosbrowser.mobile.utils.stringContainsItemFromList

/**
 * The legacy per-site JavaScript list (Settings → JavaScript blocking;
 * `javaScriptChoice` + `javaScriptBlocked` preferences) expressed as a
 * single pure decision (upstream #305).
 *
 * The upstream enum names are inverted relative to the settings
 * strings: [JavaScriptChoice.BLACKLIST] is "only allow JavaScript for
 * listed sites" and [JavaScriptChoice.WHITELIST] is "block JavaScript
 * for listed sites". [allowsJavaScript] hides that confusion.
 */
object JavaScriptSiteList {

    /**
     * Whether a page at [url] may run JavaScript under the configured
     * list. An empty/blank list allows everywhere; generated asset and
     * blank pages are always allowed, matching the WebView client's
     * early return that leaves their WebSettings untouched.
     */
    fun allowsJavaScript(choice: JavaScriptChoice, listCsv: String, url: String): Boolean {
        if (choice == JavaScriptChoice.NONE) return true
        val hosts = parseHosts(listCsv)
        if (hosts.isEmpty()) return true
        if (url.contains("file:///android_asset") || url.contains("about:blank")) return true
        val listed = stringContainsItemFromList(url, hosts)
        return when (choice) {
            JavaScriptChoice.NONE -> true
            JavaScriptChoice.BLACKLIST -> listed
            JavaScriptChoice.WHITELIST -> !listed
        }
    }

    private fun parseHosts(listCsv: String): Array<String> =
        listCsv.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toTypedArray()
}
