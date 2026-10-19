package com.arktosbrowser.mobile.html

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UI02: the in-app start/incognito pages were restyled after the Arktos
 * desktop startpage (aurora wave hero + cresting logo + gradient header,
 * no in-page search bar). The factories mutate the markup with jsoup and
 * SmartCookieWebClient syncs shortcut tiles via injected
 * `window.linkNvar` globals and the localStorage `shouldUpdate` flag, so
 * the test audits the assets for the ids/placeholders that contract needs.
 */
class StartPageTest {

    private fun loadAsset(name: String): String {
        val file = listOf(
            "src/main/assets/$name",
            "app/src/main/assets/$name"
        ).map(::File).first { it.isFile }
        return file.readText()
    }

    private fun loadSource(vararg candidates: String): String {
        val file = candidates.map(::File).first { it.isFile }
        return file.readText()
    }

    private fun assetExists(name: String): Boolean =
        listOf("src/main/assets/$name", "app/src/main/assets/$name")
            .map(::File).any { it.isFile }

    private fun firstBodyScript(html: String): String {
        val bodyStart = html.indexOf("<body")
        val scriptStart = html.indexOf("<script", bodyStart)
        val scriptEnd = html.indexOf("</script>", scriptStart)
        return html.substring(scriptStart, scriptEnd)
    }

    private val homepage by lazy { loadAsset("homepage.html") }
    private val privatePage by lazy { loadAsset("private.html") }

    @Test
    fun `aurora artwork is bundled as assets`() {
        listOf(
            "aurora-wave.svg",
            "aurora-wave-private.svg",
            "arktos-plain.svg",
            "private-sunglasses.svg"
        ).forEach { assertTrue("$it missing from assets", assetExists(it)) }
    }

    @Test
    fun `homepage renders the desktop aurora hero`() {
        assertTrue(homepage.contains("id=\"aurora\""))
        assertTrue(homepage.contains("file:///android_asset/aurora-wave.svg"))
        assertTrue(homepage.contains("file:///android_asset/arktos-plain.svg"))
        assertTrue(homepage.contains("id=\"header\""))
        assertTrue(homepage.contains("html.arktos-dark"))
    }

    @Test
    fun `private page renders the private aurora hero`() {
        assertTrue(privatePage.contains("id=\"aurora\""))
        assertTrue(privatePage.contains("file:///android_asset/aurora-wave-private.svg"))
        assertTrue(privatePage.contains("file:///android_asset/private-sunglasses.svg"))
        assertTrue(privatePage.contains("html.arktos-dark"))
        // Private palette is navy even without the dark class.
        assertTrue(privatePage.contains("#16294f"))
    }

    @Test
    fun `pages have no in-page search box`() {
        listOf(homepage, privatePage).forEach {
            assertFalse(it.contains("search_input"))
            assertFalse(it.contains("search_bar"))
            assertFalse(it.contains("search_submit"))
            assertFalse(it.contains("\${BASE_URL}"))
        }
    }

    @Test
    fun `shortcut tile contract is intact`() {
        listOf(homepage, privatePage).forEach { page ->
            (1..4).forEach { i ->
                assertTrue(page.contains("id=\"link$i\""))
                assertTrue(page.contains("id=\"link${i}click\""))
                assertTrue(page.contains("id=\"link${i}inp\""))
                assertTrue(page.contains("link${i}var"))
                assertTrue(page.contains("localStorage.setItem(\"link$i\""))
            }
            assertTrue(page.contains("id=\"shortcuts\""))
            assertTrue(page.contains("id=\"editModal\""))
            assertTrue(page.contains("id=\"edit_shortcuts\""))
            assertTrue(page.contains("id=\"apply\""))
            assertTrue(page.contains("shouldUpdate"))
            assertTrue(page.contains("class=\"edit\""))
            assertTrue(page.contains("window.link1var"))
        }
    }

    @Test
    fun `factory placeholders and ids are present`() {
        assertTrue(homepage.contains("\${TITLE}"))
        assertTrue(homepage.contains("id=\"image_url\""))
        assertTrue(privatePage.contains("id=\"title-pm\""))
        assertTrue(privatePage.contains("id=\"desc-pm\""))
    }

    @Test
    fun `first body script survives the factory ampersand escape`() {
        // The factories rewrite the first <script> in <body>, escaping '&'
        // to the JS escape 'u0026' for URL substitution — any literal '&'
        // in that script would corrupt it into a JS syntax error.
        listOf(homepage, privatePage).forEach { page ->
            assertFalse(firstBodyScript(page).contains("&"))
        }
        // The homepage keeps the ${ENDPOINT} substitution point there.
        assertTrue(firstBodyScript(homepage).contains("\${ENDPOINT}"))
    }

    @Test
    fun `pages carry no obfuscated or remote image dependencies`() {
        listOf(homepage, privatePage).forEach {
            assertFalse(it.contains("\\x"))
            assertFalse(it.contains("stack.imgur.com"))
            assertFalse(it.contains("via.placeholder.com"))
        }
    }

    @Test
    fun `incognito page plumbing reads private html`() {
        val reader = loadSource(
            "src/main/java/com/arktosbrowser/mobile/html/incognito/IncognitoPageReader.kt",
            "app/src/main/java/com/arktosbrowser/mobile/html/incognito/IncognitoPageReader.kt"
        )
        assertTrue(
            "IncognitoPageReader must open the bundled private.html asset",
            reader.contains("assets.open(\"private.html\")")
        )

        val client = loadSource(
            "src/main/java/com/arktosbrowser/mobile/view/SmartCookieWebClient.kt",
            "app/src/main/java/com/arktosbrowser/mobile/view/SmartCookieWebClient.kt"
        )
        assertTrue(
            "link-var injection must target /files/private.html",
            client.contains("/files/private.html")
        )
        assertFalse(client.contains("/files/incognito.html"))
    }

    @Test
    fun `dark start page uses the arktos-dark class`() {
        listOf(
            "src/main/java/com/arktosbrowser/mobile/html/homepage/HomePageFactory.kt",
            "app/src/main/java/com/arktosbrowser/mobile/html/homepage/HomePageFactory.kt",
            "src/main/java/com/arktosbrowser/mobile/html/incognito/IncognitoPageFactory.kt",
            "app/src/main/java/com/arktosbrowser/mobile/html/incognito/IncognitoPageFactory.kt"
        ).forEach {
            val source = File(it)
            if (source.isFile) {
                val text = source.readText()
                assertTrue("$it must add the arktos-dark class", text.contains("addClass(\"arktos-dark\")"))
            }
        }
    }
}
