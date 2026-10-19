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
import android.widget.Toast
import com.arktosbrowser.mobile.BuildConfig
import com.arktosbrowser.mobile.MainActivity
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.database.downloads.DownloadEntry
import com.arktosbrowser.mobile.database.downloads.DownloadsRepository
import com.arktosbrowser.mobile.di.DatabaseScheduler
import com.arktosbrowser.mobile.di.MainScheduler
import com.arktosbrowser.mobile.di.NetworkScheduler
import com.arktosbrowser.mobile.log.Logger
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.utils.ProxyUtils
import io.reactivex.Maybe
import io.reactivex.Scheduler
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadHandler @Inject constructor(
    private val downloadsRepository: DownloadsRepository,
    private val downloadManager: DownloadManager,
    private val downloadMetaStore: DownloadMetaStore,
    @param:DatabaseScheduler private val databaseScheduler: Scheduler,
    @param:NetworkScheduler private val networkScheduler: Scheduler,
    @param:MainScheduler private val mainScheduler: Scheduler,
    private val logger: Logger
) {

    fun onDownloadStart(
        context: Activity, manager: UserPreferences, url: String, userAgent: String,
        contentDisposition: String?, mimeType: String, contentSize: String,
        referer: String? = null
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

        onDownloadStartNoStream(context, manager, url, userAgent, contentDisposition, mimeType, contentSize, referer)
    }

    fun onDownloadStartNoStream(
        context: Activity, manager: UserPreferences, url: String, userAgent: String,
        contentDisposition: String?, mimeType: String, contentSize: String,
        referer: String? = null
    ) {
        logger.log(TAG, "DOWNLOAD: Trying to download from URL: $url")
        logger.log(TAG, "DOWNLOAD: Content disposition: $contentDisposition")
        logger.log(TAG, "DOWNLOAD: MimeType: $mimeType")
        logger.log(TAG, "DOWNLOAD: User agent: $userAgent")

        if (manager.useThirdPartyDownloaderApps) {
            // Show dialog listing third party downloaders
        } else {
            val cookies = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()

            // Probe the real response headers first: the WebView-supplied mime
            // type is often application/octet-stream and the URL may still be
            // an interstitial that redirects to the actual file.
            val proxy = ProxyUtils.connectionProxy(manager)
            Maybe.fromCallable { DownloadHttpProbe.probe(url, cookies, userAgent, referer, proxy) }
                .subscribeOn(networkScheduler)
                .observeOn(mainScheduler)
                .subscribe(
                    { probe ->
                        enqueueRequest(context, url, userAgent, cookies, referer,
                            contentDisposition, mimeType, contentSize, probe)
                    },
                    {
                        enqueueRequest(context, url, userAgent, cookies, referer,
                            contentDisposition, mimeType, contentSize, null)
                    },
                    {
                        enqueueRequest(context, url, userAgent, cookies, referer,
                            contentDisposition, mimeType, contentSize, null)
                    }
                )
        }
    }

    private fun enqueueRequest(
        context: Activity,
        originalUrl: String,
        userAgent: String,
        cookies: String?,
        referer: String?,
        contentDisposition: String?,
        mimeType: String,
        contentSize: String,
        probe: DownloadHttpProbe.Result?
    ) {
        val url = probe?.finalUrl ?: originalUrl
        val effectiveDisposition = probe?.contentDisposition ?: contentDisposition
        val effectiveMime = probe?.contentType ?: mimeType
        val fileName = DownloadNaming.resolveFileName(url, effectiveDisposition, effectiveMime)

        if (probe != null && probe.contentType == "text/html" &&
            probe.contentDisposition == null
        ) {
            // The link resolved to a page, not a file (upstream #303) — the
            // download still proceeds but it is named .html and the user is
            // told the server did not offer the file.
            logger.log(TAG, "DOWNLOAD: server returned a web page for $url")
            Toast.makeText(
                context,
                context.getString(R.string.download_server_returned_page),
                Toast.LENGTH_LONG
            ).show()
        }

        val request = DownloadManager.Request(Uri.parse(url))
            .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
            .setAllowedOverRoaming(false)
            .setTitle(fileName)
            .setDescription(contentSize)
            .setMimeType(effectiveMime)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

        // Cookies and the page's user agent have to travel with the request —
        // hosts that see a bare DownloadManager UA answer with a login or
        // interstitial HTML page (upstream #303).
        if (!cookies.isNullOrEmpty()) {
            request.addRequestHeader(COOKIE_REQUEST_HEADER, cookies)
        }
        if (userAgent.isNotBlank()) {
            request.addRequestHeader(USER_AGENT_REQUEST_HEADER, userAgent)
        }
        if (!referer.isNullOrBlank()) {
            request.addRequestHeader(REFERER_REQUEST_HEADER, referer)
        }

        val downloadId = try {
            downloadManager.enqueue(request)
        } catch (e: Exception) {
            logger.log(TAG, "DOWNLOAD: failed to enqueue $url")
            return
        }

        downloadMetaStore.save(
            downloadId,
            DownloadIntegrity.Meta(
                url = url,
                fileName = fileName,
                mimeType = effectiveMime,
                userAgent = userAgent,
                expectedBytes = probe?.contentLength ?: -1L
            )
        )
        downloadsRepository
            .addDownloadIfNotExists(DownloadEntry(url, fileName, contentSize))
            .subscribeOn(databaseScheduler)
            .subscribe()
    }

    companion object {
        private const val TAG = "DownloadHandler"
        private const val COOKIE_REQUEST_HEADER = "Cookie"
        private const val USER_AGENT_REQUEST_HEADER = "User-Agent"
        private const val REFERER_REQUEST_HEADER = "Referer"

        fun getFileNameFromURL(url: String?, contentDisposition: String?, mimeType: String?): String {
            return DownloadNaming.resolveFileName(url, contentDisposition, mimeType)
        }

        internal fun sanitizeFileName(name: String?): String =
            DownloadNaming.sanitizeFileName(name)
    }
}
