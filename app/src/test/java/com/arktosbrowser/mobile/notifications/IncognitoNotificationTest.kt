package com.arktosbrowser.mobile.notifications

import androidx.core.app.NotificationCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * INCOG03 — the persistent "private tabs running" reminder must not be
 * readable on the lock screen by default. `visibilityFor` maps the
 * privacy toggle onto notification/channel visibility and
 * `channelNeedsRecreate` decides when a persisted O+ channel has to be
 * rebuilt (its lockscreenVisibility is system-locked after creation).
 */
class IncognitoNotificationTest {

    @Test
    fun `hidden by default — toggle off maps to VISIBILITY_SECRET`() {
        assertEquals(
            NotificationCompat.VISIBILITY_SECRET,
            IncognitoNotification.visibilityFor(showOnLockScreen = false)
        )
    }

    @Test
    fun `opted-in reminder maps to VISIBILITY_PRIVATE`() {
        assertEquals(
            NotificationCompat.VISIBILITY_PRIVATE,
            IncognitoNotification.visibilityFor(showOnLockScreen = true)
        )
    }

    @Test
    fun `channel mismatch triggers recreate`() {
        assertTrue(
            IncognitoNotification.channelNeedsRecreate(
                currentVisibility = NotificationCompat.VISIBILITY_PRIVATE,
                showOnLockScreen = false
            )
        )
        assertTrue(
            IncognitoNotification.channelNeedsRecreate(
                currentVisibility = NotificationCompat.VISIBILITY_SECRET,
                showOnLockScreen = true
            )
        )
    }

    @Test
    fun `matching channel is kept`() {
        assertFalse(
            IncognitoNotification.channelNeedsRecreate(
                currentVisibility = NotificationCompat.VISIBILITY_SECRET,
                showOnLockScreen = false
            )
        )
        assertFalse(
            IncognitoNotification.channelNeedsRecreate(
                currentVisibility = NotificationCompat.VISIBILITY_PRIVATE,
                showOnLockScreen = true
            )
        )
    }

    @Test
    fun `absent channel is not resurrected`() {
        // A user-deleted/blocked channel returns null from
        // getNotificationChannel — recreating it would undo that choice.
        assertFalse(
            IncognitoNotification.channelNeedsRecreate(
                currentVisibility = null,
                showOnLockScreen = false
            )
        )
        assertFalse(
            IncognitoNotification.channelNeedsRecreate(
                currentVisibility = null,
                showOnLockScreen = true
            )
        )
    }

}
