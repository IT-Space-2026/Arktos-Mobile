package com.arktosbrowser.mobile.download

import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists [DownloadIntegrity.Meta] between a download's enqueue and its
 * ACTION_DOWNLOAD_COMPLETE broadcast, keyed by the system download id.
 * The broadcast may arrive after a process restart, so it must live on disk.
 */
@Singleton
class DownloadMetaStore @Inject constructor(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, 0)

    fun save(downloadId: Long, meta: DownloadIntegrity.Meta) {
        prefs.edit().putString(key(downloadId), meta.encode()).apply()
    }

    fun load(downloadId: Long): DownloadIntegrity.Meta? =
        DownloadIntegrity.Meta.decode(prefs.getString(key(downloadId), null))

    fun remove(downloadId: Long) {
        prefs.edit().remove(key(downloadId)).apply()
    }

    private fun key(downloadId: Long) = "dl_$downloadId"

    private companion object {
        const val PREFS_NAME = "download_integrity"
    }
}
