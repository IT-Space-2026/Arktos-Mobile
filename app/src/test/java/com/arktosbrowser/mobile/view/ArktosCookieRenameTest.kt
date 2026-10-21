// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.view

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * RENAM01 guard: the residual `SmartCookie*` type/file identifiers were
 * renamed to `ArktosCookie*` (SmartCookieView -> ArktosCookieView and
 * siblings). The legacy literals below are assembled from parts so this
 * test file itself stays clean under `grep -rn "SmartCookie"`.
 */
class ArktosCookieRenameTest {

    private val legacyPrefix = "Smart" + "Cookie"

    private val legacyTypes = listOf("View", "WebClient", "ChromeClient", "ViewTitle")
        .map { legacyPrefix + it }

    private val legacyFields = listOf("View", "WebClient", "ChromeClient")
        .map { "smart" + "Cookie" + it }

    private val newTypes = listOf(
        "ArktosCookieView", "ArktosCookieWebClient",
        "ArktosCookieChromeClient", "ArktosCookieViewTitle"
    )

    private fun moduleFile(path: String): File =
        listOf(path, "app/$path").map(::File).first { it.isFile }

    private fun kotlinSources(dir: String): List<File> {
        val root = listOf(dir, "app/$dir").map(::File).firstOrNull { it.isDirectory }
            ?: return emptyList()
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun `renamed view classes exist under ArktosCookie names`() {
        newTypes.forEach { name ->
            assertTrue(
                "$name.kt missing from view/",
                moduleFile("src/main/java/com/arktosbrowser/mobile/view/$name.kt").isFile
            )
        }
        legacyTypes.forEach { name ->
            val legacy = listOf(
                "src/main/java/com/arktosbrowser/mobile/view/$name.kt",
                "app/src/main/java/com/arktosbrowser/mobile/view/$name.kt"
            ).map(::File)
            assertFalse("legacy $name.kt still present", legacy.any { it.isFile })
        }
    }

    @Test
    fun `no SmartCookie identifiers remain in Kotlin sources`() {
        val sources = kotlinSources("src/main/java") + kotlinSources("src/test/java")
        assertTrue("no Kotlin sources found to scan", sources.isNotEmpty())

        val forbidden = legacyTypes + legacyFields
        sources.forEach { file ->
            if (file.name == "ArktosCookieRenameTest.kt") return@forEach
            val text = file.readText()

            val hit = forbidden.firstOrNull { text.contains(it) }
            assertNull("$file still uses legacy identifier $hit", hit)

            // "SmartCookieWeb" alone is the upstream project name and is
            // allowed in prose citations; strip it before the generic sweep.
            val prose = text.replace(legacyPrefix + "Web", "")
            assertFalse(
                "$file still mentions $legacyPrefix*",
                prose.contains(legacyPrefix)
            )
        }
    }

    @Test
    fun `proguard keep rule follows the renamed chrome client`() {
        val proguard = moduleFile("proguard-project.txt").readText()
        assertTrue(proguard.contains("view.ArktosCookieChromeClient"))
        assertFalse(proguard.contains("view.$legacyPrefix"))
    }

    @Test
    fun `external companion-app id and legacy-domain checks are kept`() {
        // The smartcookieweb_ytdl package and smartcookieweb.com endpoint
        // probes name *external* things (a companion app id, a dead hosted
        // domain users may still have configured) — they must not be renamed.
        val manifest = moduleFile("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("smartcookieweb_ytdl"))
        val browserActivity = moduleFile(
            "src/main/java/com/arktosbrowser/mobile/browser/activity/BrowserActivity.kt"
        ).readText()
        assertTrue(browserActivity.contains("smartcookieweb.com"))
    }
}
