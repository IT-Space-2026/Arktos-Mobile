package com.arktosbrowser.mobile.dialog

import com.arktosbrowser.mobile.dialog.TapjackingGuard.Surface
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SEC08 — obscured-touch policy coverage. Every dialog where a
 * malicious overlay could convert a tap into a security-relevant
 * action (unlock, set credential, reroute traffic, confirm a
 * download) must filter obscured touches.
 */
class TapjackingGuardTest {

    @Test
    fun `credential prompts filter obscured touches`() {
        assertTrue(TapjackingGuard.shouldFilterObscuredTouches(Surface.APP_LOCK))
        assertTrue(TapjackingGuard.shouldFilterObscuredTouches(Surface.PARENTAL_GATE))
        assertTrue(TapjackingGuard.shouldFilterObscuredTouches(Surface.CREDENTIAL_SET))
        assertTrue(TapjackingGuard.shouldFilterObscuredTouches(Surface.BACKUP_PASSWORD))
    }

    @Test
    fun `proxy and download confirms filter obscured touches`() {
        assertTrue(TapjackingGuard.shouldFilterObscuredTouches(Surface.PROXY_HOST))
        assertTrue(TapjackingGuard.shouldFilterObscuredTouches(Surface.DOWNLOAD_CONFIRM))
    }

    @Test
    fun `generic dialogs are unfiltered`() {
        assertFalse(TapjackingGuard.shouldFilterObscuredTouches(Surface.GENERIC))
    }

    @Test
    fun `every non-generic surface stays protected`() {
        // Pin the protected set: a surface must never silently drift
        // back to unfiltered.
        val unprotected = Surface.values()
            .filter { it != Surface.GENERIC }
            .filterNot(TapjackingGuard::shouldFilterObscuredTouches)
        assertTrue("unprotected sensitive surfaces: $unprotected", unprotected.isEmpty())
    }
}
