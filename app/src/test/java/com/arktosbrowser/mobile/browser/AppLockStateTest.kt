// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SEC01 — re-lock timing tests. All timestamps are passed explicitly
 * so the monotonic-clock logic runs on the JVM without Android.
 */
class AppLockStateTest {

    @Before
    fun reset() = AppLockState.reset()

    @Test
    fun `no method never locks`() {
        assertFalse(AppLockState.needsUnlock(AppLockMethod.NONE, 0, 100))
        assertFalse(AppLockState.needsUnlock(AppLockMethod.NONE, 10, Long.MAX_VALUE))
    }

    @Test
    fun `cold start requires unlock`() {
        assertTrue(AppLockState.needsUnlock(AppLockMethod.PIN, 10, 0))
    }

    @Test
    fun `immediate relock locks on any backgrounding`() {
        AppLockState.onUnlocked()
        AppLockState.onAppBackgrounded(1_000)
        assertTrue(AppLockState.needsUnlock(AppLockMethod.PIN, 0, 1_000))
    }

    @Test
    fun `return inside window stays unlocked and clears countdown`() {
        AppLockState.onUnlocked()
        AppLockState.onAppBackgrounded(1_000)
        // 1-minute window, 30s elapsed
        assertFalse(AppLockState.needsUnlock(AppLockMethod.PASSWORD, 1, 31_000))
        // Countdown cleared — a later resume doesn't accumulate time.
        assertFalse(AppLockState.needsUnlock(AppLockMethod.PASSWORD, 1, 500_000))
    }

    @Test
    fun `elapsed window re-locks`() {
        AppLockState.onUnlocked()
        AppLockState.onAppBackgrounded(1_000)
        assertTrue(AppLockState.needsUnlock(AppLockMethod.PASSWORD, 1, 61_001))
        // Once locked, it stays locked.
        assertTrue(AppLockState.needsUnlock(AppLockMethod.PASSWORD, 1, 62_000))
    }

    @Test
    fun `no backgrounding never re-locks`() {
        AppLockState.onUnlocked()
        assertFalse(AppLockState.needsUnlock(AppLockMethod.BIOMETRIC, 0, Long.MAX_VALUE))
    }

    @Test
    fun `custom minutes honoured`() {
        AppLockState.onUnlocked()
        AppLockState.onAppBackgrounded(10_000)
        assertFalse(AppLockState.needsUnlock(AppLockMethod.PIN, 7, 10_000 + 7 * 60_000L - 1))
        AppLockState.onAppBackgrounded(10_000)
        assertTrue(AppLockState.needsUnlock(AppLockMethod.PIN, 7, 10_000 + 7 * 60_000L))
    }
}
