// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.settings.fragment

import android.os.Bundle
import android.widget.Toast
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.rust.ArktosCore
import org.json.JSONObject

/**
 * MSYNC02 — drives the ported sync core from the Synchronization
 * section: the pair action creates a real invite payload (the QR
 * text a peer scans), the paired list reads the engine's member
 * store, and the status row reflects the transport.
 */
class SyncSettingsFragment : AbstractSettingsFragment() {

    private val dataRoot get() = requireContext().filesDir.absolutePath

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preference_sync, rootKey)

        findPreference<androidx.preference.Preference>("pref_sync_device_name")?.summary =
            android.os.Build.MODEL

        findPreference<androidx.preference.Preference>("pref_sync_status")?.title =
            if (ArktosCore.syncTransportRunning(dataRoot)) "Sync running"
            else getString(R.string.sync_status_not_connected)

        findPreference<androidx.preference.Preference>("pref_sync_device_list")?.summary =
            membersSummary()

        findPreference<androidx.preference.Preference>("pref_sync_pair")
            ?.setOnPreferenceClickListener {
                val payload = ArktosCore.syncInviteBegin(dataRoot, "bookmarks")
                showInvite(payload)
                true
            }
    }

    private fun membersSummary(): String {
        return try {
            val arr = org.json.JSONArray(ArktosCore.syncMembers(dataRoot))
            if (arr.length() == 0) getString(R.string.sync_no_devices)
            else (0 until arr.length()).joinToString("\n") {
                arr.getJSONObject(it).optString("id", "?")
            }
        } catch (e: Exception) {
            getString(R.string.sync_no_devices)
        }
    }

    /** Show the invite payload — the text a peer encodes/scans. */
    private fun showInvite(payloadJson: String) {
        try {
            val o = JSONObject(payloadJson)
            val qr = o.optString("qr_text", o.toString())
            val manual = o.optString("manual_code", "")
            val msg = if (manual.isNotEmpty()) "QR:\n$qr\n\nManual:\n$manual" else qr
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Pair this device")
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), payloadJson, Toast.LENGTH_LONG).show()
        }
    }
}
