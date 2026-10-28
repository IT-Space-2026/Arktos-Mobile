// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.settings.fragment

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.rust.ArktosCore
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import org.json.JSONObject

/**
 * MSYNC02/04 — drives the ported sync core from the Synchronization
 * section: the pair action creates a real invite payload and renders
 * its QR text as a scannable bitmap plus the manual code, the paired
 * list reads the engine's member store, and the status row reflects
 * the transport.
 */
class SyncSettingsFragment : AbstractSettingsFragment() {

    private val dataRoot get() = requireContext().filesDir.absolutePath
    private val handler = Handler(Looper.getMainLooper())

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

    /**
     * Render the invite's `qr` payload as a scannable bitmap and show
     * the `manual` code — the two pairing lanes a peer can use.
     */
    private fun showInvite(payloadJson: String) {
        try {
            val o = JSONObject(payloadJson)
            if (o.has("error")) {
                Toast.makeText(requireContext(), o.getString("error"), Toast.LENGTH_LONG).show()
                return
            }
            val qr = o.optString("qr", "")
            val manual = o.optString("manual", "")

            val dp = resources.displayMetrics.density
            val qrSize = (240 * dp).toInt()
            val pad = (16 * dp).toInt()
            val layout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
            }
            qrToBitmap(qr, qrSize)?.let { bmp ->
                layout.addView(ImageView(requireContext()).apply {
                    setImageBitmap(bmp)
                    adjustViewBounds = true
                })
            }
            layout.addView(android.widget.TextView(requireContext()).apply {
                text = if (manual.isNotEmpty()) "Manual code:\n$manual" else qr
                setTextIsSelectable(true)
                setPadding(0, pad, 0, 0)
            })

            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Pair this device")
                .setView(layout)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), payloadJson, Toast.LENGTH_LONG).show()
        }
    }

    /** ZXing → Bitmap — the QR payload rendered as a scannable image. */
    private fun qrToBitmap(text: String, size: Int): Bitmap? {
        return try {
            val matrix: BitMatrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) for (y in 0 until size)
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            bmp
        } catch (e: Exception) {
            null
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
