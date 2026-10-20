package com.arktosbrowser.mobile.ssl

import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.extensions.dimen
import com.arktosbrowser.mobile.extensions.inflater
import com.arktosbrowser.mobile.extensions.resizeAndShow
import android.content.Context
import android.net.http.SslCertificate
import android.text.format.DateFormat
import android.view.View
import android.view.View.MeasureSpec
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Shows an informative dialog with the provided [SslCertificate] information.
 * [rank] is the host's merged Tranco+Majestic site rank (0 = unranked) and
 * [darkTheme] picks the legible half of the desktop's verdict palette —
 * both feed the desktop-parity trust row and icon tint.
 *
 * The desktop SitePanel's trust rows ride along: connection verdict,
 * cookie count, per-site content blocking and JavaScript state. When the
 * engine exposes no certificate object ([sslCertificate] == null — the
 * WebView's cert getter is flaky even on valid https), the cert rows hide
 * and the host/connection/trust rows carry the dialog.
 */
fun Context.showSslDialog(
    sslCertificate: SslCertificate?,
    sslState: SslState,
    host: String,
    trancoRank: Long,
    majesticRank: Long,
    darkTheme: Boolean,
    cookieCount: Int,
    blockingEnabled: Boolean,
    javaScriptEnabled: Boolean
) {
    val dateFormat = DateFormat.getDateFormat(applicationContext)
    // The verdict color follows the merged (best) rank — same number
    // the toolbar icon's tint uses.
    val mergedRank = listOf(trancoRank, majesticRank).filter { it > 0 }.minOrNull() ?: 0

    val contentView = inflater.inflate(R.layout.dialog_ssl_info, null, false).apply {
        val connectionText = findViewById<TextView>(R.id.ssl_layout_connection)
        connectionText.text = when {
            sslState is SslState.Invalid -> getString(R.string.connection_broken_cert)
            sslState is SslState.Valid -> getString(R.string.connection_secure_https)
            else -> getString(R.string.connection_not_secure_http)
        }
        connectionText.setTextColor(sslVerdictColor(sslState, mergedRank, darkTheme))

        if (sslCertificate != null) {
            findViewById<TextView>(R.id.ssl_layout_issue_by).text = sslCertificate.issuedBy.cName
            findViewById<TextView>(R.id.ssl_layout_issue_to).text =
                sslCertificate.issuedTo.oName?.takeIf(String::isNotBlank)
                    ?: sslCertificate.issuedTo.cName
            findViewById<TextView>(R.id.ssl_layout_issue_date).text =
                dateFormat.format(sslCertificate.validNotBeforeDate)
            findViewById<TextView>(R.id.ssl_layout_expire_date).text =
                dateFormat.format(sslCertificate.validNotAfterDate)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val cert = sslCertificate.x509Certificate
                findViewById<TextView>(R.id.ssl_layout_serial_number).text = cert?.sigAlgName
            } else {
                findViewById<TextView>(R.id.ssl_layout_serial_number).visibility = View.GONE
                findViewById<TextView>(R.id.algorithm).visibility = View.GONE
            }
        } else {
            listOf(
                R.id.ssl_label_issued_by, R.id.ssl_layout_issue_by,
                R.id.ssl_label_issued_to, R.id.ssl_layout_issue_to,
                R.id.ssl_label_issued_on, R.id.ssl_layout_issue_date,
                R.id.ssl_label_expires_on, R.id.ssl_layout_expire_date,
                R.id.algorithm, R.id.ssl_layout_serial_number
            ).forEach { findViewById<TextView>(it).visibility = View.GONE }
        }

        findViewById<TextView>(R.id.ssl_layout_rank_tranco).text =
            if (trancoRank > 0) "#${String.format("%,d", trancoRank)}"
            else getString(R.string.ssl_rank_unranked)
        findViewById<TextView>(R.id.ssl_layout_rank_majestic).text =
            if (majesticRank > 0) "#${String.format("%,d", majesticRank)}"
            else getString(R.string.ssl_rank_unranked)
        findViewById<TextView>(R.id.ssl_layout_cookies).text =
            getString(R.string.cookies_count, cookieCount)
        findViewById<TextView>(R.id.ssl_layout_blocking).text =
            getString(if (blockingEnabled) R.string.site_blocking_on else R.string.site_blocking_off)
        findViewById<TextView>(R.id.ssl_layout_javascript).text =
            getString(if (javaScriptEnabled) R.string.site_js_on else R.string.site_js_off)
    }

    val icon = createSslDrawableForState(sslState)?.mutate()?.apply {
        setColorFilter(sslVerdictColor(sslState, mergedRank, darkTheme), android.graphics.PorterDuff.Mode.SRC_IN)
    }

    val dialog = MaterialAlertDialogBuilder(this)
        .setIcon(icon)
        .setTitle(host.ifBlank { sslCertificate?.issuedTo?.cName ?: "" })
        .setView(contentView)
        .setPositiveButton(R.string.action_ok, null)
        .resizeAndShow()

    // Upstream #277: at maximum font/display scaling the cert info is taller
    // than the screen. The dialog window wraps its content, so the overflow is
    // clipped instead of scrolled. Measure the desired height and pin the
    // ScrollView to the space actually left by the title and button bar.
    val window = dialog.window ?: return
    val width = window.attributes.width
    if (width <= 0) return
    window.decorView.measure(
        MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
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

/**
 * The height the scrollable dialog content should occupy: capped so the
 * dialog chrome ([chromeHeight] — title, buttons, window insets) still fits
 * within [availableHeight], but never below [minHeight] so a scrollable
 * viewport always remains.
 */
internal fun boundedScrollHeight(
    contentHeight: Int,
    chromeHeight: Int,
    availableHeight: Int,
    minHeight: Int
): Int = minOf(contentHeight, (availableHeight - chromeHeight).coerceAtLeast(minHeight))
