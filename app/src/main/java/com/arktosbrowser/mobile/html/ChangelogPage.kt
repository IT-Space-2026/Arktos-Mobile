package com.arktosbrowser.mobile.html

import com.arktosbrowser.mobile.rust.ArktosCore

/**
 * CHLOG01 — helpers for the bundled changelog page
 * (`assets/changelog.html`). The page is served through the
 * `arktos://changelog` chrome URL by
 * `ArktosCookieWebClient.shouldInterceptRequest`; its artwork resolves
 * as `arktos://changelog/<asset>` requests against the same
 * interceptor, so the page renders identically no matter which origin
 * it was loaded under.
 */
object ChangelogPage {

    /** Canonical in-app URL of the bundled changelog page. */
    const val URL = "arktos://changelog"

    /**
     * Adds the `arktos-dark` class to `<html>` when [dark], mirroring
     * what the generated start/error pages emit for dark themes.
     * Applied at serve time so the dark palette needs no JavaScript.
     */
    fun applyDarkTheme(html: String, dark: Boolean): String =
        if (dark) {
            html.replaceFirst("<html", "<html class=\"arktos-dark\"")
        } else {
            html
        }

    /**
     * MIME type for a changelog asset — delegates to the Rust
     * extension table, falling back to a generic stream.
     */
    fun mimeForAsset(name: String): String =
        ArktosCore.mimeTypeForExtension(name.substringAfterLast('.', ""))
            ?: "application/octet-stream"

}
