// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.browser

import com.arktosbrowser.mobile.update.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UPDT01 — the release-tag → version parsing and semver compare
 * behind the daily update check.
 */
class UpdateCheckerTest {

    @Test
    fun `versionOf parses release tags`() {
        assertEquals("1.1.0", UpdateChecker.versionOf("v1.1"))
        assertEquals("1.2.3", UpdateChecker.versionOf("v1.2.3"))
        assertEquals("2.0.0", UpdateChecker.versionOf("2.0"))
        assertNull(UpdateChecker.versionOf("latest"))
        assertNull(UpdateChecker.versionOf(""))
    }

    @Test
    fun `isNewer compares semver strictly`() {
        assertTrue(UpdateChecker.isNewer("1.2.0", "1.1"))
        assertTrue(UpdateChecker.isNewer("1.1.1", "1.1"))
        assertTrue(UpdateChecker.isNewer("2.0.0", "1.9.9"))
        assertFalse(UpdateChecker.isNewer("1.1.0", "1.1"))
        assertFalse(UpdateChecker.isNewer("1.0.9", "1.1"))
        assertFalse(UpdateChecker.isNewer("1.1.0", "1.1.0"))
    }

}
