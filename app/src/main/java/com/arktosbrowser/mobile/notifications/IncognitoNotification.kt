// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.notifications

import com.arktosbrowser.mobile.IncognitoActivity
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.utils.ThemeUtils
import android.annotation.TargetApi
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.os.BuildCompat


/**
 * A notification helper that displays the current number of tabs open in a notification as a
 * warning. When the notification is pressed, the incognito browser will open.
 *
 * INCOG03 — the reminder is kept off the lock screen by default
 * ([NotificationCompat.VISIBILITY_SECRET]); users who want it there can
 * opt in via the Privacy setting that backs [showOnLockScreen].
 */
class IncognitoNotification(
    private val context: Context,
    private val notificationManager: NotificationManager,
    private val showOnLockScreen: () -> Boolean
) {

    private val channelId = "channel_incognito"

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel() {
        val channelName = context.getString(R.string.notification_incognito_running_description)
        val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
        channel.enableVibration(false)
        channel.lockscreenVisibility = visibilityFor(showOnLockScreen())
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * On O+ the lock-screen visibility lives on the channel, and a
     * channel's visibility is locked to the system once created — so a
     * preference change can only take effect by deleting and
     * re-creating it.
     */
    @TargetApi(Build.VERSION_CODES.O)
    private fun syncChannelVisibility() {
        val current = notificationManager.getNotificationChannel(channelId)?.lockscreenVisibility
        if (channelNeedsRecreate(current, showOnLockScreen())) {
            notificationManager.deleteNotificationChannel(channelId)
            createNotificationChannel()
        }
    }

    /**
     * Shows the notification for the provided [number] of tabs. If a notification already exists,
     * it will be updated.
     *
     * @param number the number of tabs, must be > 0.
     */
    fun show(number: Int) {
        require(number > 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            syncChannelVisibility()
        }
        val incognitoIntent = IncognitoActivity.createIntent(context)

        val flags = if (BuildCompat.isAtLeastS()) {
            PendingIntent.FLAG_IMMUTABLE
        } else{
            0
        }

        val incognitoNotification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_incognito)
            .setContentTitle(context.resources.getQuantityString(R.plurals.notification_incognito_running_title, number, number))
            .setContentIntent(PendingIntent.getActivity(context, 0, incognitoIntent, flags))
            .setContentText(context.getString(R.string.notification_incognito_running_message))
            .setAutoCancel(false)
            .setColor(ThemeUtils.getAccentColor(context))
            .setOngoing(true)
            .setVisibility(visibilityFor(showOnLockScreen()))
            .build()

        notificationManager.notify(NOTIFICATION_ID, incognitoNotification)
    }

    /**
     * Hides the current notification if there is one.
     */
    fun hide() = notificationManager.cancel(NOTIFICATION_ID)

    companion object {
        /**
         * Stable notification id — the main process cancels it
         * directly on panic (INCOG02), which runs while the incognito
         * process that posted it is already gone.
         */
        const val NOTIFICATION_ID = 1

        /**
         * INCOG03 — lock-screen exposure of the private-tabs reminder.
         * `VISIBILITY_SECRET` keeps it off the lock screen entirely;
         * `VISIBILITY_PRIVATE` shows it in full when the device's
         * lock-screen-notification setting allows.
         */
        fun visibilityFor(showOnLockScreen: Boolean): Int =
            if (showOnLockScreen) {
                NotificationCompat.VISIBILITY_PRIVATE
            } else {
                NotificationCompat.VISIBILITY_SECRET
            }

        /**
         * True when the persisted channel's lock-screen visibility
         * doesn't match the preference and must be re-created.
         * A null [currentVisibility] means the channel is absent
         * (deleted/blocked by the user) — it is not resurrected.
         */
        fun channelNeedsRecreate(currentVisibility: Int?, showOnLockScreen: Boolean): Boolean =
            currentVisibility != null && currentVisibility != visibilityFor(showOnLockScreen)
    }

}
