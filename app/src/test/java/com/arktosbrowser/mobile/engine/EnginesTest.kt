// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.engine

import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/**
 * ENG02 — the process-wide engine registry hands the installed backend
 * (and through it the default profile) to every engine-neutral caller.
 */
class EnginesTest {

    @Test
    fun `installed backend and its default profile are reachable`() {
        val profile = mock<Profile> { on { storageName } doReturn "default" }
        val backend = mock<Backend> {
            on { id } doReturn "webview"
            on { defaultProfile } doReturn profile
        }

        Engines.install(backend)

        assertSame(backend, Engines.backend)
        assertSame(profile, Engines.defaultProfile)
    }
}
