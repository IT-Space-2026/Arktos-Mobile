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
 */
fun Context.showSslDialog(sslCertificate: SslCertificate, sslState: SslState) {
    val by = sslCertificate.issuedBy
    val to = sslCertificate.issuedTo
    val issueDate = sslCertificate.validNotBeforeDate
    val expireDate = sslCertificate.validNotAfterDate
    val dateFormat = DateFormat.getDateFormat(applicationContext)

    val contentView = inflater.inflate(R.layout.dialog_ssl_info, null, false).apply {
        findViewById<TextView>(R.id.ssl_layout_issue_by).text = by.cName
        findViewById<TextView>(R.id.ssl_layout_issue_to).text = to.oName?.takeIf(String::isNotBlank)
            ?: to.cName
        findViewById<TextView>(R.id.ssl_layout_issue_date).text = dateFormat.format(issueDate)
        findViewById<TextView>(R.id.ssl_layout_expire_date).text = dateFormat.format(expireDate)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val cert = sslCertificate.x509Certificate
            findViewById<TextView>(R.id.ssl_layout_serial_number).text = cert?.sigAlgName
        }
        else{
            findViewById<TextView>(R.id.ssl_layout_serial_number).visibility = View.GONE
            findViewById<TextView>(R.id.algorithm).visibility = View.GONE
        }
    }

    val icon = createSslDrawableForState(sslState)

    val dialog = MaterialAlertDialogBuilder(this)
        .setIcon(icon)
        .setTitle(to.cName)
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
