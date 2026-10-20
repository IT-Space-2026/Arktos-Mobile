@file:Suppress("NOTHING_TO_INLINE")

package com.arktosbrowser.mobile.extensions

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.LayoutInflater
import android.widget.Toast
import androidx.annotation.*
import androidx.core.content.ContextCompat
import java.util.*

/**
 * Returns the dimension in pixels.
 *
 * @param dimenRes the dimension resource to fetch.
 */
inline fun Context.dimen(@DimenRes dimenRes: Int): Int = resources.getDimensionPixelSize(dimenRes)

/**
 * Returns the [ColorRes] as a [ColorInt]
 */
@ColorInt
inline fun Context.color(@ColorRes colorRes: Int): Int = ContextCompat.getColor(this, colorRes)

/**
 * Shows a toast with the provided [StringRes].
 */
inline fun Context.toast(@StringRes stringRes: Int) = Toast.makeText(this, stringRes, Toast.LENGTH_SHORT).show()

/**
 * The [LayoutInflater] available on the [Context].
 */
inline val Context.inflater: LayoutInflater
    get() = LayoutInflater.from(this)

/**
 * Gets a drawable from the context.
 */
inline fun Context.drawable(@DrawableRes drawableRes: Int): Drawable = ContextCompat.getDrawable(this, drawableRes)!!

/**
 * The preferred locale of the user.
 */
val Context.preferredLocale: Locale
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        resources.configuration.locales[0]
    } else {
        @Suppress("DEPRECATION")
        resources.configuration.locale
    }

/**
 * True while the device is in night mode (system dark theme enabled).
 */
inline val Context.isNightMode: Boolean
    get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
        Configuration.UI_MODE_NIGHT_YES

/**
 * overridePendingTransition replacement (deprecated in API 34): applies
 * the open/close window transition on API 34+ via overrideActivityTransition.
 */
fun Activity.overrideOpenTransition(@AnimRes enterAnim: Int, @AnimRes exitAnim: Int) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, enterAnim, exitAnim)
    } else {
        @Suppress("DEPRECATION")
        overridePendingTransition(enterAnim, exitAnim)
    }
}

/**
 * Same as [overrideOpenTransition] but for the activity-closing direction.
 */
fun Activity.overrideCloseTransition(@AnimRes enterAnim: Int, @AnimRes exitAnim: Int) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, enterAnim, exitAnim)
    } else {
        @Suppress("DEPRECATION")
        overridePendingTransition(enterAnim, exitAnim)
    }
}
