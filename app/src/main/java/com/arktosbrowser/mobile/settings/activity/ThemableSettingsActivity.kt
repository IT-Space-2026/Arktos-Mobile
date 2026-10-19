package com.arktosbrowser.mobile.settings.activity

import com.arktosbrowser.mobile.AppTheme
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.di.injector
import com.arktosbrowser.mobile.extensions.isNightMode
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.utils.ThemeUtils
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import javax.inject.Inject


abstract class ThemableSettingsActivity : AppCompatPreferenceActivity() {

    private var themeId: AppTheme = AppTheme.LIGHT

    @Inject internal lateinit var userPreferences: UserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        injector.inject(this)
        themeId = userPreferences.resolvedTheme(this)

        // set the theme
        when (themeId) {
            AppTheme.LIGHT -> {
                setTheme(R.style.Theme_SettingsTheme)
            }
            AppTheme.DARK -> {
                setTheme(R.style.Theme_SettingsTheme_Dark)
            }
            AppTheme.BLACK -> {
                setTheme(R.style.Theme_SettingsTheme_Black)
            }
            AppTheme.ARKTOS_LIGHT -> {
                setTheme(R.style.Theme_SettingsTheme_Arktos)
            }
            AppTheme.ARKTOS_DARK -> {
                setTheme(R.style.Theme_SettingsTheme_Arktos_Dark)
            }
            AppTheme.SYSTEM -> {
                setTheme(if (isNightMode) R.style.Theme_SettingsTheme_Arktos_Dark else R.style.Theme_SettingsTheme_Arktos)
            }
        }

        super.onCreate(savedInstanceState)

        resetPreferences()
    }

    private fun resetPreferences() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (userPreferences.useBlackStatusBar) {
                window.statusBarColor = Color.BLACK
            } else {
                window.statusBarColor = ThemeUtils.getStatusBarColor(this)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resetPreferences()
        if (userPreferences.resolvedTheme(this) != themeId) {
            recreate()
        }
    }

}
