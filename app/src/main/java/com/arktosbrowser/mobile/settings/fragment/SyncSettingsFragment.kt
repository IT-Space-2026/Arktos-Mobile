// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.settings.fragment

import android.os.Bundle
import android.widget.Toast
import com.arktosbrowser.mobile.R

/**
 * MSYNC01 — the Synchronization section's shell: device identity,
 * sync status and the paired-device list. Pairing (Noise + QR),
 * store adapters and transport are staged milestones on top of
 * this foundation.
 */
class SyncSettingsFragment : AbstractSettingsFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preference_sync, rootKey)

        findPreference<androidx.preference.Preference>("pref_sync_device_name")?.summary =
            android.os.Build.MODEL

        findPreference<androidx.preference.Preference>("pref_sync_pair")
            ?.setOnPreferenceClickListener {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.sync_coming_soon),
                    Toast.LENGTH_SHORT
                ).show()
                true
            }
    }
}
