package com.arktosbrowser.mobile.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards upstream SmartCookieWeb #311: `URLUtil.guessFileName` produced
 * `*.bin` whenever the URL lacked an extension and the mime was opaque, and
 * split multi-dot names at the first '.'. [DownloadNaming] prefers
 * Content-Disposition, then the URL path extension, then a mime-derived
 * extension, and only falls back to .bin when nothing else is known.
 */
class DownloadNamingTest {

    @Test
    fun `content-disposition filename wins`() {
        assertEquals(
            "report.pdf",
            DownloadNaming.resolveFileName(
                "https://example.com/get?id=42",
                "attachment; filename=\"report.pdf\"",
                "application/pdf"
            )
        )
    }

    @Test
    fun `rfc5987 filename-star is parsed and decoded`() {
        assertEquals(
            "grüße.txt",
            DownloadNaming.resolveFileName(
                "https://example.com/get?id=42",
                "attachment; filename*=utf-8''gr%C3%BC%C3%9Fe.txt",
                "text/plain"
            )
        )
    }

    @Test
    fun `content-disposition name without extension still gets mime extension`() {
        assertEquals(
            "document.pdf",
            DownloadNaming.resolveFileName(
                "https://example.com/get?id=42",
                "attachment; filename=document",
                "application/pdf"
            )
        )
    }

    @Test
    fun `url path extension is preserved for multi-dot names`() {
        assertEquals(
            "myapp-1.2.3.apk",
            DownloadNaming.resolveFileName(
                "https://example.com/files/myapp-1.2.3.apk",
                null,
                "application/octet-stream"
            )
        )
    }

    @Test
    fun `url path extension wins over mismatched mime`() {
        assertEquals(
            "download.php",
            DownloadNaming.resolveFileName(
                "https://example.com/dl/download.php",
                null,
                "application/vnd.android.package-archive"
            )
        )
    }

    @Test
    fun `extensionless url gets mime extension`() {
        assertEquals(
            "file.apk",
            DownloadNaming.resolveFileName(
                "https://example.com/dl/file",
                null,
                "application/vnd.android.package-archive"
            )
        )
    }

    @Test
    fun `octet-stream with no information still falls back to bin`() {
        assertEquals(
            "file.bin",
            DownloadNaming.resolveFileName(
                "https://example.com/dl/file",
                null,
                "application/octet-stream"
            )
        )
    }

    @Test
    fun `html responses are named html not the hoped-for extension`() {
        // upstream #303 — a page answer must never masquerade as the file
        assertEquals(
            "page.html",
            DownloadNaming.resolveFileName(
                "https://example.com/dl/page",
                null,
                "text/html; charset=utf-8"
            )
        )
    }

    @Test
    fun `percent-encoded url names are decoded`() {
        assertEquals(
            "my file.zip",
            DownloadNaming.resolveFileName(
                "https://example.com/files/my%20file.zip?token=abc",
                null,
                "application/zip"
            )
        )
    }

    @Test
    fun `trailing slash url falls back to download`() {
        assertEquals(
            "download.zip",
            DownloadNaming.resolveFileName(
                "https://example.com/files/",
                null,
                "application/zip"
            )
        )
    }

    @Test
    fun `query-only url yields download plus mime extension`() {
        assertEquals(
            "download.pdf",
            DownloadNaming.resolveFileName(
                "https://example.com/?id=5",
                null,
                "application/pdf"
            )
        )
    }

    @Test
    fun `everything unknown still yields a name`() {
        assertEquals("download.bin", DownloadNaming.resolveFileName(null, null, null))
    }

    @Test
    fun `malformed content-disposition falls through to url`() {
        // missing '=' makes the parser throw
        assertEquals(
            "file.zip",
            DownloadNaming.resolveFileName(
                "https://example.com/file.zip",
                "attachment; filename",
                "application/zip"
            )
        )
        // parses to a garbage-only name — rejected
        assertEquals(
            "file.zip",
            DownloadNaming.resolveFileName(
                "https://example.com/file.zip",
                "attachment; filename===\"",
                "application/zip"
            )
        )
    }

    @Test
    fun `dotfile url segment is not treated as an extension`() {
        assertNull(DownloadNaming.extensionFromFileName(".profile"))
        assertNull(DownloadNaming.extensionFromFileName("noext"))
        assertEquals(".gz", DownloadNaming.extensionFromFileName("archive.tar.gz"))
    }
}
