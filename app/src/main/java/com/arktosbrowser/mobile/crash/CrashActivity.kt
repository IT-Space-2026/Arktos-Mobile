// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.crash

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Last-resort crash surface — shown by the default uncaught-exception
 * handler on launch-path failures where the app dies before any real
 * UI exists. Runs in its own `:crash` process so it survives the main
 * process teardown.
 */
class CrashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val trace = intent.getStringExtra(EXTRA_TRACE).orEmpty()
        val text = TextView(this).apply {
            setTextIsSelectable(true)
            text = "Arktos crashed during startup.\n\n$trace"
            setPadding(48, 48, 48, 48)
        }
        val copy = Button(this).apply {
            this.text = "Copy report"
            setOnClickListener {
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("crash", trace))
                Toast.makeText(this@CrashActivity, "Copied", Toast.LENGTH_SHORT).show()
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(copy, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(ScrollView(this@CrashActivity).apply { addView(text) },
                LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        })
    }

    companion object {
        const val EXTRA_TRACE = "trace"
    }
}
