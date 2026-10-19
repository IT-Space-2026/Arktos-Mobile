package com.arktosbrowser.mobile.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FileNameSanitizerTest {

    @Test
    fun `plain filename passes through`() {
        assertEquals("report.pdf", DownloadHandler.sanitizeFileName("report.pdf"))
    }

    @Test
    fun `path traversal is stripped`() {
        assertEquals("passwd", DownloadHandler.sanitizeFileName("../../etc/passwd"))
        assertEquals("evil.apk", DownloadHandler.sanitizeFileName("..\\..\\evil.apk"))
        assertFalse(DownloadHandler.sanitizeFileName("a/../../b.txt").contains(".."))
    }

    @Test
    fun `directory components are removed`() {
        assertEquals("file.zip", DownloadHandler.sanitizeFileName("dir/sub/file.zip"))
        assertEquals("file.zip", DownloadHandler.sanitizeFileName("C:\\dir\\file.zip"))
    }

    @Test
    fun `null and blank inputs fall back to default`() {
        assertEquals("download", DownloadHandler.sanitizeFileName(null))
        assertEquals("download", DownloadHandler.sanitizeFileName(""))
        assertEquals("download", DownloadHandler.sanitizeFileName("   "))
        assertEquals("download", DownloadHandler.sanitizeFileName("../"))
    }
}
