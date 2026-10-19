package com.arktosbrowser.mobile.html

import com.arktosbrowser.mobile.browser.ChangelogGate
import com.arktosbrowser.mobile.rust.ArktosCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CHLOG01 — the bundled changelog page is reachable inside the app as
 * the `arktos://changelog` chrome URL: the scheme is allowlisted for
 * incoming VIEW intents and URL-bar input, the URL counts as an
 * internal ("special") page, sub-asset requests under
 * `arktos://changelog/<name>` resolve to flat bundled filenames only,
 * and the version-bump gate fires exactly once per build.
 */
class ChangelogPageTest {

    private fun loadAsset(name: String): String {
        val file = listOf(
            "src/main/assets/$name",
            "app/src/main/assets/$name"
        ).map(::File).first { it.isFile }
        return file.readText()
    }

    private val changelogPage by lazy { loadAsset("changelog.html") }

    @Test
    fun `changelog asset is bundled and styled like the Arktos pages`() {
        assertTrue(changelogPage.contains("id=\"aurora\""))
        assertTrue(changelogPage.contains("arktos://changelog/aurora-wave.svg"))
        assertTrue(changelogPage.contains("arktos://changelog/arktos-plain.svg"))
        assertTrue(changelogPage.contains("id=\"header\""))
        assertTrue(changelogPage.contains("html.arktos-dark"))
        // One card per release, latest version first.
        assertTrue(changelogPage.contains("<h2>17.0"))
    }

    @Test
    fun `changelog url is a special internal page`() {
        assertTrue(ArktosCore.isChangelogUrl(ChangelogPage.URL))
        assertTrue(ArktosCore.isChangelogUrl("file:///android_asset/changelog.html"))
        assertTrue(ArktosCore.isSpecialUrl(ChangelogPage.URL))
        assertTrue(ArktosCore.isSpecialUrl("file:///android_asset/changelog.html"))
        assertFalse(ArktosCore.isChangelogUrl("https://a.com/changelog.html"))
        assertFalse(ArktosCore.isChangelogUrl("arktos://settings"))
        assertFalse(ArktosCore.isSpecialUrl("arktos://changelog/aurora-wave.svg"))
    }

    @Test
    fun `arktos scheme is accepted for intents and url-bar input`() {
        assertTrue(ArktosCore.isIntentUrlAllowed(ChangelogPage.URL))
        assertTrue(ArktosCore.acceptedUriScheme(ChangelogPage.URL))
        // Typing the URL must load it, not search for it.
        assertEquals(
            ChangelogPage.URL,
            ArktosCore.smartUrlFilter(ChangelogPage.URL, true, "https://s/?q=%s")
        )
    }

    @Test
    fun `changelog asset names are flat filenames only`() {
        assertEquals(
            "aurora-wave.svg",
            ArktosCore.changelogAssetName("arktos://changelog/aurora-wave.svg")
        )
        assertEquals(
            "arktos-plain.svg",
            ArktosCore.changelogAssetName("arktos://changelog/arktos-plain.svg")
        )
        for (url in listOf(
            "arktos://changelog/../secret",
            "arktos://changelog/..",
            "arktos://changelog/a/b.svg",
            "arktos://changelog/%2e%2e/x",
            "https://a.com/changelog/x.svg",
            ChangelogPage.URL,
        )) {
            assertEquals("rejected: $url", "", ArktosCore.changelogAssetName(url))
        }
        // Every asset the page references must exist in the bundle.
        for (ref in Regex("arktos://changelog/([A-Za-z0-9._-]+)")
            .findAll(changelogPage).map { it.groupValues[1] }) {
            assertTrue(
                "$ref missing from assets",
                listOf("src/main/assets/$ref", "app/src/main/assets/$ref").any { File(it).isFile }
            )
        }
    }

    @Test
    fun `dark theme is baked in at serve time`() {
        val dark = ChangelogPage.applyDarkTheme("<html><body>x</body></html>", true)
        assertTrue(dark.startsWith("<html class=\"arktos-dark\""))
        val light = ChangelogPage.applyDarkTheme("<html><body>x</body></html>", false)
        assertFalse(light.contains("arktos-dark"))
    }

    @Test
    fun `upgrade gate fires once per build`() {
        // Fresh install (0) and upgrades both show once…
        assertTrue(ChangelogGate.shouldShow(0, 153))
        assertTrue(ChangelogGate.shouldShow(152, 153))
        // …but never for the build that already showed.
        assertFalse(ChangelogGate.shouldShow(153, 153))
        assertFalse(ChangelogGate.shouldShow(0, 0))
    }

    @Test
    fun `repo changelog mirrors the in-app entries`() {
        val repoChangelog = listOf("ChangeLog", "../ChangeLog")
            .map(::File).first { it.isFile }.readText()
        // Desktop format: "x.y — title" header line.
        assertTrue(repoChangelog.contains("17.0 — "))
    }

}
