package com.arktosbrowser.mobile.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SEC11 — downloads whose name or server-declared type says the payload can
 * run code must surface a warning before they are enqueued. [DownloadRisk]
 * flags executable and scriptable extensions plus extension-vs-MIME
 * disguises, while leaving ordinary files (and the FIXB01 html-instead-of-
 * file case, which has its own warning) alone.
 */
class DownloadRiskTest {

    private fun flagsOf(fileName: String, mimeType: String?): Set<DownloadRisk.Flag> =
        DownloadRisk.assess(fileName, mimeType).flags

    @Test
    fun `android packages are flagged executable`() {
        assertEquals(
            setOf(DownloadRisk.Flag.EXECUTABLE),
            flagsOf("myapp-1.2.3.apk", "application/vnd.android.package-archive")
        )
        assertTrue(DownloadRisk.assess("bundle.apks", null).isRisky)
        assertTrue(DownloadRisk.assess("app.xapk", null).isRisky)
    }

    @Test
    fun `windows executables and installers are flagged`() {
        for (name in listOf("evil.exe", "setup.msi", "run.com", "saver.scr", "click.pif")) {
            assertEquals(
                "$name should be EXECUTABLE",
                setOf(DownloadRisk.Flag.EXECUTABLE),
                flagsOf(name, "application/octet-stream")
            )
        }
    }

    @Test
    fun `cross-platform installers are flagged`() {
        for (name in listOf("lib.jar", "tool.dmg", "pack.deb", "pack.rpm", "disk.iso")) {
            assertTrue("$name should be risky", DownloadRisk.assess(name, null).isRisky)
        }
    }

    @Test
    fun `scriptable extensions are flagged`() {
        for (name in listOf("payload.js", "run.bat", "script.vbs", "tool.ps1", "page.hta", "shell.sh")) {
            assertEquals(
                "$name should be SCRIPTABLE",
                setOf(DownloadRisk.Flag.SCRIPTABLE),
                flagsOf(name, null)
            )
        }
    }

    @Test
    fun `ordinary files are not flagged`() {
        for (pair in listOf(
            "report.pdf" to "application/pdf",
            "notes.txt" to "text/plain",
            "photo.jpg" to "image/jpeg",
            "song.mp3" to "audio/mpeg",
            "archive.zip" to "application/zip",
            "file.bin" to "application/octet-stream"
        )) {
            val assessment = DownloadRisk.assess(pair.first, pair.second)
            assertFalse("${pair.first} should not be risky", assessment.isRisky)
        }
    }

    @Test
    fun `html-instead-of-file downloads are not flagged`() {
        // the FIXB01 toast already covers "server sent a page" — the risk
        // dialog must not fire on top of it
        assertFalse(DownloadRisk.assess("page.html", "text/html").isRisky)
    }

    @Test
    fun `benign extension with executable mime is a mismatch`() {
        assertEquals(
            setOf(DownloadRisk.Flag.MIME_MISMATCH),
            flagsOf("photo.jpg", "application/vnd.android.package-archive")
        )
    }

    @Test
    fun `executable extension with benign mime flags both`() {
        assertEquals(
            setOf(DownloadRisk.Flag.EXECUTABLE, DownloadRisk.Flag.MIME_MISMATCH),
            flagsOf("evil.apk", "image/png")
        )
    }

    @Test
    fun `consistent executable mime and extension is not a mismatch`() {
        assertEquals(
            setOf(DownloadRisk.Flag.EXECUTABLE),
            flagsOf("program.exe", "application/x-msdownload")
        )
        assertEquals(
            setOf(DownloadRisk.Flag.SCRIPTABLE),
            flagsOf("run.sh", "application/x-sh")
        )
    }

    @Test
    fun `generic mime types never cause a mismatch`() {
        for (mime in listOf("application/octet-stream", "binary/octet-stream", "application/force-download")) {
            assertEquals(
                "$mime should not produce a mismatch",
                setOf(DownloadRisk.Flag.EXECUTABLE),
                flagsOf("app.apk", mime)
            )
        }
    }

    @Test
    fun `extensionless file with executable mime is flagged`() {
        assertEquals(
            setOf(DownloadRisk.Flag.MIME_MISMATCH),
            flagsOf("download", "application/x-msdownload")
        )
    }

    @Test
    fun `scriptable mime with benign extension flags mismatch`() {
        assertEquals(
            setOf(DownloadRisk.Flag.MIME_MISMATCH),
            flagsOf("readme.txt", "application/x-sh")
        )
    }

    @Test
    fun `null and blank mime yield no mismatch`() {
        assertEquals(setOf(DownloadRisk.Flag.EXECUTABLE), flagsOf("app.exe", null))
        assertEquals(setOf(DownloadRisk.Flag.EXECUTABLE), flagsOf("app.exe", ""))
        assertFalse(DownloadRisk.assess("photo.jpg", null).isRisky)
    }

    @Test
    fun `mime parameters are ignored`() {
        assertEquals(
            setOf(DownloadRisk.Flag.MIME_MISMATCH),
            flagsOf("photo.jpg", "Application/X-MsDownload; charset=binary")
        )
    }

    @Test
    fun `iso with its own mime is executable but not a mismatch`() {
        assertEquals(
            setOf(DownloadRisk.Flag.EXECUTABLE),
            flagsOf("live.iso", "application/x-iso9660-image")
        )
    }
}
