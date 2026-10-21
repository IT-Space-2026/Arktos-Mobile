// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.gecko

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.MailTo
import android.net.Uri
import androidx.core.content.FileProvider
import com.arktosbrowser.mobile.BuildConfig
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.constant.FILE
import com.arktosbrowser.mobile.engine.Page
import com.arktosbrowser.mobile.extensions.snackbar
import com.arktosbrowser.mobile.log.Logger
import com.arktosbrowser.mobile.rust.ArktosCore
import com.arktosbrowser.mobile.utils.IntentUtils
import com.arktosbrowser.mobile.utils.Utils
import com.arktosbrowser.mobile.utils.isFileUrl
import com.arktosbrowser.mobile.utils.isSpecialUrl
import java.io.File
import java.net.URISyntaxException
import java.net.URLConnection

/**
 * ENG04 — the external-app handoff half of
 * `ArktosCookieWebClient.shouldOverrideLoading`, minus the WebView
 * types: mailto/tel/intent:// launches, the `gh4b://`/oauth scheme
 * passthrough, browsable `file:` opens and the specialized-handler
 * resolution in [IntentUtils.startActivityForUrl]. Returns true when
 * the URL was claimed by another app (the caller denies the
 * navigation).
 */
class GeckoExternalLauncher(
    private val activity: Activity,
    private val page: Page,
    private val logger: Logger
) {

    private val intentUtils = IntentUtils(activity)

    fun launch(url: String): Boolean {
        if (url.startsWith("mailto:")) {
            val mailTo = MailTo.parse(url)
            activity.startActivity(
                Utils.newEmailIntent(mailTo.to, mailTo.subject, mailTo.body, mailTo.cc)
            )
            return true
        }
        if (url.startsWith("tel:")) {
            activity.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(url)))
            return true
        }
        if (url.startsWith("intent://")) {
            // Resolution order lives in IntentUtils: pinned app, other
            // installed handler, browser_fallback_url, then the store
            // (upstream #316).
            return intentUtils.startActivityForUrl(page, url)
        }
        if (url.startsWith("gh4b://") || url.contains("://oauth")) {
            val intent = try {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            } catch (ignored: URISyntaxException) {
                null
            }
            if (intent != null) {
                intent.addCategory(Intent.CATEGORY_BROWSABLE)
                intent.component = null
                intent.selector = null
                try {
                    activity.startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    logger.log(TAG, "ActivityNotFoundException")
                }
                return true
            }
            return false
        }
        if (url.isFileUrl() && !url.isSpecialUrl()) {
            return launchFile(url)
        }
        // A plain web/custom-scheme URL: only claim it when an installed
        // app specifically handles the link (upstream #316).
        return intentUtils.startActivityForUrl(page, url)
    }

    private fun launchFile(url: String): Boolean {
        val file = File(url.replace(FILE, ""))
        if (file.exists()) {
            val mimeType = URLConnection.guessContentTypeFromName(file.name)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                setDataAndType(
                    FileProvider.getUriForFile(
                        activity, BuildConfig.APPLICATION_ID + ".fileprovider", file
                    ),
                    mimeType
                )
            }
            try {
                activity.startActivity(intent)
            } catch (e: Exception) {
                logger.log(TAG, "cannot open downloaded file $file")
            }
        } else {
            activity.snackbar(R.string.message_open_download_fail)
        }
        return true
    }

    companion object {
        private const val TAG = "GeckoExternalLauncher"
    }
}
