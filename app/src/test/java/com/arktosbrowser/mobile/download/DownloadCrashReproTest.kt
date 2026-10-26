// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.download

import com.arktosbrowser.mobile.rust.ArktosCore
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Repro for the v1.1 "tap release APK → app dies" report: feed the
 * exact GitHub asset values through the shared filename/rust path.
 */
class DownloadCrashReproTest {

    private val assetUrl =
        "https://github.com/IT-Space-2026/Arktos-Mobile/releases/download/v1.2/Arktos-v1.2-signed.apk"
    private val finalUrl =
        "https://release-assets.githubusercontent.com/github-production-release-asset/" +
            "1425276681/83f26cb5-9a6e-4d50-83f1-b0b07b796d81?sp=r&sv=2018-11-09&sr=b&spr=https" +
            "&se=2026-10-26T22%3A14%3A04Z&rscd=attachment%3B%20filename%3DArktos-v1.2-signed.apk" +
            "&rsct=application%2Fvnd.android.package-archive&response-content-disposition=" +
            "attachment%3B%20filename%3DArktos-v1.2-signed.apk"
    private val disposition = "attachment; filename=Arktos-v1.2-signed.apk"
    private val mime = "application/vnd.android.package-archive"

    @Test
    fun rustContentDispositionParsesGithubHeader() {
        assertEquals(
            "Arktos-v1.2-signed.apk",
            ArktosCore.contentDispositionFilename(disposition)
        )
    }

    @Test
    fun resolveFileNameForAsset() {
        assertEquals(
            "Arktos-v1.2-signed.apk",
            DownloadNaming.resolveFileName(assetUrl, disposition, mime)
        )
        assertEquals(
            "Arktos-v1.2-signed.apk",
            DownloadNaming.resolveFileName(finalUrl, disposition, mime)
        )
        // Signed URL with no usable headers — last path segment is the
        // asset GUID, so the name comes out GUID+mime extension.
        assertEquals(
            "83f26cb5-9a6e-4d50-83f1-b0b07b796d81.bin",
            DownloadNaming.resolveFileName(finalUrl, "", "")
        )
    }

    @Test
    fun rustParserEdgeInputs() {
        // Multi-byte chars, stray semicolons/quotes — none may abort.
        listOf(
            "attachment; filename*=UTF-8''%C3%A9%C3%A9.apk",
            "attachment; filename=\"a;b.apk\"",
            "attachment; filename*=UTF-8''",
            "attachment; filename*=bad'xx'föo.apk",
            "attachment; filename*=UTF-8'%C3%A9'lösung.apk",
            "attachment; filename=",
            "attachment; foo=1; filename*=UTF-8''x%2Fy.apk; z=2",
        ).forEach { ArktosCore.contentDispositionFilename(it) }
    }
}
