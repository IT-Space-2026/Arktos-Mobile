// SPDX-License-Identifier: MPL-2.0
// Copyright 2020 CookieJarApps and Arktos contributors

package com.arktosbrowser.mobile.settings.fragment

import android.os.Bundle
import android.widget.Toast
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.browser.*
import com.arktosbrowser.mobile.di.injector
import com.arktosbrowser.mobile.dialog.BrowserDialog
import com.arktosbrowser.mobile.extensions.withSingleChoiceItems
import com.arktosbrowser.mobile.preference.UserPreferences
import javax.inject.Inject


class DrawerSettingsFragment : AbstractSettingsFragment() {

    @Inject internal lateinit var userPreferences: UserPreferences

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preference_drawers)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        injector.inject(this)

        switchPreference(
            preference = SETTINGS_DRAWERTABS,
            isChecked = userPreferences.showTabsInDrawer,
            onCheckChange = { userPreferences.showTabsInDrawer = it }
        )

        switchPreference(
                preference = SETTINGS_STACK_FROM_BOTTOM,
                isChecked = userPreferences.stackFromBottom,
                onCheckChange = { userPreferences.stackFromBottom = it; Toast.makeText(activity, R.string.please_restart, Toast.LENGTH_LONG).show() }
        )

        switchPreference(
            preference = SETTINGS_SWAPTABS,
            isChecked = userPreferences.bookmarksAndTabsSwapped,
            onCheckChange = { userPreferences.bookmarksAndTabsSwapped = it }
        )

        switchPreference(
            preference = SETTINGS_SWAPICONS,
            isChecked = userPreferences.bookmarksAndTabsIconsSwapped,
            onCheckChange = { userPreferences.bookmarksAndTabsIconsSwapped = it }
        )
        clickablePreference(
                preference = SETTINGS_LINES,
                onClick = ::showDrawerLines
        )

        clickablePreference(
                preference = SETTINGS_SIZE,
                onClick = ::showDrawerSize
        )
    }

    private fun showDrawerSize() {
        BrowserDialog.showCustomDialog(activity) {
            setTitle(R.string.drawer_size)
            val stringArray = resources.getStringArray(R.array.drawer_size)
            val values = DrawerSizeChoice.values().map {
                Pair(it, when (it) {
                    DrawerSizeChoice.AUTO -> stringArray[0]
                    DrawerSizeChoice.ONE -> stringArray[1]
                    DrawerSizeChoice.TWO -> stringArray[2]
                    DrawerSizeChoice.THREE -> stringArray[3]
                })
            }
            withSingleChoiceItems(values, userPreferences.drawerSize) {
                userPreferences.drawerSize = it
            }
            setPositiveButton(R.string.action_ok){_, _ ->
                Toast.makeText(activity, R.string.please_restart, Toast.LENGTH_LONG).show()
            }
        }

    }

    private fun showDrawerLines() {
        BrowserDialog.showCustomDialog(activity) {
            setTitle(R.string.drawer_lines)
            val stringArray = resources.getStringArray(R.array.drawer_lines)
            val values = DrawerLineChoice.values().map {
                Pair(it, when (it) {
                    DrawerLineChoice.ONE -> stringArray[0]
                    DrawerLineChoice.TWO -> stringArray[1]
                    DrawerLineChoice.THREE -> stringArray[2]
                })
            }
            withSingleChoiceItems(values, userPreferences.drawerLines) {
                userPreferences.drawerLines = it
            }
            setPositiveButton(R.string.action_ok){_, _ ->
                Toast.makeText(activity, R.string.please_restart, Toast.LENGTH_LONG).show()
            }
        }

    }

    companion object {
        private const val SETTINGS_DRAWERTABS = "cb_drawertabs"
        private const val SETTINGS_SWAPTABS = "cb_swapdrawers"
        private const val SETTINGS_SWAPICONS = "cb_swapicons"
        private const val SETTINGS_LINES = "drawer_lines"
        private const val SETTINGS_SIZE = "drawer_size"
        private const val SETTINGS_STACK_FROM_BOTTOM = "stack_from_bottom"
    }
}
