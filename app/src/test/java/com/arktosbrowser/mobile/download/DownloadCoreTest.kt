// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.download

import com.arktosbrowser.mobile.rust.ArktosCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PORT01 — download filename/MIME core seam tests. The host-built
 * libarktos.so is on `jna.library.path`, so these exercise the real
 * Rust implementation through `DownloadHandler`'s delegate methods.
 */
class DownloadCoreTest {

    @Test
    fun `content-disposition filename parses`() {
        assertEquals(
            "report.pdf",
            ArktosCore.contentDispositionFilename("attachment; filename=\"report.pdf\"")
        )
        assertNull(ArktosCore.contentDispositionFilename("attachment"))
    }

    @Test
    fun `rfc5987 filename star parses`() {
        assertEquals(
            "€ rates.pdf",
            ArktosCore.contentDispositionFilename("attachment; filename*=UTF-8''%E2%82%AC%20rates.pdf")
        )
    }

    @Test
    fun `downloadFileName prefers the header`() {
        assertEquals(
            "r.pdf",
            DownloadNaming.resolveFileName(
                "https://x.com/get?id=1",
                "attachment; filename=\"r.pdf\"",
                "application/pdf"
            )
        )
    }

    @Test
    fun `downloadFileName honors filename-star-only headers`() {
        // Upstream #270-class bug: a filename*-only header was ignored.
        assertEquals(
            "report.pdf",
            DownloadNaming.resolveFileName(
                "https://x.com/dl",
                "attachment; filename*=UTF-8''report.pdf",
                null
            )
        )
    }

    @Test
    fun `downloadFileName falls back to url path and mime`() {
        assertEquals(
            "pic.png",
            DownloadNaming.resolveFileName("https://x.com/a/b/pic.png?x=1", null, null)
        )
        assertEquals(
            "d.txt",
            DownloadNaming.resolveFileName("https://x.com/d", null, "text/plain")
        )
    }

    @Test
    fun `mimeTypeForExtension maps common types`() {
        assertEquals("image/jpeg", ArktosCore.mimeTypeForExtension("jpg"))
        assertNull(ArktosCore.mimeTypeForExtension("notanextension"))
    }
}
