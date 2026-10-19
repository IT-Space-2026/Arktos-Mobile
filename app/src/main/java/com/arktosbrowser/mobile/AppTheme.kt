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
    BLACK(2),
    ARKTOS_LIGHT(4),
    ARKTOS_DARK(5)/*,
    BLUE(3),
    YELLOW(4),
    GREEN(5)*/
}

/**
 * Whether the theme renders dark surfaces. `BLACK` (AMOLED) and the Arktos
 * dark variant both count; `SYSTEM` must be resolved via [resolvedTheme]
 * against the current configuration first.
 */
val AppTheme.isDark: Boolean
    get() = this == AppTheme.DARK || this == AppTheme.BLACK || this == AppTheme.ARKTOS_DARK

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
