package com.arktosbrowser.mobile.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SEC07 — FLAG_SECURE coverage. Incognito windows must always be
 * protected from screenshots/recents thumbnails; regular windows only
 * when the user opts in via the Privacy→Security "Secure windows"
 * toggle.
 */
class SecureWindowPolicyTest {

    @Test
    fun `incognito windows are always secured`() {
        assertTrue(SecureWindowPolicy.shouldSecure(isIncognito = true, secureAllWindows = false))
        assertTrue(SecureWindowPolicy.shouldSecure(isIncognito = true, secureAllWindows = true))
    }

    @Test
    fun `regular windows stay unsecured by default`() {
        assertFalse(SecureWindowPolicy.shouldSecure(isIncognito = false, secureAllWindows = false))
    }

    @Test
    fun `whole-app toggle secures regular windows`() {
        assertTrue(SecureWindowPolicy.shouldSecure(isIncognito = false, secureAllWindows = true))
    }

    @Test
    fun `toggle cannot weaken incognito`() {
        // The flag is a strict OR — no combination returns false for
        // an incognito window.
        listOf(true, false).forEach { toggle ->
            assertTrue(SecureWindowPolicy.shouldSecure(isIncognito = true, secureAllWindows = toggle))
        }
    }

}
