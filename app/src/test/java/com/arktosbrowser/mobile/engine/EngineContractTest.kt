package com.arktosbrowser.mobile.engine

import com.arktosbrowser.mobile.browser.tabs.asTabViewState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/**
 * ENG03 — the `Page`/`Profile`/`Backend` seam is now the *only* type the
 * shell sees, on both engine flavors. These tests guard the two
 * invariants that make a second backend possible:
 *
 * 1. The seam never leaks engine-native types (`android.webkit.*` on
 *    the WebView side, `org.mozilla.geckoview.*` on the Gecko side).
 * 2. The shell-facing tab surface (foreground/new-tab flags, SSL state,
 *    desktop-UA toggle, timers, content pages) stays on `Page`.
 */
class EngineContractTest {

    @Test
    fun `seam types expose no engine-native classes`() {
        val enginePrefixes = listOf("android.webkit", "androidx.webkit", "org.mozilla.geckoview")
        val seam = listOf(Backend::class, Profile::class, Page::class)

        for (type in seam) {
            val leaks = type.java.methods.filter { method ->
                enginePrefixes.any {
                    method.returnType.name.startsWith(it) ||
                        method.parameterTypes.any { p -> p.name.startsWith(it) }
                }
            }
            assertTrue(
                "${type.simpleName} leaks engine-native types: $leaks",
                leaks.isEmpty()
            )
        }
    }

    @Test
    fun `page carries the shell-facing tab surface`() {
        val names = Page::class.java.methods.map { it.name }

        // Everything the tab shell calls today — removing one of these
        // silently breaks a backend.
        for (member in listOf(
            "isForegroundTab", "isNewTab",
            "getTitle", "getFavicon", "getProgress", "isShown",
            "getSslCertificate", "currentSslState", "sslStateObservable",
            "blockedThisPage",
            "initializePreferences",
            "onPause", "onResume", "onDestroy",
            "pauseTimers", "resumeTimers",
            "requestFocus", "setVisibility",
            "loadUrl",
            "loadHomePage", "loadBookmarkPage", "loadDownloadsPage", "loadHistoryPage",
            "toggleDesktopUA", "find", "createWebPagePrint"
        )) {
            assertTrue("Page is missing $member", member in names)
        }
    }

    @Test
    fun `page maps to tab view state`() {
        val page = mock<Page> {
            on { id } doReturn 42
            on { title } doReturn "Example"
            on { favicon } doReturn null
            on { isForegroundTab } doReturn true
        }

        val state = page.asTabViewState()

        assertEquals(42, state.id)
        assertEquals("Example", state.title)
        assertEquals(null, state.favicon)
        assertEquals(true, state.isForegroundTab)
    }
}
