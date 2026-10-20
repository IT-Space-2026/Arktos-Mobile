/*
 * Copyright 2014-2017 str4d and the I2P Android project
 * SPDX-License-Identifier: Apache-2.0
 *
 * Vendored from i2p.android.base (net.i2p.android:helper:0.9.5 —
 * upstream stopped releasing in 2017). Kotlin port of
 * net.i2p.android.ui.I2PAndroidHelper, trimmed to the API surface the
 * app actually uses. The wire protocol (intents + IRouterState AIDL
 * against the I2P Android app) is unchanged.
 */

package com.arktosbrowser.mobile.i2p

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.arktosbrowser.mobile.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import net.i2p.android.router.service.IRouterState
import net.i2p.android.router.service.State

class I2PAndroidHelper @JvmOverloads constructor(
    private val context: Context,
    private val useDebug: Boolean = false
) {

    fun interface Callback {
        fun onI2PAndroidBound()
    }

    private var triedBindState = false
    private var stateService: IRouterState? = null
    private var callback: Callback? = null

    /**
     * Try to bind to I2P Android. Call this method from
     * [Activity.onStart].
     *
     * @param callback will be called once the helper has bound to the I2P app.
     */
    fun bind(callback: Callback) {
        this.callback = callback
        bind()
    }

    /** Try to bind to I2P Android. */
    fun bind() {
        Log.i(LOG_TAG, "Binding to I2P Android")
        val i2pIntent = getI2PAndroidIntent()
        if (i2pIntent != null) {
            Log.i(LOG_TAG, i2pIntent.toString())
            triedBindState = try {
                context.bindService(i2pIntent, stateConnection, Context.BIND_AUTO_CREATE)
                    .also { bound ->
                        if (!bound) Log.w(LOG_TAG, "Could not bind: bindService failed")
                    }
            } catch (e: SecurityException) {
                // Old version of I2P Android (pre-0.9.13), cannot use
                stateService = null
                Log.w(LOG_TAG, "Could not bind: I2P Android version is too old")
                false
            }
        } else {
            Log.w(LOG_TAG, "Could not bind: I2P Android not installed")
        }
    }

    private fun getI2PAndroidIntent(): Intent? {
        val intent = Intent("net.i2p.android.router.service.IRouterState")
        return when {
            useDebug && isAppInstalled(URI_I2P_ANDROID_DEBUG) -> {
                Log.w(LOG_TAG, "Using debug build of I2P Android")
                intent.setClassName(URI_I2P_ANDROID_DEBUG, ROUTER_SERVICE_CLASS)
            }
            isAppInstalled(URI_I2P_ANDROID) ->
                intent.setClassName(URI_I2P_ANDROID, ROUTER_SERVICE_CLASS)
            isAppInstalled(URI_I2P_ANDROID_DONATE) ->
                intent.setClassName(URI_I2P_ANDROID_DONATE, ROUTER_SERVICE_CLASS)
            isAppInstalled(URI_I2P_ANDROID_LEGACY) ->
                intent.setClassName(URI_I2P_ANDROID_LEGACY, ROUTER_SERVICE_CLASS)
            else -> null
        }
    }

    /** Unbind from I2P Android. Call this method from [Activity.onStop]. */
    fun unbind() {
        if (triedBindState) {
            context.unbindService(stateConnection)
        }
        triedBindState = false
        callback = null
    }

    private val stateConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            stateService = IRouterState.Stub.asInterface(service)
            Log.i(LOG_TAG, "Bound to I2P Android")
            callback?.onI2PAndroidBound()
        }

        override fun onServiceDisconnected(className: ComponentName) {
            // Called when the connection has been unexpectedly
            // disconnected — that is, its process crashed.
            Log.w(LOG_TAG, "I2P Android disconnected unexpectedly")
            stateService = null
        }
    }

    /**
     * Check if I2P Android is installed.
     *
     * @return true if I2P Android is installed, false otherwise.
     */
    fun isI2PAndroidInstalled(): Boolean =
        (useDebug && isAppInstalled(URI_I2P_ANDROID_DEBUG)) ||
            isAppInstalled(URI_I2P_ANDROID) ||
            isAppInstalled(URI_I2P_ANDROID_DONATE) ||
            isAppInstalled(URI_I2P_ANDROID_LEGACY)

    private fun isAppInstalled(uri: String): Boolean = try {
        context.packageManager.getPackageInfo(uri, PackageManager.GET_ACTIVITIES)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Show dialog - install I2P Android from market or F-Droid.
     *
     * @param activity the Activity this method has been called from.
     */
    fun promptToInstall(activity: Activity) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.install_i2p_android)
            .setMessage(R.string.you_must_have_i2p_android)
            .setPositiveButton(R.string.yes) { _, _ ->
                val uri = Uri.parse(activity.getString(R.string.market_i2p_android))
                activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
            .setNegativeButton(R.string.no) { _, _ -> }
            .show()
    }

    /**
     * Check if I2P Android is running. If [bind] has not been called
     * previously, this will always return false.
     *
     * Do not call this from [Activity.onResume]. The Android lifecycle
     * calls that method before binding the helper to the I2P app, so
     * this will always return false. Use [bind] with a [Callback]
     * instead and call this method inside the callback.
     *
     * @return true if I2P Android is running, false otherwise.
     */
    fun isI2PAndroidRunning(): Boolean {
        val service = stateService ?: return false
        return try {
            service.isStarted
        } catch (e: RemoteException) {
            Log.w(LOG_TAG, "Failed to communicate with I2P Android", e)
            false
        }
    }

    /**
     * Show dialog - request that I2P Android be started.
     *
     * @param activity the Activity this method has been called from.
     */
    fun requestI2PAndroidStart(activity: Activity) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.start_i2p_android)
            .setMessage(R.string.would_you_like_to_start_i2p_android)
            .setPositiveButton(R.string.yes) { _, _ ->
                val i = Intent("net.i2p.android.router.START_I2P")
                // Result was never consumed; this just kicks the router app.
                activity.startActivity(i)
            }
            .setNegativeButton(R.string.no) { _, _ -> }
            .show()
    }

    /**
     * Check if tunnels are active. This is the best indicator that the
     * default tunnels (such as the HTTP proxy tunnel at localhost:4444)
     * are ready to be used. If [bind] has not been called previously,
     * this will always return false.
     *
     * @return true if tunnels are active, false otherwise.
     */
    fun areTunnelsActive(): Boolean {
        val service = stateService ?: return false
        return try {
            service.state === State.ACTIVE
        } catch (e: RemoteException) {
            Log.w(LOG_TAG, "Failed to communicate with I2P Android", e)
            false
        }
    }

    companion object {
        const val URI_I2P_ANDROID = "net.i2p.android"
        const val URI_I2P_ANDROID_DONATE = "net.i2p.android.donate"
        const val URI_I2P_ANDROID_LEGACY = "net.i2p.android.router"
        const val URI_I2P_ANDROID_DEBUG = "net.i2p.android.debug"

        private const val ROUTER_SERVICE_CLASS =
            "net.i2p.android.router.service.RouterService"
        private const val LOG_TAG = "I2PHelperLib"
    }
}
