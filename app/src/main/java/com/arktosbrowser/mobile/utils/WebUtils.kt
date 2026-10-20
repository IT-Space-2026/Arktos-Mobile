package com.arktosbrowser.mobile.utils

import android.content.Context
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import androidx.annotation.RequiresApi
import com.arktosbrowser.mobile.database.history.HistoryRepository
import io.reactivex.rxjava3.core.Scheduler
import java.io.File

/**
 * Copyright 8/4/2015 Anthony Restaino
 */
object WebUtils {

    fun clearCookies(context: Context) {
        CookieManager.getInstance().removeAllCookies(null)
    }

    fun clearWebStorage() {
        WebStorage.getInstance().deleteAllData()
    }

    /**
     * Wipes one origin's site data — the per-site scope of
     * [eraseWebStorage]. CookieManager has no per-host delete, so the
     * host's cookie names are re-set with an epoch expiry; DOM
     * storage goes through [WebStorage.deleteOrigin].
     */
    fun clearSiteData(url: String) {
        val uri = Uri.parse(url)
        val host = uri.host ?: return
        val origin = "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"

        val cookieManager = CookieManager.getInstance()
        val cookies = cookieManager.getCookie(url).orEmpty()
        for (pair in cookies.split(";")) {
            val name = pair.substringBefore('=').trim()
            if (name.isNotEmpty()) {
                cookieManager.setCookie(origin,
                    "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Domain=$host")
            }
        }
        cookieManager.flush()

        WebStorage.getInstance().deleteOrigin(origin)
    }

    fun eraseWebStorage(context: Context) {
        WebStorage.getInstance().deleteAllData()

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
        val webViewDatabase = WebViewDatabase.getInstance(context)
        webViewDatabase.clearFormData()
        webViewDatabase.clearHttpAuthUsernamePassword()
        Utils.trimCache(context)
    }

    fun clearCache(view: WebView?, context: Context) {
        if (view == null) return
        view.clearCache(true)
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
