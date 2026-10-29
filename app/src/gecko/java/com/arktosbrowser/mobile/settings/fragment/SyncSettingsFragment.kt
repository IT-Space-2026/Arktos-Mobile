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
                // The transport stays lazy until needed — an invite
                // mints the endpoint a peer dials, so it must be up.
                syncRepo.ensureTransport(dataRoot, context)
                val payload = ArktosCore.syncInviteBegin(dataRoot, "default")
                showInvite(payload)
                true
            }

        findPreference<androidx.preference.Preference>("pref_sync_join")
            ?.setOnPreferenceClickListener {
                syncRepo.ensureTransport(dataRoot, context)
                showJoinEntry()
                true
            }

        findPreference<androidx.preference.Preference>("pref_sync_passwords")?.apply {
            summary = passwordsSummary()
            setOnPreferenceClickListener {
                showPasswordsDialog()
                true
            }
        }

        findPreference<androidx.preference.Preference>("pref_sync_remote_tabs")
            ?.setOnPreferenceClickListener {
                showRemoteTabs()
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
                // First exchange now — don't wait out the 15-min window.
                com.arktosbrowser.mobile.sync.SyncWorker.runNow(requireContext())
            }
            .setNegativeButton(R.string.sync_fingerprint_reject) { _, _ ->
                ArktosCore.syncPairConfirm(dataRoot, pairId, false)
            }
            .show()
    }

    /**
     * Device management — the syncmeta adapter's per-scope view;
     * unpair goes through the real revoke op (membership cut +
     * group-key rotation, matching the desktop's semantics).
     */
    private fun showDeviceManager() {
        val devices = try {
            val doc = org.json.JSONObject(ArktosCore.syncDevices(dataRoot, "default"))
            val arr = doc.optJSONArray("devices") ?: org.json.JSONArray()
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } catch (e: Exception) { emptyList() }
        if (devices.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.sync_no_devices), Toast.LENGTH_SHORT).show()
            return
        }
        val names = devices.map { it.optString("device_id", "?") }.toTypedArray()
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
        val id = dev.optString("device_id", "?")
        val fp = dev.optString("fingerprint", "?")
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(id)
            .setMessage("${getString(R.string.sync_device_fingerprint)}:\n$fp")
            .setPositiveButton(R.string.sync_unpair) { _, _ ->
                ArktosCore.syncDeviceRevoke(dataRoot, "default", id)
                Toast.makeText(requireContext(), getString(R.string.sync_unpaired), Toast.LENGTH_SHORT).show()
                findPreference<androidx.preference.Preference>("pref_sync_device_list")?.summary =
                    membersSummary()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Vault state line — off / locked / unlocked from the status doc. */
    private fun passwordsSummary(): String {
        return try {
            val st = JSONObject(ArktosCore.syncPasswordsStatus(dataRoot, "default"))
            when {
                st.optBoolean("unlocked") -> getString(R.string.sync_passwords_unlocked)
                st.optBoolean("enabled_ever") -> getString(R.string.sync_passwords_locked)
                else -> getString(R.string.sync_passwords_off)
            }
        } catch (e: Exception) {
            getString(R.string.sync_passwords_off)
        }
    }

    /**
     * Enable doubles as unlock — the passphrase re-derives the vault
     * key and verifies it against the group's verifier op.  When
     * already unlocked the dialog offers the lock action instead.
     */
    private fun showPasswordsDialog() {
        val unlocked = try {
            JSONObject(ArktosCore.syncPasswordsStatus(dataRoot, "default")).optBoolean("unlocked")
        } catch (e: Exception) { false }

        if (unlocked) {
            android.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.sync_passwords_title)
                .setMessage(R.string.sync_passwords_unlocked)
                .setPositiveButton(R.string.sync_passwords_lock_action) { _, _ ->
                    val r = ArktosCore.syncPasswordsLock(dataRoot, "default")
                    toastResult(r)
                    refreshPasswordsSummary()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        val input = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.sync_passwords_passphrase_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.sync_passwords_enable_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val pass = input.text.toString()
                if (pass.isNotEmpty()) {
                    val r = ArktosCore.syncPasswordsEnable(dataRoot, "default", pass)
                    toastResult(r)
                    refreshPasswordsSummary()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshPasswordsSummary() {
        findPreference<androidx.preference.Preference>("pref_sync_passwords")?.summary =
            passwordsSummary()
    }

    /**
     * Remote tabs — the read-only presence feed: each peer device's
     * windows with their open tabs.
     */
    private fun showRemoteTabs() {
        val lines = try {
            val doc = JSONObject(ArktosCore.syncTabPeers(dataRoot, "default"))
            val peers = doc.optJSONArray("peers") ?: org.json.JSONArray()
            buildList {
                for (i in 0 until peers.length()) {
                    val p = peers.getJSONObject(i)
                    add("— ${p.optString("device_id", "?")} —")
                    val windows = p.optJSONArray("windows") ?: continue
                    for (w in 0 until windows.length()) {
                        val tabs = windows.getJSONObject(w).optJSONArray("tabs") ?: continue
                        for (t in 0 until tabs.length()) {
                            val tab = tabs.getJSONObject(t)
                            val title = tab.optString("title", "")
                            val url = tab.optString("url", "")
                            add(if (title.isNotEmpty()) "$title\n$url" else url)
                        }
                    }
                }
            }
        } catch (e: Exception) { emptyList() }

        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.sync_remote_tabs_title)
            .setMessage(
                if (lines.isEmpty()) getString(R.string.sync_remote_tabs_none)
                else lines.joinToString("\n")
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun toastResult(json: String) {
        val msg = try {
            val o = JSONObject(json)
            if (o.has("error")) o.getString("error") else getString(R.string.action_ok)
        } catch (e: Exception) { json }
        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
    }

    private fun membersSummary(): String {
        return try {
            val doc = org.json.JSONObject(ArktosCore.syncDevices(dataRoot, "default"))
            val arr = doc.optJSONArray("devices") ?: org.json.JSONArray()
            if (arr.length() == 0) getString(R.string.sync_no_devices)
            else (0 until arr.length()).joinToString("\n") {
                arr.getJSONObject(it).optString("device_id", "?")
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
