// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.update

import com.arktosbrowser.mobile.BuildConfig
import com.arktosbrowser.mobile.di.NetworkScheduler
import com.arktosbrowser.mobile.di.SuggestionsClient
import com.arktosbrowser.mobile.log.Logger
import com.arktosbrowser.mobile.preference.UserPreferences
import android.app.Application
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.core.Single
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UPDT01 — periodic "is a newer release out" check against the
 * GitHub Releases API. Release tags carry the semver (`v1.1` →
 * `1.1`); a tag that sorts above the running [BuildConfig.VERSION_NAME]
 * is an available update. Runs at most once per day and only when the
 * user left the General-settings toggle on; incognito sessions never
 * reach the network for this.
 */
@Singleton
class UpdateChecker @Inject constructor(
    @SuggestionsClient private val okHttpClient: Single<OkHttpClient>,
    @NetworkScheduler private val networkScheduler: Scheduler,
    private val userPreferences: UserPreferences,
    private val application: Application,
    private val logger: Logger
) {

    data class UpdateInfo(val tag: String, val version: String, val url: String)

    /**
     * The newest published release when it is strictly newer than the
     * running build — completes empty on up-to-date, malformed
     * payloads and network/HTTP failures (an update check must never
     * crash startup or nag on a hiccup).
     */
    fun check(): Maybe<UpdateInfo> = latestRelease()
        .filter { isNewer(it.version, BuildConfig.VERSION_NAME) }

    /**
     * UPDT02 — the newest published release regardless of the running
     * build. The About screen shows this as "latest downloadable".
     */
    fun latestRelease(): Maybe<UpdateInfo> = okHttpClient
        .flatMapMaybe { client -> Maybe.fromCallable { fetchLatest(client) } }
        .subscribeOn(networkScheduler)
        .doOnError { logger.log(TAG, "Update check failed", it) }
        .onErrorComplete()

    private fun fetchLatest(client: OkHttpClient): UpdateInfo? {
        val request = Request.Builder()
            .url(RELEASE_API_URL)
            .header("Accept", "application/vnd.github+json")
            // Bypass the shared client's disk cache (and any upstream
            // proxy cache) — a stale releases list made About show an
            // old "latest" version long after a newer tag shipped.
            .header("Cache-Control", "no-cache")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            // Read the list, not /releases/latest — GitHub's "latest"
            // pointer can lag behind new tags and resolve to an older
            // release; the highest published semver is authoritative.
            val arr = org.json.JSONArray(response.body?.string() ?: return null)
            var best: UpdateInfo? = null
            for (i in 0 until arr.length()) {
                val rel = arr.optJSONObject(i) ?: continue
                if (rel.optBoolean("draft") || rel.optBoolean("prerelease")) continue
                val tag = rel.optString("tag_name").trim()
                val version = versionOf(tag) ?: continue
                val current = best
                if (current == null || isNewer(version, current.version)) {
                    best = UpdateInfo(
                        tag = tag,
                        version = version,
                        url = rel.optString("html_url").ifBlank { RELEASE_PAGE_URL }
                    )
                }
            }
            return best
        }
    }

    companion object {
        private const val TAG = "UpdateChecker"
        private const val RELEASE_API_URL =
            "https://api.github.com/repos/IT-Space-2026/Arktos-Mobile/releases?per_page=20"
        private const val RELEASE_PAGE_URL =
            "https://github.com/IT-Space-2026/Arktos-Mobile/releases/latest"

        /** `v1.2.3` / `1.2` → `1.2.3`; anything else → null. */
        internal fun versionOf(tag: String): String? {
            val m = Regex("""^v?(\d+)\.(\d+)(?:\.(\d+))?.*$""").find(tag.trim())
                ?: return null
            val (a, b, c) = m.destructured
            return "$a.$b.${c.ifEmpty { "0" }}"
        }

        /** Strict semver compare: true when [candidate] sorts above [current]. */
        internal fun isNewer(candidate: String, current: String): Boolean {
            fun triple(s: String): IntArray? {
                val parts = s.split(".").map { it.toIntOrNull() ?: return null }
                if (parts.isEmpty() || parts.size > 3) return null
                return IntArray(3) { parts.getOrElse(it) { 0 } }
            }
            val c = triple(candidate) ?: return false
            val v = triple(current) ?: return false
            for (i in 0..2) {
                if (c[i] != v[i]) return c[i] > v[i]
            }
            return false
        }
    }

}
