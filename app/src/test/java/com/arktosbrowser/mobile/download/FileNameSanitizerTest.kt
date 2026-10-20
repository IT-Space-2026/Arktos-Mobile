package com.arktosbrowser.mobile.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FileNameSanitizerTest {

    @Test
    fun `plain filename passes through`() {
        assertEquals("report.pdf", DownloadNaming.sanitizeFileName("report.pdf"))
    }

    @Test
    fun `path traversal is stripped`() {
        assertEquals("passwd", DownloadNaming.sanitizeFileName("../../etc/passwd"))
        assertEquals("evil.apk", DownloadNaming.sanitizeFileName("..\\..\\evil.apk"))
        assertFalse(DownloadNaming.sanitizeFileName("a/../../b.txt").contains(".."))
    }

    @Test
    fun `directory components are removed`() {
        assertEquals("file.zip", DownloadNaming.sanitizeFileName("dir/sub/file.zip"))
        assertEquals("file.zip", DownloadNaming.sanitizeFileName("C:\\dir\\file.zip"))
    }

    @Test
    fun `null and blank inputs fall back to default`() {
        assertEquals("download", DownloadNaming.sanitizeFileName(null))
        assertEquals("download", DownloadNaming.sanitizeFileName(""))
        assertEquals("download", DownloadNaming.sanitizeFileName("   "))
        assertEquals("download", DownloadNaming.sanitizeFileName("../"))
    }
}
