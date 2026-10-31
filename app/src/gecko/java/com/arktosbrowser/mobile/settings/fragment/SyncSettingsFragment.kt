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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.util.concurrent.Executors
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

    /** Blocking Rust calls (transport bring-up = a full Tor bootstrap,
     *  join = a link dial + send) must never run on the main thread —
     *  they ANR'd the whole app from the Pair/Join taps. */
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SyncIo").apply { isDaemon = true }
    }

    /** The open join dialog — the scan re-open replaces, not stacks. */
    private var joinDialog: androidx.appcompat.app.AlertDialog? = null

    /** QR scan result — fill the field so the user reviews the code
     *  and taps Join deliberately, same as a paste. */
    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let {
            joinDialog?.dismiss()
            if (isAdded) showJoinEntry(it)
        }
    }

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
            "Changes: ${st.optLong("ops", 0)} · Members: ${st.optLong("members", 0)}" +
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
                // Bring-up blocks through Tor bootstrap — off the UI.
                io.execute {
                    runCatching {
                        syncRepo.ensureTransport(dataRoot, context)
                        val payload = ArktosCore.syncInviteBegin(dataRoot, "default")
                        handler.post { if (isAdded) showInvite(payload) }
                    }.onFailure { e -> handler.post { toastError(e) } }
                }
                true
            }

        findPreference<androidx.preference.Preference>("pref_sync_join")
            ?.setOnPreferenceClickListener {
                // Instant response — the transport bring-up moved into
                // joinWithText so the entry dialog opens immediately.
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

        findPreference<androidx.preference.Preference>("pref_sync_join_status")?.apply {
            isVisible = false
            setOnPreferenceClickListener {
                // Copy the terminal message — the summary holds it.
                val cm = requireContext().getSystemService(
                    android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText(
                    "join result", summary?.toString() ?: ""))
                Toast.makeText(requireContext(), "Copied", Toast.LENGTH_SHORT).show()
                true
            }
        }

        findPreference<androidx.preference.ListPreference>("pref_sync_interval")?.apply {
            summary = entry
            setOnPreferenceChangeListener { pref, newValue ->
                val lp = pref as androidx.preference.ListPreference
                lp.summary = lp.entries?.getOrNull(
                    lp.entryValues?.indexOf(newValue) ?: -1)
                context?.applicationContext?.let {
                    com.arktosbrowser.mobile.sync.SyncRepository.armInAppSync(it)
                }
                true
            }
        }

        findPreference<androidx.preference.Preference>("pref_sync_now")
            ?.setOnPreferenceClickListener {
                context?.applicationContext?.let {
                    com.arktosbrowser.mobile.sync.SyncWorker.runNow(it)
                    Toast.makeText(context, R.string.sync_now_started,
                        Toast.LENGTH_SHORT).show()
                }
                true
            }

        // A pair ceremony outlives this screen — resume polling any
        // in-flight session so the fingerprint dialog isn't orphaned
        // by a back-out-and-in.
        io.execute {
            runCatching {
                val sessions = JSONObject(ArktosCore.syncPairPoll(dataRoot, 0u))
                    .optJSONArray("sessions") ?: return@runCatching
                val live = setOf("waiting", "handshaking", "fingerprint", "committing")
                for (i in 0 until sessions.length()) {
                    val s = sessions.getJSONObject(i)
                    val id = s.optLong("pair_id", -1)
                    if (id >= 0 && s.optString("state") in live) {
                        val uid = id.toULong()
                        handler.post { if (isAdded) pollPair(uid) }
                    }
                }
            }
        }
    }

    /** Join lane — scan the peer's QR or paste the manual code. */
    private fun showJoinEntry(prefill: String? = null) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_join_device, null)
        val input = dialogView.findViewById<
            com.google.android.material.textfield.TextInputEditText>(R.id.join_code_input)
        prefill?.let { input.setText(it) }
        dialogView.findViewById<android.view.View>(R.id.join_scan_button)
            .setOnClickListener {
                scanLauncher.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setBeepEnabled(false)
                        .setOrientationLocked(true)
                        .setCaptureActivity(
                            com.arktosbrowser.mobile.sync.QrScanActivity::class.java
                        )
                )
            }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.sync_join_button)
            .setView(dialogView)
            .setPositiveButton(R.string.sync_join_button) { _, _ ->
                input.text.toString().trim().takeIf { it.isNotEmpty() }
                    ?.let(::joinWithText)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .also { joinDialog = it }
    }

    /** The blocking dial+handshake — transport bring-up happens here
     *  too (idempotent), so the entry dialog can open instantly. A
     *  bad code or a dead peer surfaces as a toast, not a crash. */
    private fun joinWithText(text: String) {
        // Immediate ack — the bootstrap+dial takes seconds, give the
        // tap visible feedback instead of silence.
        Toast.makeText(context?.applicationContext ?: return,
            R.string.sync_joining, Toast.LENGTH_SHORT).show()
        joinStatus("Contacting peer…", true)
        io.execute {
            val ctx = context?.applicationContext ?: return@execute
            com.arktosbrowser.mobile.crash.Diag.sync(ctx, "join.start len=${text.length}")
            runCatching {
                syncRepo.ensureTransport(dataRoot, context)
                com.arktosbrowser.mobile.crash.Diag.sync(ctx,
                    "join.transport up=${ArktosCore.syncTransportRunning(dataRoot)} " +
                        "dbg=${ArktosCore.syncTransportDebug(dataRoot).take(220)}")
                val resp = ArktosCore.syncJoinBegin(dataRoot, text)
                com.arktosbrowser.mobile.crash.Diag.sync(ctx,
                    "join.began ${resp.take(80)} " +
                        "dbg=${ArktosCore.syncTransportDebug(dataRoot).take(220)}")
                handler.post { if (isAdded) startPairPolling(resp) }
            }.onFailure { e ->
                com.arktosbrowser.mobile.crash.Diag.sync(ctx,
                    "join.error ${e.javaClass.simpleName} ${e.message} " +
                        "dbg=${ArktosCore.syncTransportDebug(dataRoot).take(220)}")
                handler.post {
                    joinStatus(readableJoinError(e), false)
                    toastError(e)
                }
            }
        }
    }

    /** The status row under "Join a device": spinner while in
     *  flight, then a readable terminal message (tap to copy). */
    private fun joinStatus(msg: String, inFlight: Boolean) {
        handler.post {
            if (!isAdded) return@post
            findPreference<androidx.preference.Preference>("pref_sync_join_status")?.apply {
                isVisible = true
                summary = msg
                setWidgetLayoutResource(if (inFlight) R.layout.sync_join_progress else 0)
            }
        }
    }

    /** Uniffi error text → a message the user can act on. */
    private fun readableJoinError(e: Throwable): String =
        readableJoinErrorText(e.message ?: return "Pairing failed")

    private fun readableJoinErrorText(m: String): String {
        return when {
            "connect deadline" in m -> "Peer unreachable — keep the other " +
                "device's pairing screen open and try again"
            "tor leg is not running" in m -> "Tor is still starting — try again in a moment"
            "invite" in m || "parse" in m -> "That code doesn't look like a pairing invite"
            else -> "Pairing failed: ${m.take(160)}"
        }
    }

    /** App-context toasts — a join/pair error must still surface when
     *  the fragment detached behind the camera or a back press. */
    private fun toastError(e: Throwable) {
        val ctx = context?.applicationContext ?: return
        Toast.makeText(ctx, e.message ?: "Sync failed", Toast.LENGTH_LONG).show()
    }

    /**
     * Drive a pair session's handshake — poll its status JSON;
     * when the fingerprint lands, show the confirm dialog.
     */
    /** The status_json fingerprint is {"words":[],"emoji":[]} —
     *  flatten it to a display string; plain-string forms pass too. */
    private fun fingerprintText(st: JSONObject): String {
        st.optJSONObject("fingerprint")?.let { obj ->
            val words = obj.optJSONArray("words")
            val emoji = obj.optJSONArray("emoji")
            return buildString {
                words?.let { for (i in 0 until it.length()) append(it.getString(i)).append(' ') }
                emoji?.let { for (i in 0 until it.length()) append(it.getString(i)).append(' ') }
            }.trim()
        }
        return st.optString("fingerprint", st.optString("fp", ""))
    }

    private fun startPairPolling(joinResp: String) {
        val id = try { JSONObject(joinResp).optLong("session", -1) } catch (e: Exception) { -1L }
        val uid = id.toULong()
        if (id < 0) {
            // Error JSON, not a session — surface it in the row too.
            val err = JSONObject(joinResp).optString("error", joinResp)
            joinStatus(readableJoinErrorText(err), false)
            Toast.makeText(requireContext(), err, Toast.LENGTH_LONG).show()
            return
        }
        pollPair(uid)
    }

    private fun pollPair(pairId: ULong, attempts: Int = 0) {
        // A first-time join can wait out a cold Tor bootstrap plus the
        // handshake itself — ~3 minutes of patient polling.
        if (attempts > 60) return
        handler.postDelayed({
            if (!isAdded) return@postDelayed
            // The uniffi call drives the transport event pump — off
            // the main thread, or every 3s tick stalls the UI.
            io.execute {
                val st = runCatching {
                    JSONObject(ArktosCore.syncPairPoll(dataRoot, pairId))
                }.getOrNull()
                handler.post {
                    if (!isAdded) return@post
                    if (st == null) {
                        pollPair(pairId, attempts + 1)
                        return@post
                    }
                    // status_json emits "state" (waiting/handshaking/
                    // fingerprint/committing/done/failed/cancelled)
                    // and fingerprint as {"words":[],"emoji":[]}.
                    val phase = st.optString("state", st.optString("phase", ""))
                    val fp = fingerprintText(st)
                    val err = st.optString("error", "")
                    context?.applicationContext?.let {
                        com.arktosbrowser.mobile.crash.Diag.sync(it,
                            "poll.$attempts state=$phase fp=${fp.isNotEmpty()} err=$err")
                    }
                    when {
                        fp.isNotEmpty() || phase == "fingerprint" -> {
                            joinStatus("Confirm the fingerprint on both devices", true)
                            showFingerprintConfirm(pairId, fp.ifEmpty { "—" })
                        }
                        phase == "done" -> {
                            joinStatus(getString(R.string.sync_pair_done), false)
                            Toast.makeText(requireContext(), R.string.sync_pair_done, Toast.LENGTH_LONG).show()
                            findPreference<androidx.preference.Preference>("pref_sync_device_list")
                                ?.summary = membersSummary()
                        }
                        phase == "failed" || phase == "cancelled" || phase == "gone" -> {
                            val msg = "Pairing $phase${if (err.isNotEmpty()) ": $err" else ""}"
                            joinStatus(msg, false)
                            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                        }
                        else -> pollPair(pairId, attempts + 1)
                    }
                }
            }
        }, if (attempts == 0) 0 else 3000)
    }

    /** The fingerprint check — the human-verification lane of the handshake. */
    private fun showFingerprintConfirm(pairId: ULong, fingerprint: String) {
        MaterialAlertDialogBuilder(requireContext())
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
        MaterialAlertDialogBuilder(requireContext())
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
        MaterialAlertDialogBuilder(requireContext())
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
            MaterialAlertDialogBuilder(requireContext())
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
        MaterialAlertDialogBuilder(requireContext())
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
        // label → url pairs; device headers carry no url and are
        // inert in the picker.
        val rows = try {
            val doc = JSONObject(ArktosCore.syncTabPeers(dataRoot, "default"))
            val peers = doc.optJSONArray("peers") ?: org.json.JSONArray()
            buildList<Pair<String, String?>> {
                for (i in 0 until peers.length()) {
                    val p = peers.getJSONObject(i)
                    add("— ${p.optString("device_id", "?")} —" to null)
                    val windows = p.optJSONArray("windows") ?: continue
                    for (w in 0 until windows.length()) {
                        val tabs = windows.getJSONObject(w).optJSONArray("tabs") ?: continue
                        for (t in 0 until tabs.length()) {
                            val tab = tabs.getJSONObject(t)
                            val title = tab.optString("title", "")
                            val url = tab.optString("url", "")
                            if (url.isNotEmpty()) {
                                val label = if (title.isNotEmpty()) "$title\n$url" else url
                                add(label to url)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) { emptyList() }

        if (rows.isEmpty()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.sync_remote_tabs_title)
                .setMessage(R.string.sync_remote_tabs_none)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.sync_remote_tabs_title)
            .setItems(rows.map { it.first }.toTypedArray()) { _, which ->
                rows[which].second?.let { url ->
                    val i = android.content.Intent(android.content.Intent.ACTION_VIEW)
                    i.data = android.net.Uri.parse(url)
                    startActivity(i, null)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
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

            MaterialAlertDialogBuilder(requireContext())
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

    // The multicast lock is screen-scoped: held while pairing/
    // discovery is actually possible, released on leave — a held
    // lock wakes the CPU on every multicast packet on the LAN.
    override fun onResume() {
        super.onResume()
        if (::syncRepo.isInitialized) syncRepo.holdMulticast(context)
    }

    override fun onPause() {
        if (::syncRepo.isInitialized) syncRepo.dropMulticast()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
