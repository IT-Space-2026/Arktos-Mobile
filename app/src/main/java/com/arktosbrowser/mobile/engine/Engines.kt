// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.engine

/**
 * Process-wide registry for the active engine [Backend].
 *
 * `BrowserApp` installs the `WebViewEngine` adapter at startup; every
 * engine-neutral caller reaches engine services (the default cookie/
 * storage partition, page construction, the system user agent) through
 * here instead of touching the platform webview API directly.
 */
object Engines {

    @Volatile
    private var installed: Backend? = null

    val backend: Backend
        get() = checkNotNull(installed) { "Engines.install() was never called" }

    /** The process-default cookie/storage partition. */
    val defaultProfile: Profile
        get() = backend.defaultProfile

    fun install(backend: Backend) {
        installed = backend
    }
}
