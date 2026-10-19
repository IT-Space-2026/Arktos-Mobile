package com.arktosbrowser.mobile

import com.arktosbrowser.mobile.preference.IntEnum

/**
 * The available app themes. [SYSTEM] follows the device night-mode setting;
 * it must be resolved through [resolve] before a concrete theme is applied.
 *
 * The backing [value]s are persisted — do not reuse or reorder them.
 */
enum class AppTheme(override val value: Int) : IntEnum {
    SYSTEM(3),
    LIGHT(0),
    DARK(1),
    BLACK(2)/*,
    BLUE(3),
    YELLOW(4),
    GREEN(5)*/
}

/**
 * Resolves [AppTheme.SYSTEM] to [AppTheme.DARK] or [AppTheme.LIGHT] based on
 * the device's current night-mode state. Every other theme returns itself.
 */
fun AppTheme.resolve(systemNightMode: Boolean): AppTheme =
    if (this == AppTheme.SYSTEM) {
        if (systemNightMode) AppTheme.DARK else AppTheme.LIGHT
    } else {
        this
    }

/**
 * True when web content should be darkened: either the dark-mode addon is
 * explicitly enabled, or the theme follows the system and the system is in
 * night mode.
 */
fun isDarkWebAddonEnabled(extensionEnabled: Boolean, theme: AppTheme, systemNightMode: Boolean): Boolean =
    extensionEnabled || (theme == AppTheme.SYSTEM && systemNightMode)
