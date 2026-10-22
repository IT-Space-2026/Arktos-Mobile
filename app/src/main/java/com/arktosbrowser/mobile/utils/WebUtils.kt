package com.arktosbrowser.mobile.utils

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.arktosbrowser.mobile.database.history.HistoryRepository
import com.arktosbrowser.mobile.engine.Engines
import com.arktosbrowser.mobile.engine.Page
import com.arktosbrowser.mobile.engine.StorageArea
import io.reactivex.rxjava3.core.Scheduler
import java.io.File

/**
 * Copyright 8/4/2015 Anthony Restaino
 *
 * Engine-facing storage helpers. The cookie/DOM-storage/database
 * calls delegate to the active engine's default [Profile] so no
 * platform webview type crosses this file's boundary.
 */
object WebUtils {

    fun clearCookies(context: Context) {
        Engines.defaultProfile.clear(setOf(StorageArea.Cookies))
    }

    fun clearWebStorage() {
        Engines.defaultProfile.clear(setOf(StorageArea.LocalStorage, StorageArea.IndexedDb))
    }

    /**
     * Wipes one origin's site data — the per-site scope of
     * [eraseWebStorage].
     */
    fun clearSiteData(url: String) {
        Engines.defaultProfile.clearSiteData(url)
    }

    fun eraseWebStorage(context: Context) {
        // SitePermissions ride the web-storage wipe — "clear web
        // storage" is the umbrella users expect to cover site grants.
        Engines.defaultProfile.clear(setOf(
            StorageArea.LocalStorage, StorageArea.IndexedDb,
            StorageArea.SitePermissions
        ))

        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N) {
            deleteData(context)
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun deleteData(context: Context) {
        try {
            val dir = File(context.dataDir.toString() + "/app_webview")
            deleteDir(dir)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun clearHistory(
        context: Context,
        historyRepository: HistoryRepository,
        databaseScheduler: Scheduler
    ) {
        historyRepository.deleteHistory()
            .subscribeOn(databaseScheduler)
            .subscribe()
        Engines.defaultProfile.clear(setOf(StorageArea.FormData, StorageArea.HttpAuth))
        Utils.trimCache(context)
    }

    fun clearCache(page: Page?, context: Context) {
        if (page == null) return
        page.clearCache()
        deleteCache(context)
    }

    fun deleteCache(context: Context) {
        try {
            val dir = context.cacheDir
            deleteDir(dir)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun deleteDir(dir: File?): Boolean {
        if (dir != null && dir.isDirectory) {
            val children = dir.list() ?: return false
            for (child in children) {
                val success = deleteDir(File(dir, child))
                if (!success) {
                    return false
                }
            }
            return dir.delete()
        } else if (dir != null && dir.isFile) {
            return dir.delete()
        } else {
            return false
        }
    }
}
