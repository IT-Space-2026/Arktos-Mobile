// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.favicon

import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.extensions.pad
import com.arktosbrowser.mobile.extensions.safeUse
import com.arktosbrowser.mobile.html.ShortcutIconResolver
import com.arktosbrowser.mobile.log.Logger
import com.arktosbrowser.mobile.utils.DrawableUtils
import com.arktosbrowser.mobile.utils.FileUtils
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import androidx.annotation.ColorInt
import androidx.annotation.WorkerThread
import androidx.core.net.toUri
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Maybe
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reactive model that can fetch favicons
 * from URLs and also cache them.
 */
@Singleton
class FaviconModel @Inject constructor(
    private val application: Application,
    private val logger: Logger,
    private val shortcutIconResolver: ShortcutIconResolver
) {

    private val loaderOptions = BitmapFactory.Options()
    private val bookmarkIconSize = application.resources.getDimensionPixelSize(R.dimen.material_grid_small_icon)
    private val faviconCache = object : LruCache<String, Bitmap>(FileUtils.megabytesToBytes(1).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /**
     * Retrieves a favicon from the memory cache.Bitmap may not be present if no bitmap has been
     * added for the URL or if it has been evicted from the memory cache.
     *
     * @param url the URL to retrieve the bitmap for.
     * @return the bitmap associated with the URL, may be null.
     */
    private fun getFaviconFromMemCache(url: String): Bitmap? {
        synchronized(faviconCache) {
            return faviconCache.get(url)
        }
    }

    fun createDefaultBitmapForTitle(title: String?): Bitmap {
        val firstTitleCharacter = title?.takeIf(String::isNotBlank)?.let { it[0] } ?: '?'

        @ColorInt val defaultFaviconColor = DrawableUtils.characterToColorHash(firstTitleCharacter, application)

        return DrawableUtils.createRoundedLetterImage(
            firstTitleCharacter,
            bookmarkIconSize,
            bookmarkIconSize,
            defaultFaviconColor
        )
    }

    /**
     * Adds a bitmap to the memory cache for the given URL.
     *
     * @param url    the URL to map the bitmap to.
     * @param bitmap the bitmap to store.
     */
    private fun addFaviconToMemCache(url: String, bitmap: Bitmap) {
        synchronized(faviconCache) {
            faviconCache.put(url, bitmap)
        }
    }

    /**
     * Retrieves the favicon for a URL, may be from network or cache.
     *
     * @param url   The URL that we should retrieve the favicon for.
     * @param title The title for the web page.
     */
    fun faviconForUrl(url: String, title: String): Maybe<Bitmap> = Maybe.create {
        val uri = url.toUri().toValidUri()
            ?: return@create it.onSuccess(createDefaultBitmapForTitle(title).pad())

        val cachedFavicon = getFaviconFromMemCache(url)

        if (cachedFavicon != null) {
            return@create it.onSuccess(cachedFavicon.pad())
        }

        val faviconCacheFile = getFaviconCacheFile(application, uri)

        if (faviconCacheFile.exists() &&
            System.currentTimeMillis() - faviconCacheFile.lastModified() < ICON_TTL_MS) {
            val storedFavicon = BitmapFactory.decodeFile(faviconCacheFile.path, loaderOptions)

            if (storedFavicon != null) {
                addFaviconToMemCache(url, storedFavicon)
                return@create it.onSuccess(storedFavicon.pad())
            }
        }

        // Never visited (imported bookmarks) or stale icon — resolve it
        // directly from the site itself (declared <link> icons, then
        // /favicon.ico), never through a third-party icon service.
        resolveRemoteIcon(uri)?.let { bitmap ->
            addFaviconToMemCache(url, bitmap)
            cacheFaviconForUrl(bitmap, url).subscribe()
            return@create it.onSuccess(bitmap.pad())
        }

        return@create it.onSuccess(createDefaultBitmapForTitle(title).pad())
    }

    /**
     * Fetch the host's icon via [ShortcutIconResolver] (data-URI
     * result) and decode it to a [Bitmap]. SVG payloads are skipped —
     * BitmapFactory can't rasterize them — and ICO containers get
     * their embedded PNG extracted.
     */
    @WorkerThread
    private fun resolveRemoteIcon(uri: ValidUri): Bitmap? {
        val dataUri = runCatching {
            shortcutIconResolver.resolve("${uri.scheme}://${uri.host}")
        }.getOrNull() ?: return null
        val comma = dataUri.indexOf(',')
        if (comma <= 0 || "svg" in dataUri.substring(0, comma)) return null
        val bytes = runCatching {
            Base64.decode(dataUri.substring(comma + 1), Base64.DEFAULT)
        }.getOrNull() ?: return null
        return if (bytes.size >= 4 && bytes[2] == 1.toByte() &&
            bytes[0] == 0.toByte() && bytes[1] == 0.toByte()) {
            decodeIco(bytes)
        } else {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, loaderOptions)
        }
    }

    /**
     * Android has no ICO decoder — pull the largest PNG-encoded frame
     * out of the container (BMP frames are skipped; modern favicons
     * almost always carry PNG).
     */
    private fun decodeIco(bytes: ByteArray): Bitmap? {
        if (bytes.size < 6) return null
        val count = (bytes[5].toInt() and 0xFF shl 8) or (bytes[4].toInt() and 0xFF)
        var best: Bitmap? = null
        var bestEdge = -1
        for (i in 0 until count) {
            val off = 6 + i * 16
            if (off + 16 > bytes.size) break
            val edge = bytes[off].toInt() and 0xFF
            val size = (bytes[off + 11].toInt() and 0xFF shl 24) or
                (bytes[off + 10].toInt() and 0xFF shl 16) or
                (bytes[off + 9].toInt() and 0xFF shl 8) or
                (bytes[off + 8].toInt() and 0xFF)
            val pos = (bytes[off + 15].toInt() and 0xFF shl 24) or
                (bytes[off + 14].toInt() and 0xFF shl 16) or
                (bytes[off + 13].toInt() and 0xFF shl 8) or
                (bytes[off + 12].toInt() and 0xFF)
            if (size <= 0 || pos + size > bytes.size) continue
            // Embedded frame starts with the PNG magic?
            if (bytes[pos] != 0x89.toByte() || bytes[pos + 1] != 0x50.toByte()) continue
            val bmp = BitmapFactory.decodeByteArray(bytes, pos, size, loaderOptions) ?: continue
            val realEdge = if (edge == 0) 256 else edge
            if (realEdge > bestEdge) {
                best = bmp
                bestEdge = realEdge
            }
        }
        return best
    }

    /**
     * Caches a favicon for a particular URL.
     *
     * @param favicon the favicon to cache.
     * @param url     the URL to cache the favicon for.
     * @return an observable that notifies the consumer when it is complete.
     */
    fun cacheFaviconForUrl(favicon: Bitmap, url: String): Completable = Completable.create { emitter ->
        val uri = url.toUri().toValidUri() ?: return@create emitter.onComplete()

        logger.log(TAG, "Caching icon for ${uri.host}")
        FileOutputStream(getFaviconCacheFile(application, uri)).safeUse {
            favicon.compress(Bitmap.CompressFormat.PNG, 100, it)
            it.flush()
            emitter.onComplete()
        }
    }

    companion object {

        private const val TAG = "FaviconModel"

        private const val ICON_TTL_MS = 30L * 24 * 60 * 60 * 1000

        /**
         * Creates the cache file for the favicon image. The file name
         * is a SHA-256 digest of the host — `String.hashCode` was
         * collision-prone enough that two different hosts could share
         * one cached icon.
         *
         * @param app the context needed to retrieve the cache directory.
         * @param validUri the URI to use as a unique identifier.
         * @return a valid cache file.
         */
        @WorkerThread
        fun getFaviconCacheFile(app: Application, validUri: ValidUri): File {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(validUri.host.toByteArray())
                .joinToString("") { "%02x".format(it) }

            return File(app.cacheDir, "$digest.png")
        }
    }

}
