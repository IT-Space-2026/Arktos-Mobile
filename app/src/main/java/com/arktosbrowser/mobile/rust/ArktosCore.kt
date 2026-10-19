package com.arktosbrowser.mobile.rust

// RCORE01 — seam between the Kotlin shell and the Rust core
// (`libarktos.so`, UniFFI-generated bindings under
// `com.arktosbrowser.mobile.uniffi`).
//
// Every Kotlin→Rust call should go through this object so the JNI/JNA
// boundary stays in one place. The generated bindings load the native
// library lazily on first use, so calling into them is safe without an
// explicit System.loadLibrary here.

import com.arktosbrowser.mobile.browser.SiteBlockChoice
import com.arktosbrowser.mobile.uniffi.ArktosStore
import com.arktosbrowser.mobile.uniffi.BlocklistConfig
import com.arktosbrowser.mobile.uniffi.BlocklistEngine
import com.arktosbrowser.mobile.uniffi.ReadableArticle
import com.arktosbrowser.mobile.uniffi.RequestVerdict
import com.arktosbrowser.mobile.uniffi.SiteBlockMode
import com.arktosbrowser.mobile.uniffi.StoreBookmark
import com.arktosbrowser.mobile.uniffi.SuggestionProvider
import com.arktosbrowser.mobile.uniffi.UserScriptMeta
import com.arktosbrowser.mobile.uniffi.contentDispositionFilename as uniffiContentDispositionFilename
import com.arktosbrowser.mobile.uniffi.corePing
import com.arktosbrowser.mobile.uniffi.coreVersion as uniffiCoreVersion
import com.arktosbrowser.mobile.uniffi.downloadFileName as uniffiDownloadFileName
import com.arktosbrowser.mobile.uniffi.extractReadableArticle as uniffiExtractReadableArticle
import com.arktosbrowser.mobile.uniffi.formatDataSize as uniffiFormatDataSize
import com.arktosbrowser.mobile.uniffi.guessFileName as uniffiGuessFileName
import com.arktosbrowser.mobile.uniffi.importLegacyBookmarks as uniffiImportLegacyBookmarks
import com.arktosbrowser.mobile.uniffi.mimeTypeForExtension as uniffiMimeTypeForExtension
import com.arktosbrowser.mobile.uniffi.parseDefaultBookmarks as uniffiParseDefaultBookmarks
import com.arktosbrowser.mobile.uniffi.parseNetscapeBookmarks
import com.arktosbrowser.mobile.uniffi.parseSuggestions as uniffiParseSuggestions
import com.arktosbrowser.mobile.uniffi.parseUserscript
import com.arktosbrowser.mobile.uniffi.sanitizeFileName as uniffiSanitizeFileName
import com.arktosbrowser.mobile.uniffi.suggestionQueryUrl as uniffiSuggestionQueryUrl

object ArktosCore {

    /** Semantic version of the linked Rust core. */
    fun coreVersion(): String = uniffiCoreVersion()

    /**
     * Cheap liveness check — verifies the native library loaded and the
     * FFI trampoline works. Always true when reachable.
     */
    fun ping(): Boolean = corePing()

