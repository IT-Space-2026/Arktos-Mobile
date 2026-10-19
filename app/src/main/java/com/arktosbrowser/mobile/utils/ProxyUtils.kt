package com.arktosbrowser.mobile.utils

import android.app.Activity
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.browser.ProxyChoice
import com.arktosbrowser.mobile.extensions.snackbar
import com.arktosbrowser.mobile.i2p.I2PAndroidHelper
import com.arktosbrowser.mobile.preference.UserPreferences
import java.net.InetSocketAddress
import java.net.Proxy
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProxyUtils @Inject constructor(
    private val userPreferences: UserPreferences,
    private val i2PAndroidHelper: I2PAndroidHelper
) {

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

        // WebView-specific adapter — the only WebView-coupled piece of proxy
        // support. A future engine would get its own applier behind this seam.
        try {
            applyWebViewProxy(activity, host, port)
        } catch (e: Exception) {
            Log.d(TAG, "error enabling web proxying", e)
        }
    }

    private fun applyWebViewProxy(activity: Activity, host: String, port: Int) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            Log.w(TAG, "WebView on this device does not support proxy override")
            return
        }
        val config = ProxyConfig.Builder().addProxyRule("$host:$port").build()
        ProxyController.getInstance()
            .setProxyOverride(config, ContextCompat.getMainExecutor(activity)) {}
    }

    private fun clearWebViewProxy(activity: Activity) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            return
        }
        ProxyController.getInstance()
            .clearProxyOverride(ContextCompat.getMainExecutor(activity)) {}
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
                clearWebViewProxy(activity)
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

        /**
         * The [Proxy] matching the configured choice, or null for direct connections.
         * Used by non-WebView fetches (reader mode, favicon resolution, download
         * probes, userscript resources) so they do not silently bypass Tor/I2P/manual.
         */
        @JvmStatic
        fun connectionProxy(userPreferences: UserPreferences): Proxy? = when (userPreferences.proxyChoice) {
            ProxyChoice.ORBOT -> Proxy(Proxy.Type.HTTP, InetSocketAddress("localhost", 8118))
            ProxyChoice.I2P -> Proxy(Proxy.Type.HTTP, InetSocketAddress("localhost", 4444))
            ProxyChoice.MANUAL -> Proxy(Proxy.Type.HTTP,
                InetSocketAddress(userPreferences.proxyHost, userPreferences.proxyPort))
            else -> null
        }

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
