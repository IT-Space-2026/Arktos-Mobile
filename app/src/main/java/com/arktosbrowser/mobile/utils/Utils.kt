// SPDX-License-Identifier: MPL-2.0
// Copyright 2014 A.C.R. and Arktos contributors
package com.arktosbrowser.mobile.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.annotation.StringRes
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.database.HistoryEntry
import com.arktosbrowser.mobile.dialog.BrowserDialog.setDialogSize
import com.arktosbrowser.mobile.extensions.snackbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

object Utils {
    private const val TAG = "Utils"

    // PORT02 — getDomainName, isColorTooDark, mixTwoColors,
    // buildErrorPage, buildBlockPage and guessFileExtension moved to
    // the Rust core; call sites go through `ArktosCore`.

    /**
     * Creates a new intent that can launch the email
     * app with a subject, address, body, and cc. It
     * is used to handle mail:to links.
     *
     * @param address the address to send the email to.
     * @param subject the subject of the email.
     * @param body    the body of the email.
     * @param cc      extra addresses to CC.
     * @return a valid intent.
     */
    fun newEmailIntent(address: String, subject: String?,
                       body: String?, cc: String?): Intent =
        Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(address))
            putExtra(Intent.EXTRA_TEXT, body)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_CC, cc)
            type = "message/rfc822"
        }

    /**
     * Creates a dialog with only a title, message, and okay button.
     *
     * @param activity the activity needed to create a dialog.
     * @param title    the title of the dialog.
     * @param message  the message of the dialog.
     */
    fun createInformativeDialog(activity: Activity, @StringRes title: Int, @StringRes message: Int) {
        val alert = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(true)
            .setPositiveButton(R.string.action_ok, null)
            .show()
        setDialogSize(activity, alert)
    }

    /**
     * Converts Density Pixels (DP) to Pixels (PX).
     *
     * @param dp the number of density pixels to convert.
     * @return the number of pixels that the conversion generates.
     */
    @JvmStatic
    fun dpToPx(dp: Float): Int =
        (dp * Resources.getSystem().displayMetrics.density).roundToInt()

    @JvmStatic
    fun trimCache(context: Context) {
        context.cacheDir?.deleteRecursively()
    }

    @Throws(IOException::class)
    fun createImageFile(): File {
        val timeStamp = IMAGE_TIMESTAMP_FORMAT.format(LocalDateTime.now())
        val storageDir = Environment
                .getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        return File.createTempFile("JPEG_${timeStamp}_", ".jpg", storageDir)
    }

    /**
     * Quietly closes a closeable object like an InputStream or OutputStream without
     * throwing any errors or requiring you do do any checks.
     *
     * @param closeable the object to close
     */
    @JvmStatic
    fun close(closeable: Closeable?) {
        try {
            closeable?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Unable to close closeable", e)
        }
    }

    /**
     * Creates a shortcut on the homescreen using the
     * [HistoryEntry] information that opens the
     * browser. The icon, URL, and title are used in
     * the creation of the shortcut.
     *
     * @param activity the activity needed to create
     * the intent and show a snackbar message
     * @param historyEntry     the HistoryEntity to create the shortcut from
     */
    fun createShortcut(activity: Activity,
                       historyEntry: HistoryEntry,
                       favicon: Bitmap) {
        val shortcutIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(historyEntry.url)
            setPackage(activity.packageName)
        }
        val title = historyEntry.title.takeUnless { it.isNullOrEmpty() }
            ?: activity.getString(R.string.untitled)
        val shortcutManager = activity.getSystemService(ShortcutManager::class.java)
        if (shortcutManager?.isRequestPinShortcutSupported == true) {
            val pinShortcutInfo = ShortcutInfo.Builder(
                activity,
                "browser-shortcut-" + historyEntry.url.hashCode()
            )
                    .setIntent(shortcutIntent)
                    .setIcon(Icon.createWithBitmap(favicon))
                    .setShortLabel(title)
                    .build()
            shortcutManager.requestPinShortcut(pinShortcutInfo, null)
            activity.snackbar(R.string.message_added_to_homescreen)
        } else {
            activity.snackbar(R.string.shortcut_message_failed_to_add)
        }
    }

    private val IMAGE_TIMESTAMP_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    // MOD03 — dropped the dead helpers: `calculateInSampleSize` had no
    // callers, `deleteDir` folded into deleteRecursively(), and the
    // pre-API-26 INSTALL_SHORTCUT broadcast branch was unreachable
    // (minSdk is 26).
}
