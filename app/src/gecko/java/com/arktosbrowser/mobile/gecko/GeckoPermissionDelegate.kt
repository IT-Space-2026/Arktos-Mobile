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
    private val userPreferences: UserPreferences,
    persist: Boolean
) : GeckoSession.PermissionDelegate {

    /**
     * Per-origin decisions — Firefox's permission model: answered
     * prompts must not come back. Sites re-request on
     * focus/visibilitychange (which fires on every session re-bind,
     * i.e. tab switch), so without a remembered answer the prompt
     * returns on every switch. For regular tabs the answers persist
     * across restarts via SharedPreferences; incognito sessions keep
     * them memory-only for the tab's lifetime.
     */
    private val prefs = if (persist) {
        activity.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE)
    } else {
        null
    }
    private val decisions = mutableMapOf<String, Int>()

    private fun decisionFor(key: String): Int? =
        decisions[key] ?: prefs?.getInt(key, ABSENT)?.takeIf { it != ABSENT }

    private fun recordDecision(key: String, value: Int) {
        decisions[key] = value
        prefs?.edit()?.putInt(key, value)?.apply()
    }

    private fun originOf(uri: String): String =
        try {
            val u = java.net.URI(uri)
            "${u.scheme ?: ""}://${u.host ?: uri}${if (u.port > 0) ":${u.port}" else ""}"
        } catch (_: Exception) { uri }

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
        decisionFor("${originOf(perm.uri)}|${perm.permission}")?.let {
            return GeckoResult.fromValue(it)
        }
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
        val key = "${originOf(perm.uri)}|${perm.permission}"
        fun answer(value: Int) {
            recordDecision(key, value)
            result.complete(value)
        }
        uiController.requestRuntimePermissions(
            setOf(Manifest.permission.ACCESS_FINE_LOCATION)
        ) { granted ->
            if (!granted) {
                answer(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
                return@requestRuntimePermissions
            }
            activity.runOnUiThread {
                val org = perm.uri.let { if (it.length > 50) it.take(50) + "…" else it }
                MaterialAlertDialogBuilder(activity).apply {
                    setTitle(R.string.location)
                    setMessage(org + activity.getString(R.string.message_location))
                    setCancelable(true)
                    setOnCancelListener {
                        answer(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
                    }
                    setPositiveButton(R.string.action_allow) { _, _ ->
                        answer(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
                    }
                    setNegativeButton(R.string.action_dont_allow) { _, _ ->
                        answer(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
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
        val key = "${originOf(perm.uri)}|${perm.permission}"
        fun answer(value: Int) {
            recordDecision(key, value)
            result.complete(value)
        }
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
                    answer(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
                },
                negativeButton = DialogItem(title = R.string.action_dont_allow) {
                    answer(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
                },
                onCancel = {
                    answer(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
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
        val key = "media|${originOf(uri)}"
        decisionFor(key)?.let { decision ->
            if (decision == GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW) {
                callback.grant(video?.firstOrNull(), audio?.firstOrNull())
            } else {
                callback.reject()
            }
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
                    recordDecision(key, GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
                    callback.grant(video?.firstOrNull(), audio?.firstOrNull())
                },
                negativeButton = DialogItem(title = R.string.action_dont_allow) {
                    recordDecision(key, GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
                    callback.reject()
                },
                onCancel = {
                    recordDecision(key, GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
                    callback.reject()
                }
            )
        }
    }

    private companion object {
        private const val PREFS_NAME = "gecko_site_permissions"
        private const val ABSENT = Int.MIN_VALUE
    }
}
