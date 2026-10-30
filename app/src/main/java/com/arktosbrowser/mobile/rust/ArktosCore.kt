// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
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
import com.arktosbrowser.mobile.uniffi.AdBlockListPreset
import com.arktosbrowser.mobile.uniffi.ArktosStore
import com.arktosbrowser.mobile.uniffi.BlocklistConfig
import com.arktosbrowser.mobile.uniffi.BlocklistEngine
import com.arktosbrowser.mobile.uniffi.RankListSource
import com.arktosbrowser.mobile.uniffi.RankListSourceKind
import com.arktosbrowser.mobile.uniffi.SiteRankDetail
import com.arktosbrowser.mobile.uniffi.ReadableArticle
import com.arktosbrowser.mobile.uniffi.RequestVerdict
import com.arktosbrowser.mobile.uniffi.SiteBlockMode
import com.arktosbrowser.mobile.uniffi.StoreBookmark
import com.arktosbrowser.mobile.uniffi.TranslateRequest
import com.arktosbrowser.mobile.uniffi.SuggestionProvider
import com.arktosbrowser.mobile.uniffi.UserScriptMeta
import com.arktosbrowser.mobile.uniffi.PasswordCheck
import com.arktosbrowser.mobile.uniffi.acceptedUriScheme as uniffiAcceptedUriScheme
import com.arktosbrowser.mobile.uniffi.adblockPresets
import com.arktosbrowser.mobile.uniffi.buildBlockPage as uniffiBuildBlockPage
import com.arktosbrowser.mobile.uniffi.buildErrorPage as uniffiBuildErrorPage
import com.arktosbrowser.mobile.uniffi.buildHttpsWarningPage as uniffiBuildHttpsWarningPage
import com.arktosbrowser.mobile.uniffi.changelogAssetName as uniffiChangelogAssetName
import com.arktosbrowser.mobile.uniffi.composeSearchUrl as uniffiComposeSearchUrl
import com.arktosbrowser.mobile.uniffi.contentDispositionFilename as uniffiContentDispositionFilename
import com.arktosbrowser.mobile.uniffi.corePing
import com.arktosbrowser.mobile.uniffi.coreVersion as uniffiCoreVersion
import com.arktosbrowser.mobile.uniffi.domainName as uniffiDomainName
import com.arktosbrowser.mobile.uniffi.downloadFileName as uniffiDownloadFileName
import com.arktosbrowser.mobile.uniffi.exportLegacyBookmarks as uniffiExportLegacyBookmarks
import com.arktosbrowser.mobile.uniffi.extractReadableArticle as uniffiExtractReadableArticle
import com.arktosbrowser.mobile.uniffi.fileExtension as uniffiFileExtension
import com.arktosbrowser.mobile.uniffi.formatDataSize as uniffiFormatDataSize
import com.arktosbrowser.mobile.uniffi.guessFileName as uniffiGuessFileName
import com.arktosbrowser.mobile.uniffi.hashPassword as uniffiHashPassword
import com.arktosbrowser.mobile.uniffi.idnDisplay as uniffiIdnDisplay
import com.arktosbrowser.mobile.uniffi.IdnDisplay
import com.arktosbrowser.mobile.uniffi.importLegacyBookmarks as uniffiImportLegacyBookmarks
import com.arktosbrowser.mobile.uniffi.isBookmarkUrl as uniffiIsBookmarkUrl
import com.arktosbrowser.mobile.uniffi.isChangelogUrl as uniffiIsChangelogUrl
import com.arktosbrowser.mobile.uniffi.isColorTooDark as uniffiIsColorTooDark
import com.arktosbrowser.mobile.uniffi.isDownloadsUrl as uniffiIsDownloadsUrl
import com.arktosbrowser.mobile.uniffi.isHistoryUrl as uniffiIsHistoryUrl
import com.arktosbrowser.mobile.uniffi.isIntentUrlAllowed as uniffiIsIntentUrlAllowed
import com.arktosbrowser.mobile.uniffi.isSpecialUrl as uniffiIsSpecialUrl
import com.arktosbrowser.mobile.uniffi.isStartPageUrl as uniffiIsStartPageUrl
import com.arktosbrowser.mobile.uniffi.mimeTypeForExtension as uniffiMimeTypeForExtension
import com.arktosbrowser.mobile.uniffi.mixColorFraction as uniffiMixColorFraction
import com.arktosbrowser.mobile.uniffi.mixTwoColors as uniffiMixTwoColors
import com.arktosbrowser.mobile.uniffi.parseDefaultBookmarks as uniffiParseDefaultBookmarks
import com.arktosbrowser.mobile.uniffi.parseHostsDomains as uniffiParseHostsDomains
import com.arktosbrowser.mobile.uniffi.parseNetscapeBookmarks
import com.arktosbrowser.mobile.uniffi.parseSuggestions as uniffiParseSuggestions
import com.arktosbrowser.mobile.uniffi.parseUserscript
import com.arktosbrowser.mobile.uniffi.sanitizeFileName as uniffiSanitizeFileName
import com.arktosbrowser.mobile.uniffi.translateUrl as uniffiTranslateUrl
import com.arktosbrowser.mobile.uniffi.translateRequestBody as uniffiTranslateRequestBody
import com.arktosbrowser.mobile.uniffi.translateParseRequest as uniffiTranslateParseRequest
import com.arktosbrowser.mobile.uniffi.parseTranslateResponse as uniffiParseTranslateResponse
import com.arktosbrowser.mobile.uniffi.siteRank as uniffiSiteRank
import com.arktosbrowser.mobile.uniffi.siteRankDetail as uniffiSiteRankDetail
import com.arktosbrowser.mobile.uniffi.siteRankEntryCount as uniffiSiteRankEntryCount
import com.arktosbrowser.mobile.uniffi.siteRankFileEntries as uniffiSiteRankFileEntries
import com.arktosbrowser.mobile.uniffi.siteRankLastUpdate as uniffiSiteRankLastUpdate
import com.arktosbrowser.mobile.uniffi.siteRankReload as uniffiSiteRankReload
import com.arktosbrowser.mobile.uniffi.siteRankSetDataDir as uniffiSiteRankSetDataDir
import com.arktosbrowser.mobile.uniffi.siteRankSources as uniffiSiteRankSources
import com.arktosbrowser.mobile.uniffi.siteRankUpdateIfStale as uniffiSiteRankUpdateIfStale
import com.arktosbrowser.mobile.uniffi.siteRankUpdateNow as uniffiSiteRankUpdateNow
import com.arktosbrowser.mobile.uniffi.smartUrlFilter as uniffiSmartUrlFilter
import com.arktosbrowser.mobile.uniffi.stripUrl as uniffiStripUrl
import com.arktosbrowser.mobile.uniffi.suggestionQueryUrl as uniffiSuggestionQueryUrl
import com.arktosbrowser.mobile.uniffi.verifyPassword as uniffiVerifyPassword

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
     * Parse one subscription list's content (hosts-file or
     * AdBlockPlus syntax) and merge its domains into the ad-block set
     * under [id] (LIST01). Returns the extracted domain count.
     */
    fun loadSubscriptionHosts(id: String, content: String): Int =
        blocklistEngine.loadSubscriptionHosts(id, content).toInt()

    /**
     * Drop a subscription's domains from the ad-block set (LIST01) —
     * called when a list is disabled or removed.
     */
    fun removeSubscriptionHosts(id: String) = blocklistEngine.removeSubscriptionHosts(id)

    /**
     * The curated filter-list catalog (LIST01) — a verbatim port of
     * the desktop's `adblockpresets.cpp`.
     */
    fun adBlockPresets(): List<AdBlockListPreset> = adblockPresets()

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
     * (STORE01). [key] is the 32-byte SQLCipher page key — see
     * `database/DatabaseKeyProvider` (SEC15). One instance is shared
     * by all repositories — see `AppModule.providesArktosStore`.
     */
    fun openStore(path: String, key: ByteArray): ArktosStore = ArktosStore.open(path, key)

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

    // ---- RANK01 --------------------------------------------------

    /**
     * Point the site-rank engine at its drop directory — the Kotlin
     * `RankListLoader` commits `tranco-list.dat` /
     * `majestic-million.csv` there (RANK01).
     */
    fun setRankListDataDir(dir: String) = uniffiSiteRankSetDataDir(dir)

    /**
     * The host's merged Tranco+Majestic rank; 0 when unranked
     * (RANK01). Lazy — the seed ∪ drop merge builds on first call, so
     * this stays off the per-request hot path.
     */
    fun siteRank(host: String): Long = uniffiSiteRank(host).toLong()

    /** Per-source ranks for [host] — a 0 slot means that list doesn't
     *  rank it. Powers the padlock dialog's Tranco/Majestic rows. */
    fun siteRankDetail(host: String): SiteRankDetail = uniffiSiteRankDetail(host)

    /** The two rank-list sources with last-update stamps (RANK01). */
    fun rankListSources(): List<RankListSource> = uniffiSiteRankSources()

    /** ms-since-epoch stamp of one source's committed copy; 0 = never (RANK01). */
    fun rankListLastUpdate(kind: RankListSourceKind): Long = uniffiSiteRankLastUpdate(kind).toLong()

    /** Usable-entry count of one drop file under its grammar — the updater's validation probe (RANK01). */
    fun rankFileEntries(fileName: String): Long = uniffiSiteRankFileEntries(fileName).toLong()

    /** Rebuild the merged rank table after an update lands (RANK01). Throws on a corrupt drop file. */
    fun reloadRankLists() = uniffiSiteRankReload()

    /**
     * Consent-gated staleness check (RANK01): the sources to fetch,
     * or empty when consent is missing / every copy is fresh.
     */
    fun rankUpdateIfStale(consentGranted: Boolean, nowMs: Long): List<RankListSource> =
        uniffiSiteRankUpdateIfStale(consentGranted, nowMs.toULong())

    /** Explicit refresh list — consent-free (RANK01). */
    fun rankUpdateNow(): List<RankListSource> = uniffiSiteRankUpdateNow()

    /** Merged rank-table entry count (RANK01). */
    fun rankEntryCount(): Long = uniffiSiteRankEntryCount().toLong()

    // ---- PORT02 --------------------------------------------------

    /**
     * `UrlUtils.smartUrlFilter` (PORT02) — resolve user input to a URL
     * or a composed search URL; `""` when [canBeSearch] is false and
     * the input is no URL.
     */
    fun smartUrlFilter(url: String, canBeSearch: Boolean, searchUrl: String): String =
        uniffiSmartUrlFilter(url, canBeSearch, searchUrl)

    /** The `ACCEPTED_URI_SCHEMA` check shared by UrlUtils/IntentUtils (PORT02). */
    fun acceptedUriScheme(url: String): Boolean = uniffiAcceptedUriScheme(url)

    /** `BrowserPresenter.isIntentUrlAllowed` — incoming-intent scheme allowlist (PORT02). */
    fun isIntentUrlAllowed(url: String): Boolean = uniffiIsIntentUrlAllowed(url)

    /** `URLUtil.composeSearchUrl` — placeholder substitution with form encoding (PORT02). */
    fun composeSearchUrl(query: String, template: String, placeholder: String): String =
        uniffiComposeSearchUrl(query, template, placeholder)

    /** Generated in-app `file://` page checks (PORT02). */
    fun isSpecialUrl(url: String): Boolean = uniffiIsSpecialUrl(url)
    fun isBookmarkUrl(url: String): Boolean = uniffiIsBookmarkUrl(url)
    fun isDownloadsUrl(url: String): Boolean = uniffiIsDownloadsUrl(url)
    fun isHistoryUrl(url: String): Boolean = uniffiIsHistoryUrl(url)
    fun isStartPageUrl(url: String): Boolean = uniffiIsStartPageUrl(url)

    /**
     * CHLOG01 — true for `arktos://changelog` and for the bundled
     * `file://…/changelog.html` asset path. [changelogAssetName]
     * returns the flat asset filename under `arktos://changelog/<name>`
     * ("" when the URL is not a changelog asset request).
     */
    fun isChangelogUrl(url: String): Boolean = uniffiIsChangelogUrl(url)
    fun changelogAssetName(url: String): String = uniffiChangelogAssetName(url)

    /** `Utils.getDomainName` — display-only domain extraction (PORT02). */
    fun domainName(url: String): String = uniffiDomainName(url)

    /**
     * SEC12 — IDN/homograph display info: [IdnDisplay.isIdn] flags a
     * host carrying non-ASCII characters or `xn--` A-labels;
     * [IdnDisplay.asciiUrl] is the URL with its host in punycode form
     * (input unchanged when the host needs no rewrite). Display-only —
     * never feed the result back into navigation or storage.
     */
    fun idnDisplay(url: String): IdnDisplay = uniffiIdnDisplay(url)

    /**
     * Desktop-parity copy-link cleaning (urlstrip module): removes
     * tracking query params (utm_*, fbclid, …), resolves redirector
     * wrappers, and returns the input unchanged when nothing fired.
     */
    fun stripUrl(url: String): String = uniffiStripUrl(url)

    /** `Utils.guessFileExtension` (PORT02). */
    fun fileExtension(filename: String): String? = uniffiFileExtension(filename)

    /**
     * `Utils.buildErrorPage` (PORT02) — the error interstitial HTML;
     * all strings arrive localized, [color] is the theme override
     * fragment appended verbatim. [hint] is the small suggestion
     * line under the error (UI01 desktop-style page).
     */
    fun buildErrorPage(
        color: String,
        title: String,
        error: String,
        hint: String,
        reload: String,
        showButton: Boolean,
        reloadCode: String = "window.history.back();"
    ): String = uniffiBuildErrorPage(color, title, error, hint, reload, showButton, reloadCode)

    /**
     * SEC04 — the HTTPS-only warning interstitial HTML: [httpsUrl] is
     * the upgrade button's target and [proceedUrl] the consent-marker
     * URL behind "proceed anyway".
     */
    fun buildHttpsWarningPage(
        color: String,
        title: String,
        error: String,
        blockedUrl: String,
        hint: String,
        httpsLabel: String,
        httpsUrl: String,
        proceedLabel: String,
        proceedUrl: String,
        goBackLabel: String
    ): String = uniffiBuildHttpsWarningPage(
        color, title, error, blockedUrl, hint,
        httpsLabel, httpsUrl, proceedLabel, proceedUrl, goBackLabel
    )

    /** `Utils.buildBlockPage` (PORT02) — the block interstitial HTML. */
    fun buildBlockPage(
        color: String,
        title: String,
        error: String,
        blockedUrl: String,
        hint: String,
        proceedLabel: String,
        goBackLabel: String,
        showProceed: Boolean
    ): String =
        uniffiBuildBlockPage(color, title, error, blockedUrl, hint, proceedLabel, goBackLabel, showProceed)

    /** `Utils.isColorTooDark` — tab-strip luminance heuristic (PORT02). */
    fun isColorTooDark(color: Int): Boolean = uniffiIsColorTooDark(color)

    /** `Utils.mixTwoColors` — ARGB blend (PORT02). */
    fun mixTwoColors(color1: Int, color2: Int, amount: Float): Int =
        uniffiMixTwoColors(color1, color2, amount)

    /** `DrawableUtils.mixColor` — fraction-lerp incl. alpha (PORT02). */
    fun mixColorFraction(fraction: Float, startValue: Int, endValue: Int): Int =
        uniffiMixColorFraction(fraction, startValue, endValue)

    /**
     * Argon2id hash for the app-lock / parental-control passwords
     * (PORT02) — replaces the plaintext SharedPreferences storage.
     */
    fun hashPassword(password: String): String = uniffiHashPassword(password)

    /**
     * Verify [candidate] against the stored value (PORT02). A legacy
     * plaintext stored value that matches returns the Argon2id
     * replacement in [PasswordCheck.upgradedHash] — persist it to
     * complete the migration.
     */
    fun verifyPassword(stored: String, candidate: String): PasswordCheck =
        uniffiVerifyPassword(stored, candidate)

    /**
     * Hosts-file → domain list (PORT02) — the Rust parser behind the
     * engine, exposed for the `HostParserTest` parity check.
     */
    fun parseHostsDomains(content: String): List<String> = uniffiParseHostsDomains(content)

    /**
     * `BookmarkExporter` payload (PORT02) — the legacy JSON-lines
     * export text for the given bookmarks (upstream field order and
     * org.json escaping preserved).
     */
    fun exportLegacyBookmarks(bookmarks: List<StoreBookmark>): String =
        uniffiExportLegacyBookmarks(bookmarks)

    /** Endpoint base → LibreTranslate `…/translate` route (page translation). */
    fun translateUrl(endpoint: String): String = uniffiTranslateUrl(endpoint)

    /** `{"q":[…],"source","target","format":"text"}` request body. */
    fun translateRequestBody(texts: List<String>, source: String, target: String): String =
        uniffiTranslateRequestBody(texts, source, target)

    /**
     * Parses `{"translatedText": …}` — string or array; the legacy
     * `{"text": …}` shape is accepted for the ReadingActivity caller.
     */
    fun parseTranslateResponse(body: String): List<String> = uniffiParseTranslateResponse(body)

    /**
     * Validates a `{"texts":[…],"target":"xx"}` walker request; `null`
     * for malformed or empty payloads and non-BCP-47 targets.
     */
    fun translateParseRequest(body: String): TranslateRequest? = uniffiTranslateParseRequest(body)

    // MSYNC02 — synchronization engine facade.
    fun syncInviteBegin(dataRoot: String, scope: String): String =
        com.arktosbrowser.mobile.uniffi.syncInviteBegin(dataRoot, scope)

    fun syncJoinBegin(dataRoot: String, payloadText: String): String =
        com.arktosbrowser.mobile.uniffi.syncJoinBegin(dataRoot, payloadText)

    fun syncFingerprint(hashB64: String): String =
        com.arktosbrowser.mobile.uniffi.syncFingerprint(hashB64)

    fun syncMembers(dataRoot: String): String =
        com.arktosbrowser.mobile.uniffi.syncMembers(dataRoot)

    fun syncTransportStart(dataRoot: String, backend: String, name: String): Boolean =
        com.arktosbrowser.mobile.uniffi.syncTransportStart(dataRoot, backend, name)

    fun syncTransportStop(dataRoot: String): Boolean =
        com.arktosbrowser.mobile.uniffi.syncTransportStop(dataRoot)

    fun syncTransportRunning(dataRoot: String): Boolean =
        com.arktosbrowser.mobile.uniffi.syncTransportRunning(dataRoot)

    fun syncTransportDebug(dataRoot: String): String =
        com.arktosbrowser.mobile.uniffi.syncTransportDebug(dataRoot)

    fun syncAppend(dataRoot: String, storeName: String, scope: String, bodyJson: String): String =
        com.arktosbrowser.mobile.uniffi.syncAppend(dataRoot, storeName, scope, bodyJson)

    fun syncExport(dataRoot: String, storeName: String?, sinceSeq: Long): String =
        com.arktosbrowser.mobile.uniffi.syncExport(dataRoot, storeName, sinceSeq)

    fun syncImport(dataRoot: String, opsJson: String): String =
        com.arktosbrowser.mobile.uniffi.syncImport(dataRoot, opsJson)

    fun syncPendingApply(dataRoot: String, storeName: String): String =
        com.arktosbrowser.mobile.uniffi.syncPendingApply(dataRoot, storeName)

    fun syncMarkApplied(dataRoot: String, opId: String): String =
        com.arktosbrowser.mobile.uniffi.syncMarkApplied(dataRoot, opId)

    fun syncDrainPending(dataRoot: String, storeName: String): String =
        com.arktosbrowser.mobile.uniffi.syncDrainPending(dataRoot, storeName)

    fun syncStats(dataRoot: String): String =
        com.arktosbrowser.mobile.uniffi.syncStats(dataRoot)

    /** One-shot adapter bring-up — installs the shared store and
     * registers every adapter's apply lane (gecko only). */
    fun syncInstall(dataRoot: String, store: ArktosStore): String =
        com.arktosbrowser.mobile.uniffi.syncInstall(dataRoot, store)

    /** Local bookmark mutation through the real adapter emit path. */
    fun syncBookmarkLocal(dataRoot: String, op: String, url: String, title: String, folder: String): String =
        com.arktosbrowser.mobile.uniffi.syncBookmarkLocal(dataRoot, op, url, title, folder)

    /** Local history mutation through the real adapter emit path. */
    fun syncHistoryLocal(dataRoot: String, op: String, url: String, title: String): String =
        com.arktosbrowser.mobile.uniffi.syncHistoryLocal(dataRoot, op, url, title)

    /** Publish the open-tabs presence document. */
    fun syncTabsLocal(dataRoot: String, tabsJson: String): String =
        com.arktosbrowser.mobile.uniffi.syncTabsLocal(dataRoot, tabsJson)

    /** Paired-device list for the scope. */
    fun syncDevices(dataRoot: String, scope: String): String =
        com.arktosbrowser.mobile.uniffi.syncDevices(dataRoot, scope)

    /** Revoke a paired device — cuts membership and rotates the group key. */
    fun syncDeviceRevoke(dataRoot: String, scope: String, deviceId: String): String =
        com.arktosbrowser.mobile.uniffi.syncDeviceRevoke(dataRoot, scope, deviceId)

    /** Opt the scope into encrypted password sync. */
    fun syncPasswordsEnable(dataRoot: String, scope: String, passphrase: String): String =
        com.arktosbrowser.mobile.uniffi.syncPasswordsEnable(dataRoot, scope, passphrase)

    /** Lock the scope's password vault. */
    fun syncPasswordsLock(dataRoot: String, scope: String): String =
        com.arktosbrowser.mobile.uniffi.syncPasswordsLock(dataRoot, scope)

    /** Vault state for the scope. */
    fun syncPasswordsStatus(dataRoot: String, scope: String): String =
        com.arktosbrowser.mobile.uniffi.syncPasswordsStatus(dataRoot, scope)

    /** Local login save — upsert into the autofill vault + emit. */
    fun syncPasswordLocal(dataRoot: String, scope: String, url: String, username: String, password: String): String =
        com.arktosbrowser.mobile.uniffi.syncPasswordLocal(dataRoot, scope, url, username, password)

    /** Local login delete — drops the record + emits the tombstone. */
    fun syncPasswordRemove(dataRoot: String, scope: String, url: String): String =
        com.arktosbrowser.mobile.uniffi.syncPasswordRemove(dataRoot, scope, url)

    /** Local login wipe — tombstones the synced set in one pass. */
    fun syncPasswordClear(dataRoot: String, scope: String): String =
        com.arktosbrowser.mobile.uniffi.syncPasswordClear(dataRoot, scope)

    /** The decrypted group image — materialized records for merging. */
    fun syncPasswordsList(dataRoot: String, scope: String): String =
        com.arktosbrowser.mobile.uniffi.syncPasswordsList(dataRoot, scope)

    /** Local settings write — persists + emits through RcSettings::set. */
    fun syncSettingLocal(dataRoot: String, scope: String, key: String, valueJson: String): String =
        com.arktosbrowser.mobile.uniffi.syncSettingLocal(dataRoot, scope, key, valueJson)

    /** Local settings delete — emits the `del` op. */
    fun syncSettingRemove(dataRoot: String, scope: String, key: String): String =
        com.arktosbrowser.mobile.uniffi.syncSettingRemove(dataRoot, scope, key)

    /** The merged settings image for the app-store merge. */
    fun syncSettingsList(dataRoot: String, scope: String): String =
        com.arktosbrowser.mobile.uniffi.syncSettingsList(dataRoot, scope)

    /** Peer devices' open-tabs docs — the read-only remote tabs feed. */
    fun syncTabPeers(dataRoot: String, scope: String): String =
        com.arktosbrowser.mobile.uniffi.syncTabPeers(dataRoot, scope)

    /** Inbound dispatch: drain transport frames into the import lane. */
    fun syncTransportDrain(dataRoot: String): String =
        com.arktosbrowser.mobile.uniffi.syncTransportDrain(dataRoot)

    /** Outbound dispatch: export the op-log and fan it out to live links. */
    fun syncTransportPush(dataRoot: String): String =
        com.arktosbrowser.mobile.uniffi.syncTransportPush(dataRoot)

    fun syncMemberAdd(dataRoot: String, pubkeyB64: String, onion: String, scope: String): String =
        com.arktosbrowser.mobile.uniffi.syncMemberAdd(dataRoot, pubkeyB64, onion, scope)

    fun syncMemberRemove(dataRoot: String, deviceId: String): Boolean =
        com.arktosbrowser.mobile.uniffi.syncMemberRemove(dataRoot, deviceId)

    fun syncPairPoll(dataRoot: String, pairId: ULong): String =
        com.arktosbrowser.mobile.uniffi.syncPairPoll(dataRoot, pairId)

    fun syncPairConfirm(dataRoot: String, pairId: ULong, ok: Boolean): String =
        com.arktosbrowser.mobile.uniffi.syncPairConfirm(dataRoot, pairId, ok)

    fun syncPairCancel(dataRoot: String, pairId: ULong): String =
        com.arktosbrowser.mobile.uniffi.syncPairCancel(dataRoot, pairId)

    fun syncPairList(dataRoot: String): String =
        com.arktosbrowser.mobile.uniffi.syncPairList(dataRoot)
}

private fun SiteBlockChoice.toSiteBlockMode(): SiteBlockMode = when (this) {
    SiteBlockChoice.NONE -> SiteBlockMode.NONE
    SiteBlockChoice.WHITELIST -> SiteBlockMode.WHITELIST
    SiteBlockChoice.BLACKLIST -> SiteBlockMode.BLACKLIST
}
