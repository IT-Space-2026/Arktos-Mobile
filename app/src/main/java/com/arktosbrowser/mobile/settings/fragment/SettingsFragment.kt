// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.settings.fragment

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import com.arktosbrowser.mobile.R


class SettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, s: String?) {
        addPreferencesFromResource(R.xml.preferences_headers)
    }
}