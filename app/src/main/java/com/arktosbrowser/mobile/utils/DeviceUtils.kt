package com.arktosbrowser.mobile.utils

import android.content.Context
import android.graphics.Point
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Utils used to access information about the device.
 */
object DeviceUtils {

    /**
     * Gets the width of the device's screen.
     *
     * @param context the context used to access the [WindowManager].
     */
    @JvmStatic
    fun getScreenWidth(context: Context): Int {
        return Point().apply {
            windowManager(context).defaultDisplay.getSize(this)
        }.x
    }

    /**
     * Gets the width of the screen space currently available to the app.
     *
     * @param context the context used to access the [WindowManager].
     */
    @JvmStatic
    fun getAvailableScreenWidth(context: Context): Int {
        return DisplayMetrics().apply {
            windowManager(context).defaultDisplay.getRealMetrics(this)
        }.widthPixels
    }

    private fun windowManager(context: Context): WindowManager =
        checkNotNull(context.getSystemService(WindowManager::class.java)) {
            "WindowManager service unavailable"
        }

}
