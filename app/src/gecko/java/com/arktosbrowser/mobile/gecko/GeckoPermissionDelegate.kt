// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.gecko

import android.Manifest
import android.app.Activity
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.controller.UIController
import com.arktosbrowser.mobile.dialog.BrowserDialog
import com.arktosbrowser.mobile.dialog.DialogItem
import com.arktosbrowser.mobile.preference.UserPreferences
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.arktosbrowser.mobile.extensions.resizeAndShow
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession

/**
 * ENG04 — `GeckoSession.PermissionDelegate` parity of the WebView's
 * `ArktosCookieChromeClient` permission surface: Android runtime
 * permissions (`onAndroidPermissionsRequest` → the activity's
 * requester), site content permissions (geolocation gets the same
 * runtime-permission + confirm-dialog flow as
 * `onGeolocationPermissionsShowPrompt`), and getUserMedia requests
 * gated on the WebRTC toggle like `onPermissionRequest`.
 */
class GeckoPermissionDelegate(
    private val activity: Activity,
    private val uiController: UIController,
    private val userPreferences: UserPreferences
) : GeckoSession.PermissionDelegate {

    /** Runtime permissions Gecko asks Android for (camera, mic, …). */
    override fun onAndroidPermissionsRequest(
        session: GeckoSession,
        permissions: Array<out String>?,
        callback: GeckoSession.PermissionDelegate.Callback
    ) {
        val wanted = permissions?.toSet().orEmpty()
        if (wanted.isEmpty()) {
            callback.grant()
            return
        }
        uiController.requestRuntimePermissions(wanted) { granted ->
            if (granted) callback.grant() else callback.reject()
        }
    }

    override fun onContentPermissionRequest(
        session: GeckoSession,
        perm: GeckoSession.PermissionDelegate.ContentPermission
    ): GeckoResult<Int>? {
        return when (perm.permission) {
            GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION ->
                geolocation(perm)
            // Storage/access requests Gecko resolves on its own stay
            // default-denied to the prompt result contract — persistent
            // storage is granted silently (harmless, matches Firefox).
            GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE ->
                GeckoResult.fromValue(
                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                )
            else -> contentDialog(perm)
        }
    }

    /**
     * Geolocation — same shape as `onGeolocationPermissionsShowPrompt`:
     * runtime permission first, then the site-consent dialog.
     */
    private fun geolocation(
        perm: GeckoSession.PermissionDelegate.ContentPermission
    ): GeckoResult<Int> {
        val result = GeckoResult<Int>()
        uiController.requestRuntimePermissions(
            setOf(Manifest.permission.ACCESS_FINE_LOCATION)
        ) { granted ->
            if (!granted) {
                result.complete(
                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                )
                return@requestRuntimePermissions
            }
            activity.runOnUiThread {
                val org = perm.uri.let { if (it.length > 50) it.take(50) + "…" else it }
                MaterialAlertDialogBuilder(activity).apply {
                    setTitle(R.string.location)
                    setMessage(org + activity.getString(R.string.message_location))
                    setCancelable(true)
                    setOnCancelListener {
                        result.complete(
                            GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                        )
                    }
                    setPositiveButton(R.string.action_allow) { _, _ ->
                        result.complete(
                            GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                        )
                    }
                    setNegativeButton(R.string.action_dont_allow) { _, _ ->
                        result.complete(
                            GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                        )
                    }
                }.resizeAndShow()
            }
        }
        return result
    }

    /**
     * Notification/autoplay/XR/etc. — the generic "site wants to use
     * X" dialog the WebView shows through `requestResources`.
     */
    private fun contentDialog(
        perm: GeckoSession.PermissionDelegate.ContentPermission
    ): GeckoResult<Int> {
        val result = GeckoResult<Int>()
        activity.runOnUiThread {
            BrowserDialog.showPositiveNegativeDialog(
                activity = activity,
                title = R.string.title_permission_request,
                message = R.string.message_permission_request,
                messageArguments = arrayOf(
                    perm.uri,
                    activity.getString(
                        when (perm.permission) {
                            GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION ->
                                R.string.notification_permission
                            GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE,
                            GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE ->
                                R.string.autoplay_permission
                            GeckoSession.PermissionDelegate.PERMISSION_STORAGE_ACCESS ->
                                R.string.storage_access_permission
                            else -> R.string.permission_generic
                        }
                    )
                ),
                positiveButton = DialogItem(title = R.string.action_allow) {
                    result.complete(
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                    )
                },
                negativeButton = DialogItem(title = R.string.action_dont_allow) {
                    result.complete(
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                    )
                },
                onCancel = {
                    result.complete(
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                    )
                }
            )
        }
        return result
    }

    /**
     * getUserMedia — gated on the WebRTC toggle like the WebView's
     * `onPermissionRequest`; a granted ask picks the first offered
     * video+audio sources.
     */
    override fun onMediaPermissionRequest(
        session: GeckoSession,
        uri: String,
        video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
        audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
        callback: GeckoSession.PermissionDelegate.MediaCallback
    ) {
        if (!userPreferences.webRtcEnabled) {
            callback.reject()
            return
        }
        activity.runOnUiThread {
            val resources = buildList {
                if (!video.isNullOrEmpty()) add("camera")
                if (!audio.isNullOrEmpty()) add("microphone")
            }.joinToString("\n")
            BrowserDialog.showPositiveNegativeDialog(
                activity = activity,
                title = R.string.title_permission_request,
                message = R.string.message_permission_request,
                messageArguments = arrayOf(uri, resources),
                positiveButton = DialogItem(title = R.string.action_allow) {
                    callback.grant(video?.firstOrNull(), audio?.firstOrNull())
                },
                negativeButton = DialogItem(title = R.string.action_dont_allow) {
                    callback.reject()
                },
                onCancel = { callback.reject() }
            )
        }
    }
}
