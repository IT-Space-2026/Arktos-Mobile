package com.arktosbrowser.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * SEC14 — mirror of .devin/check-manifest-security.py so the export /
 * permission invariants also trip the JVM suite for contributors who
 * never run the standalone lint. Keep both allowlists in sync.
 */
class ManifestSecurityTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    private val exportedAllowlist = setOf(
        "com.arktosbrowser.mobile.MainActivity",
        "com.arktosbrowser.mobile.download.DownloadCompleteReceiver"
    )

    private val permissionAllowlist = setOf(
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.RECORD_AUDIO",
        "android.permission.CAMERA",
        "android.permission.MODIFY_AUDIO_SETTINGS",
        "com.android.launcher.permission.INSTALL_SHORTCUT"
    )

    private val componentTags = listOf(
        "activity", "activity-alias", "service", "receiver", "provider"
    )

    private val manifest: Element by lazy {
        val file = File("src/main/AndroidManifest.xml")
        assertTrue("manifest missing: ${file.absolutePath}", file.isFile)
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)
            .documentElement
    }

    private fun components(): List<Pair<String, Element>> =
        componentTags.flatMap { tag ->
            manifest.getElementsByTagName(tag).let { nodes ->
                (0 until nodes.length).map { tag to (nodes.item(it) as Element) }
            }
        }

    private fun Element.hasIntentFilter() =
        getElementsByTagName("intent-filter").length > 0

    private fun Element.exported() = getAttributeNS(androidNs, "exported")

    private fun Element.resolvedName(): String {
        val raw = getAttributeNS(androidNs, "name")
        val pkg = manifest.getAttribute("package").ifEmpty { BuildConfig.APPLICATION_ID }
        return when {
            raw.startsWith(".") -> pkg + raw
            "." !in raw -> "$pkg.$raw"
            else -> raw
        }
    }

    @Test
    fun `components with intent-filter declare exported explicitly`() {
        val ambiguous = components()
            .filter { (_, el) -> el.hasIntentFilter() && el.exported().isEmpty() }
            .map { (tag, el) -> "<$tag ${el.resolvedName()}>" }
        assertEquals("implicitly exported components: $ambiguous", emptyList<String>(), ambiguous)
    }

    @Test
    fun `exported components stay inside the reviewed allowlist`() {
        val exported = components()
            .filter { (_, el) -> el.exported() == "true" }
            .map { (_, el) -> el.resolvedName() }
            .toSet()
        assertEquals(exportedAllowlist, exported)
    }

    @Test
    fun `requested permissions stay inside the allowlist`() {
        val requested = listOf("uses-permission", "uses-permission-sdk-23")
            .flatMap { tag ->
                manifest.getElementsByTagName(tag).let { nodes ->
                    (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(androidNs, "name") }
                }
            }
            .toSet()
        val unexpected = requested - permissionAllowlist
        assertEquals("unallowlisted permissions: $unexpected", emptySet<String>(), unexpected)
    }

    @Test
    fun `application is not debuggable`() {
        val app = manifest.getElementsByTagName("application").item(0) as Element
        assertEquals("", app.getAttributeNS(androidNs, "debuggable"))
    }
}
