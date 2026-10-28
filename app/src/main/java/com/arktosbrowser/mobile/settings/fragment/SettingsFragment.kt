// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.settings.fragment

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import com.arktosbrowser.mobile.BuildConfig
import com.arktosbrowser.mobile.R


class SettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, s: String?) {
        addPreferencesFromResource(R.xml.preferences_headers)
        // Sync lives in the gecko flavor only — hide its header on
        // Light so it never reaches a fragment that isn't compiled.
        findPreference<androidx.preference.Preference>("pref_settings_sync")?.isVisible =
            BuildConfig.IS_GECKO
    }
}