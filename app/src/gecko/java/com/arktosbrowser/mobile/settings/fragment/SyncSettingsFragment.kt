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
import com.arktosbrowser.mobile.di.injector
import com.arktosbrowser.mobile.rust.ArktosCore
import com.arktosbrowser.mobile.sync.SyncRepository
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

    private lateinit var syncRepo: SyncRepository

    private fun drainStore(storeName: String) {
        if (::syncRepo.isInitialized) runCatching { syncRepo.drainPending(dataRoot, storeName) }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preference_sync, rootKey)
        syncRepo = com.arktosbrowser.mobile.sync.SyncRepository(injector.arktosStore())

        findPreference<androidx.preference.Preference>("pref_sync_device_name")?.summary =
            android.os.Build.MODEL

        val status = try {
            val st = JSONObject(ArktosCore.syncStats(dataRoot))
            "Ops: ${st.optLong("ops", 0)} · Members: ${st.optLong("members", 0)}" +
                if (ArktosCore.syncTransportRunning(dataRoot)) " · Transport up" else " · Transport down"
        } catch (e: Exception) {
            getString(R.string.sync_status_not_connected)
        }
        findPreference<androidx.preference.Preference>("pref_sync_status")?.title = status

        findPreference<androidx.preference.Preference>("pref_sync_device_list")?.summary =
            membersSummary()

        findPreference<androidx.preference.Preference>("pref_sync_device_list")
            ?.setOnPreferenceClickListener {
                showDeviceManager()
                true
            }

        listOf("bookmarks", "history").forEach { drainStore(it) }

        findPreference<androidx.preference.Preference>("pref_sync_pair")
            ?.setOnPreferenceClickListener {
                val payload = ArktosCore.syncInviteBegin(dataRoot, "bookmarks")
                showInvite(payload)
                true
            }

        findPreference<androidx.preference.Preference>("pref_sync_join")
            ?.setOnPreferenceClickListener {
                showJoinEntry()
                true
            }
    }

    /** Join lane — paste the peer's manual code (or scanned QR text). */
    private fun showJoinEntry() {
        val input = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.sync_join_hint)
        }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.sync_join_button)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    val resp = ArktosCore.syncJoinBegin(dataRoot, text)
                    startPairPolling(resp)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Drive a pair session's handshake — poll its status JSON;
     * when the fingerprint lands, show the confirm dialog.
     */
    private fun startPairPolling(joinResp: String) {
        val id = try { JSONObject(joinResp).optLong("session", -1) } catch (e: Exception) { -1L }
        val uid = id.toULong()
        if (id < 0) {
            Toast.makeText(requireContext(), joinResp, Toast.LENGTH_LONG).show()
            return
        }
        pollPair(uid)
    }

    private fun pollPair(pairId: ULong, attempts: Int = 0) {
        if (attempts > 30) return // give up after ~90s
        handler.postDelayed({
            try {
                val st = JSONObject(ArktosCore.syncPairPoll(dataRoot, pairId))
                val phase = st.optString("phase", "")
                val fp = st.optString("fingerprint", st.optString("fp", ""))
                if (fp.isNotEmpty()) {
                    showFingerprintConfirm(pairId, fp)
                } else if (phase == "done" || phase == "failed" || phase == "cancelled") {
                    Toast.makeText(requireContext(), "Pairing $phase", Toast.LENGTH_LONG).show()
                } else {
                    pollPair(pairId, attempts + 1)
                }
            } catch (e: Exception) {
                pollPair(pairId, attempts + 1)
            }
        }, if (attempts == 0) 0 else 3000)
    }

    /** The fingerprint check — the human-verification lane of the handshake. */
    private fun showFingerprintConfirm(pairId: ULong, fingerprint: String) {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.sync_fingerprint_title)
            .setMessage(fingerprint)
            .setPositiveButton(R.string.sync_fingerprint_confirm) { _, _ ->
                ArktosCore.syncPairConfirm(dataRoot, pairId, true)
                findPreference<androidx.preference.Preference>("pref_sync_device_list")?.summary =
                    membersSummary()
            }
            .setNegativeButton(R.string.sync_fingerprint_reject) { _, _ ->
                ArktosCore.syncPairConfirm(dataRoot, pairId, false)
            }
            .show()
    }

    /**
     * MSYNC08 — device management: list each paired device with its
     * fingerprint and an unpair action backed by syncMemberRemove.
     */
    private fun showDeviceManager() {
        val devices = try {
            val arr = org.json.JSONArray(ArktosCore.syncMembers(dataRoot))
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } catch (e: Exception) { emptyList() }
        if (devices.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.sync_no_devices), Toast.LENGTH_SHORT).show()
            return
        }
        val names = devices.map { it.optString("id", "?") }.toTypedArray()
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.sync_paired_devices)
            .setItems(names) { _, which ->
                val dev = devices[which]
                showDeviceDetail(dev)
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    private fun showDeviceDetail(dev: JSONObject) {
        val id = dev.optString("id", "?")
        val fp = try {
            JSONObject(ArktosCore.syncFingerprint(dev.optString("pubkey", ""))).toString()
        } catch (e: Exception) { dev.optString("pubkey", "") }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(id)
            .setMessage("${getString(R.string.sync_device_fingerprint)}:\n$fp")
            .setPositiveButton(R.string.sync_unpair) { _, _ ->
                ArktosCore.syncMemberRemove(dataRoot, id)
                Toast.makeText(requireContext(), getString(R.string.sync_unpaired), Toast.LENGTH_SHORT).show()
                findPreference<androidx.preference.Preference>("pref_sync_device_list")?.summary =
                    membersSummary()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
