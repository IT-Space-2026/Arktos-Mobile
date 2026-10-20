package com.arktosbrowser.mobile.browser

import android.content.Context
import com.arktosbrowser.mobile.DeviceCapabilities
import com.arktosbrowser.mobile.engine.Engines
import com.arktosbrowser.mobile.engine.StorageArea
import com.arktosbrowser.mobile.isSupported
import com.arktosbrowser.mobile.utils.WebUtils
import java.io.File

/**
 * INCOG01 — the on-disk storage partition of the `:incognito` process.
 *
 * The WebView backend pins that process's WebView data behind
 * `WebView.setDataDirectorySuffix` (view/WebViewEngine), which makes
 * the physical directory `dataDir/app_webview_<suffix>` — an
 * underscore-joined sibling of `app_webview`, NOT a subdirectory.
 * `app_webview` itself must never be deleted from the :incognito
 * process: `context.dataDir` is app-wide, so that path is the MAIN
 * profile's partition and wiping it would destroy normal browsing
 * data.
 *
 * Two wipes keep the guarantee airtight:
 *
 *  1. Session end — [wipeSession], called from the BrowserActivity
 *     exit path and onDestroy: API-level clears through the engine's
 *     default profile (inside the :incognito process that profile IS
 *     the OTR partition) plus directory deletion, covering a session
 *     that ends while the process stays alive.
 *  2. Fresh start — [wipeDirectories] inside the engine's
 *     `initialize`, before the first page exists, so a swipe-away
 *     process death (no onDestroy ran) cannot carry state forward.
 *
 * GeckoView needs no partition bookkeeping: its incognito sessions
 * run `usePrivateMode`, which keeps nothing on disk, so process death
 * discards the whole session by construction.
 */
object IncognitoPartition {

    /** `android:process` suffix on IncognitoActivity in the manifest. */
    const val PROCESS_NAME_SUFFIX = ":incognito"

    /**
     * Suffix handed to `WebView.setDataDirectorySuffix` (WebView
     * backend). Below API 28 the call doesn't exist and incognito
     * shares the default partition — see
     * [DeviceCapabilities.FULL_INCOGNITO].
     */
    const val WEB_VIEW_DATA_DIR_SUFFIX = "incognito"

    /** WebView's un-suffixed data directory name (main partition). */
    const val WEB_VIEW_DEFAULT_DATA_DIR = "app_webview"

    fun isIncognitoProcess(packageName: String, processName: String): Boolean =
        processName == packageName + PROCESS_NAME_SUFFIX

    /**
     * The directory name the WebView suffix produces:
     * `app_webview_incognito`.
     */
    fun webViewDataDirName(): String =
        "${WEB_VIEW_DEFAULT_DATA_DIR}_$WEB_VIEW_DATA_DIR_SUFFIX"

    /**
     * Every filesystem location that could hold incognito WebView
     * state: the canonical `app_webview_<suffix>`, the same prefix
     * with the full process name as suffix (some OEM builds force the
     * suffix to the process name instead of the requested string), and
     * the nested `app_webview/incognito` variant. `app_webview` itself
     * is deliberately absent — see the class doc.
     */
    fun partitionDirs(dataDir: File, packageName: String): List<File> = listOf(
        File(dataDir, webViewDataDirName()),
        File(dataDir, "$WEB_VIEW_DEFAULT_DATA_DIR" + "_" + packageName + PROCESS_NAME_SUFFIX),
        File(File(dataDir, WEB_VIEW_DEFAULT_DATA_DIR), WEB_VIEW_DATA_DIR_SUFFIX)
    )

    /**
     * Delete the incognito partition directories; returns false when a
     * deletion failed. Safe before the first page exists and at
     * session end — never touches the default partition.
     */
    fun wipeDirectories(dataDir: File, packageName: String): Boolean =
        partitionDirs(dataDir, packageName).all { !it.exists() || WebUtils.deleteDir(it) }

    /**
     * Session-end wipe: clear every storage area through the engine's
     * default profile — inside the :incognito process that profile IS
     * the OTR partition — then delete the partition directories.
     *
     * Below [DeviceCapabilities.FULL_INCOGNITO] there is no separate
     * partition (incognito shares `app_webview` with normal browsing),
     * so the scope stays at the old storage-API-only clear — a full
     * wipe would destroy the user's normal cookies and storage.
     */
    fun wipeSession(context: Context) {
        if (DeviceCapabilities.FULL_INCOGNITO.isSupported) {
            Engines.defaultProfile.clear(setOf(StorageArea.All))
        } else {
            WebUtils.clearWebStorage()
        }
        wipeDirectories(context.dataDir, context.packageName)
    }
}
