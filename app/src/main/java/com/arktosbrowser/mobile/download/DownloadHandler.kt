/*
 * Copyright 2014 A.C.R. Development
 */

package com.arktosbrowser.mobile.download

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import com.arktosbrowser.mobile.BuildConfig
import com.arktosbrowser.mobile.MainActivity
import com.arktosbrowser.mobile.database.downloads.DownloadsRepository
import com.arktosbrowser.mobile.di.DatabaseScheduler
import com.arktosbrowser.mobile.di.MainScheduler
import com.arktosbrowser.mobile.di.NetworkScheduler
import com.arktosbrowser.mobile.log.Logger
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.rust.ArktosCore
import io.reactivex.Scheduler
import java.io.File
import java.io.IOException
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadHandler @Inject constructor(
    private val downloadsRepository: DownloadsRepository,
    private val downloadManager: DownloadManager,
    @param:DatabaseScheduler private val databaseScheduler: Scheduler,
    @param:NetworkScheduler private val networkScheduler: Scheduler,
    @param:MainScheduler private val mainScheduler: Scheduler,
    private val logger: Logger
) {

    fun onDownloadStart(
        context: Activity, manager: UserPreferences, url: String, userAgent: String,
        contentDisposition: String?, mimeType: String, contentSize: String
    ) {
        logger.log(TAG, "DOWNLOAD: Trying to download from URL: $url")
        logger.log(TAG, "DOWNLOAD: Content disposition: $contentDisposition")
        logger.log(TAG, "DOWNLOAD: MimeType: $mimeType")
        logger.log(TAG, "DOWNLOAD: User agent: $userAgent")

        if (contentDisposition == null || !contentDisposition.regionMatches(0, "attachment", 0, 10, ignoreCase = true)) {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(Uri.parse(url), mimeType)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.component = null
            intent.selector = null
            val info = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            if (info != null) {
                if (BuildConfig.APPLICATION_ID == info.activityInfo.packageName || MainActivity::class.java.name == info.activityInfo.name) {
                    try {
                        context.startActivity(intent)
                        return
                    } catch (ex: ActivityNotFoundException) {
                    }
                }
            }
        }

        onDownloadStartNoStream(context, manager, url, userAgent, contentDisposition, mimeType, contentSize)
    }

    fun onDownloadStartNoStream(
        context: Activity, manager: UserPreferences, url: String, userAgent: String,
        contentDisposition: String?, mimeType: String, contentSize: String
    ) {
        logger.log(TAG, "DOWNLOAD: Trying to download from URL: $url")
        logger.log(TAG, "DOWNLOAD: Content disposition: $contentDisposition")
        logger.log(TAG, "DOWNLOAD: MimeType: $mimeType")
        logger.log(TAG, "DOWNLOAD: User agent: $userAgent")

        if (manager.useThirdPartyDownloaderApps) {
            // Show dialog listing third party downloaders
        } else {
            val filename = sanitizeFileName(ArktosCore.guessFileName(url, contentDisposition, mimeType))
            downloadManager.enqueue(
                DownloadManager.Request(Uri.parse(url))
                    .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
                    .setAllowedOverRoaming(false)
                    .setTitle(filename)
                    .setDescription(contentSize)
                    .setMimeType(mimeType)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                    .addRequestHeader(COOKIE_REQUEST_HEADER, CookieManager.getInstance().getCookie(url))
            )
        }
    }

    companion object {
        private const val TAG = "DownloadHandler"
        private const val COOKIE_REQUEST_HEADER = "Cookie"

        fun getFileNameFromURL(url: String?, contentDisposition: String?, mimeType: String?): String {
            return ArktosCore.downloadFileName(url.orEmpty(), contentDisposition, mimeType)
        }

        internal fun sanitizeFileName(name: String?): String {
            return ArktosCore.sanitizeFileName(name)
        }

        private fun isWriteAccessAvailable(fileUri: Uri): Boolean {
            if (fileUri.path == null) {
                return false
            }
            val file = File(fileUri.path)
            return if (!file.isDirectory && !file.mkdirs()) {
                false
            } else try {
                if (file.createNewFile()) {
                    file.delete()
                }
                true
            } catch (ignored: IOException) {
                false
            }
        }

        private fun encodePath(path: String): String {
            val chars = path.toCharArray()
            var needed = false
            for (c in chars) {
                if (c == '[' || c == ']' || c == '|') {
                    needed = true
                    break
                }
            }
            if (!needed) {
                return path
            }
            val sb = StringBuilder()
            for (c in chars) {
                if (c == '[' || c == ']' || c == '|') {
                    sb.append('%')
                    sb.append(Integer.toHexString(c.code))
                } else {
                    sb.append(c)
                }
            }
            return sb.toString()
        }
    }
}