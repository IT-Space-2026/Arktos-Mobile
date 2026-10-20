package com.arktosbrowser.mobile.browser

import android.app.Application
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.rust.ArktosCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/**
 * SEC12 — IDN/homograph protection: an internationalized host renders
 * in its punycode (`xn--`) form in the URL bar instead of Unicode, so
 * lookalike domains can't impersonate ASCII brands. Covers the
 * `ArktosCore.idnDisplay` seam (real libarktos via JNA) and the
 * [SearchBoxModel] display policy behind `showPunycodeEnabled`.
 */
class IdnDisplayTest {

    private fun searchBox(
        choice: SearchBoxDisplayChoice,
        punycode: Boolean = true,
        trim: Boolean = false
    ) = SearchBoxModel(
        mock {
            on { urlBoxContentChoice } doReturn choice
            on { showPunycodeEnabled } doReturn punycode
            on { trimUrlBarEnabled } doReturn trim
        },
        mock<Application> { on { getString(any()) } doReturn "Untitled" }
    )

    @Test
    fun `unicode host maps to punycode`() {
        val info = ArktosCore.idnDisplay("https://bücher.de/path?q=1")
        assertTrue(info.isIdn)
        assertEquals("https://xn--bcher-kva.de/path?q=1", info.asciiUrl)
    }

    @Test
    fun `ascii hosts pass through unflagged`() {
        for (url in listOf(
            "https://example.com/x",
            "https://user@example.com:8443/",
            "https://[::1]:8080/x",
            "about:blank",
            "data:text/html,x"
        )) {
            val info = ArktosCore.idnDisplay(url)
            assertFalse("$url must not flag", info.isIdn)
            assertEquals(url, info.asciiUrl)
        }
    }

    @Test
    fun `already punycode host still flags`() {
        val info = ArktosCore.idnDisplay("https://xn--bcher-kva.de/")
        assertTrue(info.isIdn)
        assertEquals("https://xn--bcher-kva.de/", info.asciiUrl)
    }

    @Test
    fun `url bar shows punycode when enabled`() {
        val model = searchBox(SearchBoxDisplayChoice.URL)
        assertEquals(
            "https://xn--bcher-kva.de/login",
            model.getDisplayContent("https://bücher.de/login", "Page", isLoading = false)
        )
    }

    @Test
    fun `url bar keeps unicode when toggle off`() {
        val model = searchBox(SearchBoxDisplayChoice.URL, punycode = false)
        assertEquals(
            "https://bücher.de/login",
            model.getDisplayContent("https://bücher.de/login", "Page", isLoading = false)
        )
    }

    @Test
    fun `domain choice shows punycode host`() {
        val model = searchBox(SearchBoxDisplayChoice.DOMAIN)
        // domainName keeps the https:// prefix on SSL URLs (existing quirk).
        assertEquals(
            "https://xn--bcher-kva.de",
            model.getDisplayContent("https://bücher.de/login", "Page", isLoading = false)
        )
        assertEquals(
            "xn--bcher-kva.de",
            model.getDisplayContent("http://bücher.de/login", "Page", isLoading = false)
        )
    }

    @Test
    fun `loading display also punycodes`() {
        val model = searchBox(SearchBoxDisplayChoice.TITLE)
        assertEquals(
            "https://xn--bcher-kva.de/login",
            model.getDisplayContent("https://bücher.de/login", "Page", isLoading = true)
        )
    }

    @Test
    fun `non idn urls are untouched`() {
        val model = searchBox(SearchBoxDisplayChoice.URL)
        assertEquals(
            "https://example.com/login",
            model.getDisplayContent("https://example.com/login", "Page", isLoading = false)
        )
    }

    @Test
    fun `punycode composes with url trim`() {
        val model = searchBox(SearchBoxDisplayChoice.URL, trim = true)
        assertEquals(
            "xn--bcher-kva.de/login",
            model.getDisplayContent("https://www.bücher.de/login", "Page", isLoading = false)
        )
    }
}
