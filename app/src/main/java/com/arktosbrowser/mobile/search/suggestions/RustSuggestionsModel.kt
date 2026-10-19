package com.arktosbrowser.mobile.search.suggestions

import android.app.Application
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.constant.UTF8
import com.arktosbrowser.mobile.database.SearchSuggestion
import com.arktosbrowser.mobile.extensions.preferredLocale
import com.arktosbrowser.mobile.log.Logger
import com.arktosbrowser.mobile.rust.ArktosCore
import com.arktosbrowser.mobile.uniffi.SuggestionProvider
import io.reactivex.rxjava3.core.Single
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.io.UnsupportedEncodingException
import java.net.URLEncoder

/**
 * The single search-suggestions model (PORT01). URL construction and
 * response parsing live in the Rust core — this class keeps the
 * OkHttp fetch, RxJava shape, and result wrapping (localized subtitle)
 * in Kotlin.
 */
class RustSuggestionsModel(
    private val provider: SuggestionProvider,
    private val okHttpClient: Single<OkHttpClient>,
    private val requestFactory: RequestFactory,
    application: Application,
    private val logger: Logger
) : SuggestionsRepository {

    private val searchSubtitle = application.getString(R.string.suggestion)
    private val language =
        application.preferredLocale.language.takeIf(String::isNotEmpty) ?: DEFAULT_LANGUAGE

    override fun resultsForSearch(rawQuery: String): Single<List<SearchSuggestion>> =
        okHttpClient.flatMap { client ->
            Single.fromCallable {
                val query = try {
                    URLEncoder.encode(rawQuery, UTF8)
                } catch (throwable: UnsupportedEncodingException) {
                    logger.log(TAG, "Unable to encode the URL", throwable)

                    return@fromCallable emptyList<SearchSuggestion>()
                }
                return@fromCallable client.downloadSuggestionsForQuery(query)
                    ?.body
                    ?.use { body -> ArktosCore.parseSuggestions(provider, body.bytes()) }
                    ?.take(MAX_RESULTS)
                    ?.map { SearchSuggestion("$searchSubtitle \"$it\"", it) }
                    ?: emptyList()
            }
        }

    /**
     * This method downloads the search suggestions for the specific query.
     * NOTE: This is a blocking operation, do not fetchResults on the UI thread.
     */
    private fun OkHttpClient.downloadSuggestionsForQuery(query: String): Response? {
        val queryUrl = ArktosCore.suggestionQueryUrl(provider, query, language).toHttpUrlOrNull()
            ?: return null
        val request = requestFactory.createSuggestionsRequest(queryUrl, UTF8)
        return try {
            newCall(request).execute()
        } catch (exception: IOException) {
            logger.log(TAG, "Problem getting search suggestions", exception)
            null
        }
    }

    companion object {

        private const val TAG = "RustSuggestionsModel"

        private const val MAX_RESULTS = 8
        private const val DEFAULT_LANGUAGE = "en"

    }

}
