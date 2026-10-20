package com.arktosbrowser.mobile.browser

import android.os.SystemClock

/**
 * SEC01 — app-wide lock state, shared by every activity in the
 * process. The incognito activity runs in its own process, so it
 * carries an independent lock state and always re-prompts on its own
 * cold start.
 *
 * Timestamps are `SystemClock.elapsedRealtime()` — monotonic, so a
 * wall-clock change can't extend or bypass the re-lock window.
 */
object AppLockState {

    /** True once the gate was passed this session. */
    var unlocked = false
        private set

    /** When the app last went fully background (all activities stopped). */
    var backgroundedAt: Long? = null
        private set

    fun onAppBackgrounded(nowMs: Long = SystemClock.elapsedRealtime()) {
        backgroundedAt = nowMs
    }

    fun onUnlocked() {
        unlocked = true
        backgroundedAt = null
    }

    /**
     * Whether the gate must be shown. Side effects: a timed-out
     * background marks the session locked again; a return inside the
     * grace window clears the countdown.
     */
    fun needsUnlock(
        method: AppLockMethod,
        relockMinutes: Int,
        nowMs: Long = SystemClock.elapsedRealtime()
    ): Boolean {
        if (method == AppLockMethod.NONE) return false
        if (!unlocked) return true
        val since = backgroundedAt ?: return false
        if (nowMs - since >= relockMinutes * 60_000L) {
            unlocked = false
            return true
        }
        backgroundedAt = null
        return false
    }

    @androidx.annotation.VisibleForTesting
    fun reset() {
        unlocked = false
        backgroundedAt = null
    }
}