    /**
     * The shared [BlocklistEngine] (BLOCK01) — created lazily so
     * touching the seam doesn't load libarktos before first use.
     */
    private val blocklistEngine: BlocklistEngine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BlocklistEngine()
    }

    /** Push the blocklist feature toggles in one call. */
    fun configureBlocklist(adsEnabled: Boolean, malwareEnabled: Boolean, lanBlockEnabled: Boolean) =
        blocklistEngine.configure(BlocklistConfig(adsEnabled, malwareEnabled, lanBlockEnabled))

    /**
     * Parse and load hosts-file content into the ad-block set.
     * Returns the number of blocked domains.
     */
    fun loadAdHosts(content: String): Int = blocklistEngine.loadAdHosts(content).toInt()

    /**
     * Load comma-separated malware terms (`malware.txt` format);
     * an empty string clears the list. Returns the term count.
     */
    fun loadMalwareTerms(content: String): Int = blocklistEngine.loadMalwareTerms(content).toInt()

    /** Push the raw `siteBlockNames` preference string and mode. */
    fun setSiteBlockList(names: String, choice: SiteBlockChoice) =
        blocklistEngine.setSiteBlockList(names, choice.toSiteBlockMode())

    /**
     * Per-request verdict — called once per `shouldInterceptRequest`.
     * [pageUrl] is the URL of the loading page ("" when unknown).
     */
    fun requestVerdict(requestUrl: String, pageUrl: String, isMainFrame: Boolean): RequestVerdict =
        blocklistEngine.verdict(requestUrl, pageUrl, isMainFrame)

    /**
     * Open (creating if needed) the Rust storage core at [path]
     * (STORE01). One instance is shared by all repositories — see
     * `AppModule.providesArktosStore`.
     */
    fun openStore(path: String): ArktosStore = ArktosStore.open(path)

    /**
     * Parse a Netscape-format bookmark file in the Rust core
     * (STORE01). Returns flat rows — folder is the slash-joined path.
     */
    fun importNetscapeBookmarks(html: String): List<StoreBookmark> = parseNetscapeBookmarks(html)

    // ---- PORT01 --------------------------------------------------

    /**
     * Suggestion endpoint URL for an already-URLEncoded query
     * (PORT01). Kotlin builds the OkHttp request from it.
     */
    fun suggestionQueryUrl(provider: SuggestionProvider, encodedQuery: String, language: String): String =
        uniffiSuggestionQueryUrl(provider, encodedQuery, language)

    /**
     * Parse a suggestion response body (raw bytes — the Rust side
     * decodes each provider's wire charset) into bare suggestion
     * strings (PORT01).
     */
    fun parseSuggestions(provider: SuggestionProvider, body: ByteArray): List<String> =
        uniffiParseSuggestions(provider, body)

    /**
     * Parse a userscript source into metadata + body (PORT01).
     * [UserScriptMeta.includes] are Java-regex strings — compile-check
     * each before storing.
     */
    fun parseUserScript(source: String): UserScriptMeta = parseUserscript(source)

    /** RFC 6266/5987 Content-Disposition filename (PORT01). */
    fun contentDispositionFilename(header: String): String? = uniffiContentDispositionFilename(header)

    /** `URLUtil.guessFileName` parity (PORT01). */
    fun guessFileName(url: String, contentDisposition: String?, mimeType: String?): String =
        uniffiGuessFileName(url, contentDisposition, mimeType)

    /**
     * `DownloadHandler.getFileNameFromURL` parity (PORT01) — honors
     * `filename*`-only headers upstream ignored.
     */
    fun downloadFileName(url: String, contentDisposition: String?, mimeType: String?): String =
        uniffiDownloadFileName(url, contentDisposition, mimeType)

    /** Download filename sanitizer (PORT01). */
    fun sanitizeFileName(name: String?): String = uniffiSanitizeFileName(name)

    /** `DownloadUtil.getDataSize` parity (PORT01). */
    fun formatDataSize(size: Long): String = uniffiFormatDataSize(size)

    /** Extension → MIME type for the `FetchUrlMimeType` fixup (PORT01). */
    fun mimeTypeForExtension(extension: String): String? = uniffiMimeTypeForExtension(extension)

    /**
     * Strict legacy (JSON-lines) bookmark import (PORT01).
     * `null` on malformed input — callers surface the error.
     */
    fun importLegacyBookmarks(text: String): List<StoreBookmark>? = uniffiImportLegacyBookmarks(text)

    /** Lenient parse for bundled default bookmarks (PORT01). */
    fun parseDefaultBookmarks(text: String): List<StoreBookmark> = uniffiParseDefaultBookmarks(text)

    /**
     * Readability extraction via dom_smoothie (PORT01). `url` only
     * resolves relative links.
     */
    fun extractReadableArticle(url: String, html: String): ReadableArticle? =
        uniffiExtractReadableArticle(url, html)
}

private fun SiteBlockChoice.toSiteBlockMode(): SiteBlockMode = when (this) {
    SiteBlockChoice.NONE -> SiteBlockMode.NONE
    SiteBlockChoice.WHITELIST -> SiteBlockMode.WHITELIST
    SiteBlockChoice.BLACKLIST -> SiteBlockMode.BLACKLIST
}
