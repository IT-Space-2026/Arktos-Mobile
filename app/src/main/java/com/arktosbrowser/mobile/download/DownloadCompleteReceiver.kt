package com.arktosbrowser.mobile.download

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import com.arktosbrowser.mobile.di.injector
import com.arktosbrowser.mobile.log.Logger
import java.io.File
import javax.inject.Inject

/**
 * Verifies finished downloads against the size the header probe recorded and
 * re-enqueues truncated or transiently-failed downloads (upstream
 * SmartCookieWeb #270 — the system manager can report "complete" on a
 * chunked response whose connection died mid-stream).
 */
class DownloadCompleteReceiver : BroadcastReceiver() {

    @Inject
    lateinit var metaStore: DownloadMetaStore

    @Inject
    lateinit var logger: Logger

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (downloadId < 0) return

        context.injector.inject(this)
        val meta = metaStore.load(downloadId) ?: return // not a verified download

        val downloadManager = context.getSystemService(DownloadManager::class.java) ?: run {
            metaStore.remove(downloadId)
            return
        }

        var status = -1
        var reason = -1
        var downloadedBytes = -1L
        var localPath: String? = null
        try {
            downloadManager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
                if (cursor == null || !cursor.moveToFirst()) {
                    metaStore.remove(downloadId)
                    return
                }
                status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                downloadedBytes = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                )
                localPath = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                    ?.let { runCatching { Uri.parse(it).path }.getOrNull() }
            }
        } catch (e: Exception) {
            logger.log(TAG, "Failed to query completed download $downloadId")
            return
        }

        when (DownloadIntegrity.evaluate(
            status == DownloadManager.STATUS_SUCCESSFUL, downloadedBytes, meta.expectedBytes
        )) {
            DownloadIntegrity.Verdict.COMPLETE -> metaStore.remove(downloadId)
            DownloadIntegrity.Verdict.TRUNCATED -> retry(context, downloadManager, downloadId, meta, localPath)
            DownloadIntegrity.Verdict.FAILED -> {
                if (DownloadIntegrity.isRetryableReason(reason)) {
                    retry(context, downloadManager, downloadId, meta, localPath)
                } else {
                    metaStore.remove(downloadId)
                }
            }
        }
    }

    private fun retry(
        context: Context,
        downloadManager: DownloadManager,
        downloadId: Long,
        meta: DownloadIntegrity.Meta,
        localPath: String?
    ) {
        metaStore.remove(downloadId)
        if (meta.attempts + 1 >= DownloadIntegrity.MAX_ATTEMPTS) {
            logger.log(TAG, "Download of ${meta.url} gave up after ${meta.attempts + 1} attempts")
            return
        }

        // The partial file left by the truncated run would collide with the
        // destination of the retry.
        localPath?.let { File(it).delete() }

        try {
            val request = DownloadManager.Request(Uri.parse(meta.url))
                .setAllowedNetworkTypes(
                    DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE
                )
                .setAllowedOverRoaming(false)
                .setTitle(meta.fileName)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, meta.fileName)
            meta.mimeType?.let { request.setMimeType(it) }
            meta.userAgent?.let { request.addRequestHeader("User-Agent", it) }
            CookieManager.getInstance().getCookie(meta.url)
                ?.let { request.addRequestHeader("Cookie", it) }

            val newId = downloadManager.enqueue(request)
            metaStore.save(newId, meta.retried())
            logger.log(TAG, "Re-enqueued truncated download of ${meta.url}")
        } catch (e: Exception) {
            logger.log(TAG, "Failed to re-enqueue download of ${meta.url}")
        }
    }

    private companion object {
        const val TAG = "DownloadCompleteReceiver"
    }
}
