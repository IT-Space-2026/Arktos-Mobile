package com.arktosbrowser.mobile.browser

import com.arktosbrowser.mobile.rust.ArktosCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FIXB07 — app-lock decision tests (upstream #302). The old dialog
 * called `finishAffinity()` on any non-match, so one mistyped
 * password closed the app with no feedback; the gate now returns
 * [AppLockGate.Result.Retry] so the prompt stays open.
 */
class AppLockGateTest {

    @Test
    fun `correct password unlocks`() {
        val stored = ArktosCore.hashPassword("s3cret")
        val result = AppLockGate().check(stored, "s3cret") as AppLockGate.Result.Unlocked
        assertNull(result.upgradedHash)
    }

    @Test
    fun `wrong password retries instead of closing`() {
        val stored = ArktosCore.hashPassword("s3cret")
        assertEquals(AppLockGate.Result.Retry, AppLockGate().check(stored, "typo"))
    }

    @Test
    fun `legacy plaintext match upgrades the stored hash`() {
        val result = AppLockGate().check("hunter2", "hunter2") as AppLockGate.Result.Unlocked
        assertTrue(result.upgradedHash!!.startsWith("arktos:v1:"))
    }

    @Test
    fun `malformed stored hash retries`() {
        assertEquals(
            AppLockGate.Result.Retry,
            AppLockGate().check("arktos:v1:not-a-phc", "x")
        )
    }

    @Test
    fun `verifier failure retries instead of crashing`() {
        val gate = AppLockGate { _, _ -> throw RuntimeException("ffi boom") }
        assertEquals(AppLockGate.Result.Retry, gate.check("stored", "candidate"))
    }
}
