// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.browser

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression guards for the tab-restore bug cluster (v1.0 maintenance → v1.1):
 *
 *  - a SessionState that can't unparcel (classloader/engine upgrade)
 *    must never cost the tab — the plain URL list is authoritative
 *  - finishInitialization's stop() aborting a background restore left
 *    permanently white tabs; an aborted load must mark the tab stale
 *    so selecting it re-issues the load
 *  - saved site-permission grants must be wiped by the web-storage
 *    clear paths
 *
 * There is no Robolectric in this project, so — like
 * DrawerNewTabButtonTest — the test audits the wiring directly.
 */
class TabRestoreRegressionTest {

    private fun source(path: String): String {
        val file = listOf(
            "src/main/java", "app/src/main/java",
            "src/gecko/java", "app/src/gecko/java"
        )
            .map { File("$it/$path") }
            .first { it.isFile }
        return file.readText()
    }

    private val tabsManager: String by lazy {
        source("com/arktosbrowser/mobile/browser/TabsManager.kt")
    }

    private val tabInitializer: String by lazy {
        source("com/arktosbrowser/mobile/view/TabInitializer.kt")
    }

    private val geckoPage: String by lazy {
        source("com/arktosbrowser/mobile/gecko/GeckoPage.kt")
    }

    private val geckoProfile: String by lazy {
        source("com/arktosbrowser/mobile/gecko/GeckoProfile.kt")
    }

    private val webUtils: String by lazy {
        source("com/arktosbrowser/mobile/utils/WebUtils.kt")
    }

    @Test
    fun `saveState always writes the URL file alongside the parcel`() {
        assertTrue(
            "saveState must write the plain URL list file",
            tabsManager.contains("URL_STORAGE") && tabsManager.contains("SAVED_TAB_URLS.txt")
        )
    }

    @Test
    fun `restore falls back to the positional URL line for stateless bundles`() {
        // readSavedStateFromDisk must position-match the URL file into
        // BundleInitializer so a bundle with no URL_KEY and an
        // unparcellable SessionState still restores.
        assertTrue(tabsManager.contains("savedUrlLines()"))
        assertTrue(tabsManager.contains("BundleInitializer(bundle, urls.getOrNull(pos))"))
    }

    @Test
    fun `bundle initializer tries engine state then URL fallback`() {
        val initialize = tabInitializer
            .substringAfter("class BundleInitializer")
            .substringBefore("class NoOpInitializer")
        val stateIdx = initialize.indexOf("page.restoreState(bundle)")
        val urlIdx = initialize.indexOf("URL_KEY") 
        assertTrue("restoreState must be attempted", stateIdx > 0)
        assertTrue("URL fallback must follow restoreState", urlIdx > stateIdx)
        assertTrue(initialize.contains("fallbackUrl"))
    }

    @Test
    fun `gecko restoreState extracts a URL from SessionState history`() {
        // A SessionState that reproduces empty must still yield the
        // tab's real URL from its current history entry.
        assertTrue(geckoPage.contains("sessionState.currentIndex"))
    }

    @Test
    fun `aborted loads mark the gecko tab stale for select-time recovery`() {
        val onPageStop = geckoPage
            .substringAfter("override fun onPageStop")
            .substringBefore("override fun onProgressChange")
        assertTrue(
            "onPageStop(success=false) must clear hasCommittedDoc",
            onPageStop.contains("hasCommittedDoc = false")
        )
    }

    @Test
    fun `stale gecko tabs re-issue their URL on select`() {
        val reloadIfStale = geckoPage
            .substringAfter("override fun reloadIfStale")
            .substringBefore("override fun onDestroy")
        assertTrue(
            "stale recovery must loadUri the tracked URL",
            reloadIfStale.contains("session.loadUri(currentUrl)")
        )
    }

    @Test
    fun `switchToTab always runs the stale check`() {
        val switch = tabsManager
            .substringAfter("fun switchToTab")
            .substringBefore("companion object")
        assertTrue(switch.contains("it.reloadIfStale()"))
        assertTrue(
            "reloadIfStale must not be gated on the allTabs preference",
            !switch.contains("userPreferences.allTabs")
        )
    }

    @Test
    fun `site permission grants are wiped with web storage`() {
        assertTrue(webUtils.contains("StorageArea.SitePermissions"))
        assertTrue(geckoProfile.contains("StorageArea.SitePermissions"))
        assertTrue(geckoProfile.contains("engine.permissionStore.clear()"))
    }
}
