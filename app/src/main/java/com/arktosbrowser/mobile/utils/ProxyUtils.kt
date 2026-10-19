package com.arktosbrowser.mobile.utils

import android.app.Activity
import android.util.Log
import com.arktosbrowser.mobile.BrowserApp
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.browser.ProxyChoice
import com.arktosbrowser.mobile.extensions.snackbar
import com.arktosbrowser.mobile.i2p.I2PAndroidHelper
import com.arktosbrowser.mobile.preference.DeveloperPreferences
import com.arktosbrowser.mobile.preference.UserPreferences
import info.guardianproject.netcipher.webkit.WebkitProxy
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProxyUtils @Inject constructor(
    private val userPreferences: UserPreferences,
    private val developerPreferences: DeveloperPreferences,
    private val i2PAndroidHelper: I2PAndroidHelper
) {

    /*
     * If Orbot/Tor or I2P is installed, prompt the user if they want to enable
     * proxying for this session
     */
    fun checkForProxy(activity: Activity) {
    }

    /*
     * Initialize WebKit Proxying
     */
    private fun initializeProxy(activity: Activity) {
        val host: String
        val port: Int

        when (userPreferences.proxyChoice) {
            ProxyChoice.NONE ->
                // We shouldn't be here
                return
            ProxyChoice.ORBOT -> {
                host = "localhost"
                port = 8118
            }
            ProxyChoice.I2P -> {
                sI2PProxyInitialized = true
                if (sI2PHelperBound && !i2PAndroidHelper.isI2PAndroidRunning()) {
                    i2PAndroidHelper.requestI2PAndroidStart(activity)
                }
                host = "localhost"
                port = 4444
            }
            ProxyChoice.MANUAL -> {
                host = userPreferences.proxyHost
                port = userPreferences.proxyPort
            }
            else -> {
                host = userPreferences.proxyHost
                port = userPreferences.proxyPort
            }
        }

        try {
            WebkitProxy.setProxy(BrowserApp::class.java.name, activity.applicationContext, null, host, port)
        } catch (e: Exception) {
            Log.d(TAG, "error enabling web proxying", e)
        }
    }

    fun isProxyReady(activity: Activity): Boolean {
        if (userPreferences.proxyChoice == ProxyChoice.I2P) {
            if (!i2PAndroidHelper.isI2PAndroidRunning()) {
                activity.snackbar(R.string.i2p_not_running)
                return false
            } else if (!i2PAndroidHelper.areTunnelsActive()) {
                activity.snackbar(R.string.i2p_tunnels_not_ready)
                return false
            }
        }

        return true
    }

    fun updateProxySettings(activity: Activity) {
        if (userPreferences.proxyChoice != ProxyChoice.NONE) {
            initializeProxy(activity)
        } else {
            try {
                WebkitProxy.resetProxy(BrowserApp::class.java.name, activity.applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to reset proxy", e)
            }

            sI2PProxyInitialized = false
        }
    }

    fun onStop() {
        i2PAndroidHelper.unbind()
        sI2PHelperBound = false
    }

    fun onStart(activity: Activity) {
        if (userPreferences.proxyChoice == ProxyChoice.I2P) {
            // Try to bind to I2P Android
            i2PAndroidHelper.bind {
                sI2PHelperBound = true
                if (sI2PProxyInitialized && !i2PAndroidHelper.isI2PAndroidRunning()) {
                    i2PAndroidHelper.requestI2PAndroidStart(activity)
                }
            }
        }
    }

    companion object {
        private const val TAG = "ProxyUtils"

        // Helper
        private var sI2PHelperBound: Boolean = false
        private var sI2PProxyInitialized: Boolean = false

        @JvmStatic
        fun sanitizeProxyChoice(choice: ProxyChoice, activity: Activity): ProxyChoice {
            var choice = choice
            when (choice) {
                ProxyChoice.ORBOT -> {
                }
                ProxyChoice.I2P -> {
                    val ih = I2PAndroidHelper(activity.application)
                    if (!ih.isI2PAndroidInstalled()) {
                        choice = ProxyChoice.NONE
                        ih.promptToInstall(activity)
                    }
                }
                ProxyChoice.MANUAL -> {
                }
                else -> {
                }
            }
            return choice
        }
    }
}
