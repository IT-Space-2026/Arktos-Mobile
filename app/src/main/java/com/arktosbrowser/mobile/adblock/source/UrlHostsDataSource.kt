// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.adblock.source

import com.arktosbrowser.mobile.extensions.onIOExceptionResumeNext
import com.arktosbrowser.mobile.log.Logger
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.preference.userAgent
import android.app.Application
import io.reactivex.rxjava3.core.Single
import okhttp3.*
import java.io.IOException

/**
 * A [HostsDataSource] that loads hosts from an [HttpUrl].
 */
class UrlHostsDataSource(
    private val url: HttpUrl,
    private val okHttpClient: Single<OkHttpClient>,
    private val logger: Logger,
    private val userPreferences: UserPreferences,
    private val application: Application
) : HostsDataSource {

    override fun loadHosts(): Single<HostsResult> =
        okHttpClient.flatMap { client ->
            Single.create<HostsResult> { emitter ->
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", userPreferences.userAgent(application))
                    .get()
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        emitter.onError(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val successfulResponse = response.takeIf(Response::isSuccessful)
                            ?: return emitter.onError(IOException("Error reading remote file"))
                        val content = successfulResponse.body?.string()
                            ?: return emitter.onError(IOException("Empty response"))

                        logger.log(TAG, "Loaded hosts file (${content.length} chars)")
                        emitter.onSuccess(HostsResult.Success(content))
                    }
                })
            }.onIOExceptionResumeNext { HostsResult.Failure(it) }
        }

    companion object {
        private const val TAG = "UrlHostsDataSource"
    }

}
