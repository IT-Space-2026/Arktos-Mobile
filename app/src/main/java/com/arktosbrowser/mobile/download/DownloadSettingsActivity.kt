// SPDX-License-Identifier: MPL-2.0
package com.arktosbrowser.mobile.download

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.arktosbrowser.mobile.R
import java.io.File

/**
 * Iceraven-style "Download Settings" screen:
 *  - File storage section showing the default location
 *  - Delete-policy radio group (device / history / ask)
 *  - "Manage downloads with another app" toggle that routes the
 *    browser's Downloads entry point to the system UI.
 */
class DownloadSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (resources.displayMetrics.density * 16).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val toolbar =
            com.google.android.material.appbar.MaterialToolbar(this).apply {
                title = getString(R.string.download_settings_title)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
        root.addView(toolbar)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(ScrollView(this).apply { addView(content) })

        fun section(text: String) = TextView(this).apply {
            this.text = text
            setPadding(pad, pad, pad, pad / 2)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            content.addView(this)
        }

        val radios = mutableListOf<RadioButton>()
        fun refreshRadios() = radios.forEach {
            it.isChecked =
                DownloadSettings.deletePolicy(this) == it.tag
        }

        fun radio(title: String, subtitle: String, policy: String) =
            RadioButton(this).apply {
                this.text = if (subtitle.isEmpty()) title else "$title\n$subtitle"
                textSize = 16f
                setPadding(pad, pad / 2, pad, pad / 2)
                isChecked = DownloadSettings.deletePolicy(this@DownloadSettingsActivity) == policy
                setOnClickListener {
                    DownloadSettings.setDeletePolicy(this@DownloadSettingsActivity, policy)
                    refreshRadios()
                }
                tag = policy
                content.addView(this)
            }

        section(getString(R.string.download_settings_storage))
        TextView(this).apply {
            val dir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            ).absolutePath.replace(File.separator + "", "/")
            text = getString(R.string.download_settings_location) +
                    "\n~" + dir.substring(dir.indexOf("/Download").coerceAtLeast(0))
            textSize = 16f
            setPadding(pad, 0, pad, pad)
            content.addView(this)
        }

        section(getString(R.string.download_settings_delete_header))
        radios += radio(
            getString(R.string.download_settings_delete_device),
            getString(R.string.download_settings_delete_device_sub),
            DownloadSettings.POLICY_DEVICE
        )
        radios += radio(
            getString(R.string.download_settings_delete_history),
            getString(R.string.download_settings_delete_history_sub),
            DownloadSettings.POLICY_HISTORY
        )
        radios += radio(
            getString(R.string.download_settings_delete_ask),
            "",
            DownloadSettings.POLICY_ASK
        )

        val divider = android.view.View(this).apply {
            setBackgroundColor(0x22000000)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1
            )
        }
        content.addView(divider)

        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, pad / 2, pad, pad / 2)
            gravity = android.view.Gravity.CENTER_VERTICAL
            val label = TextView(context).apply {
                text = getString(R.string.download_settings_external)
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            val toggle = MaterialSwitch(context).apply {
                isChecked = DownloadSettings.externalManager(context)
                setOnCheckedChangeListener { _, checked ->
                    DownloadSettings.setExternalManager(context, checked)
                }
            }
            addView(label); addView(toggle)
            content.addView(this)
        }

        setContentView(root)
        toolbar.setNavigationIcon(
            androidx.appcompat.R.drawable.abc_ic_ab_back_material
        )
        toolbar.setNavigationOnClickListener { finish() }
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(toolbar) { v, insets ->
                val bars = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.statusBars()
                )
                v.setPadding(v.paddingLeft, bars.top, v.paddingRight, v.paddingBottom)
                insets
            }
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.navigationBars()
            )
            v.setPadding(0, 0, 0, bars.bottom)
            insets
        }
    }

    companion object {
        /** System download-manager intent used when externalManager is on. */
        fun externalIntent(): Intent =
            Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
    }
}
