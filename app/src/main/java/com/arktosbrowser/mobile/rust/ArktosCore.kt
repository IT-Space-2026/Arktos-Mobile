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
import com.arktosbrowser.mobile.uniffi.BlocklistConfig
import com.arktosbrowser.mobile.uniffi.BlocklistEngine
import com.arktosbrowser.mobile.uniffi.RequestVerdict
import com.arktosbrowser.mobile.uniffi.SiteBlockMode
import com.arktosbrowser.mobile.uniffi.corePing
import com.arktosbrowser.mobile.uniffi.coreVersion

object ArktosCore {

    /** Semantic version of the linked Rust core. */
    fun coreVersion(): String = coreVersion()

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
}

private fun SiteBlockChoice.toSiteBlockMode(): SiteBlockMode = when (this) {
    SiteBlockChoice.NONE -> SiteBlockMode.NONE
    SiteBlockChoice.WHITELIST -> SiteBlockMode.WHITELIST
    SiteBlockChoice.BLACKLIST -> SiteBlockMode.BLACKLIST
}
