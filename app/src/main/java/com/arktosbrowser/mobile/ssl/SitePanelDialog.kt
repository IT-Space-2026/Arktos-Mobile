// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.ssl

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.dialog.BrowserDialog
import com.arktosbrowser.mobile.extensions.dimen
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * SHLD01 mobile: the site-shield panel — the desktop SitePanel's
 * subset that maps to mobile infra. Host, connection verdict and the
 * Tranco/Majestic ranks are read-only rows; the switches write through
 * [Callbacks] immediately and the caller reloads the page so every
 * change takes effect while the panel stays open.
 */
object SitePanelDialog {

    /** Everything the panel needs, re-polled after each write. */
    class State(
        val host: String,
        val connection: String,
        val verdictRank: Long,
        val trancoRank: Long,
        val majesticRank: Long,
        val blockedThisPage: Int,
        val siteBlocking: Boolean,
        val globalBlocking: Boolean,
        val javaScript: Boolean,
        val cookies: Boolean,
        val cookieCount: Int,
        val sslState: SslState
    )

    class Callbacks(
        val poll: () -> State,
        val toggleSiteBlocking: () -> Unit,
        val toggleGlobalBlocking: () -> Unit,
        val toggleJavaScript: () -> Unit,
        val toggleCookies: () -> Unit,
        val clearSiteData: () -> Unit,
        val sslCertificateDialog: () -> Unit
    )

    fun Context.showSitePanel(darkTheme: Boolean, callbacks: Callbacks) {
        val inflater = LayoutInflater.from(this)
        val contentView = inflater.inflate(R.layout.dialog_site_panel, null, false)

        val connectionText = contentView.findViewById<TextView>(R.id.panel_connection)
        val trancoText = contentView.findViewById<TextView>(R.id.panel_rank_tranco)
        val majesticText = contentView.findViewById<TextView>(R.id.panel_rank_majestic)
        val blockedText = contentView.findViewById<TextView>(R.id.panel_blocked_count)
        val cookieCountText = contentView.findViewById<TextView>(R.id.panel_cookie_count)
        val blockSiteSwitch = contentView.findViewById<SwitchCompat>(R.id.panel_block_site)
        val blockGlobalSwitch = contentView.findViewById<SwitchCompat>(R.id.panel_block_global)
        val javaScriptSwitch = contentView.findViewById<SwitchCompat>(R.id.panel_javascript)
        val cookiesSwitch = contentView.findViewById<SwitchCompat>(R.id.panel_cookies)

        fun refresh() {
            val s = callbacks.poll()
            connectionText.text = s.connection
            connectionText.setTextColor(sslVerdictColor(s.sslState, s.verdictRank, darkTheme))
            trancoText.text = if (s.trancoRank > 0) "#${String.format("%,d", s.trancoRank)}"
                else getString(R.string.ssl_rank_unranked)
            majesticText.text = if (s.majesticRank > 0) "#${String.format("%,d", s.majesticRank)}"
                else getString(R.string.ssl_rank_unranked)
            blockedText.text = getString(R.string.shield_blocked_count, s.blockedThisPage)
            cookieCountText.text = getString(R.string.cookies_count, s.cookieCount)
            blockSiteSwitch.isChecked = s.siteBlocking
            blockGlobalSwitch.isChecked = s.globalBlocking
            javaScriptSwitch.isChecked = s.javaScript
            cookiesSwitch.isChecked = s.cookies
        }

        refresh()

        blockSiteSwitch.setOnCheckedChangeListener { _, _ ->
            callbacks.toggleSiteBlocking(); refresh()
        }
        blockGlobalSwitch.setOnCheckedChangeListener { _, _ ->
            callbacks.toggleGlobalBlocking(); refresh()
        }
        javaScriptSwitch.setOnCheckedChangeListener { _, _ ->
            callbacks.toggleJavaScript(); refresh()
        }
        cookiesSwitch.setOnCheckedChangeListener { _, _ ->
            callbacks.toggleCookies(); refresh()
        }
        contentView.findViewById<Button>(R.id.panel_clear_data).setOnClickListener {
            callbacks.clearSiteData(); refresh()
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(callbacks.poll().host)
            .setView(contentView)
            .setNeutralButton(R.string.shield_view_certificate) { _, _ -> callbacks.sslCertificateDialog() }
            .setPositiveButton(R.string.action_ok, null)
            .create()
        dialog.show()
        BrowserDialog.setDialogSize(this, dialog)

        // Same overflow fix as the SSL dialog (upstream #277): the
        // AlertDialog custom panel isn't scrollable, so a panel taller
        // than the window is clipped with no way to scroll. Pin the
        // ScrollView to the space left by the dialog chrome.
        val window = dialog.window ?: return
        val width = window.attributes.width
        if (width <= 0) return
        window.decorView.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val boundedHeight = boundedScrollHeight(
            contentHeight = contentView.measuredHeight,
            chromeHeight = window.decorView.measuredHeight - contentView.measuredHeight,
            availableHeight = resources.displayMetrics.heightPixels - 2 * dimen(R.dimen.dialog_padding),
            minHeight = dimen(R.dimen.ssl_dialog_min_content_height)
        )
        if (boundedHeight < contentView.measuredHeight) {
            contentView.layoutParams.height = boundedHeight
            contentView.requestLayout()
        }
    }
}
