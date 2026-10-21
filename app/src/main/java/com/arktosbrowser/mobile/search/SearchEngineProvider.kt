// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.search

import android.app.Application
import com.arktosbrowser.mobile.di.SuggestionsClient
import com.arktosbrowser.mobile.log.Logger
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.search.engine.*
import com.arktosbrowser.mobile.search.suggestions.*
import com.arktosbrowser.mobile.uniffi.SuggestionProvider
import dagger.Reusable
import io.reactivex.rxjava3.core.Single
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * The model that provides the search engine based
 * on the user's preference.
 */
@Reusable
class  SearchEngineProvider @Inject constructor(
    private val userPreferences: UserPreferences,
    @SuggestionsClient private val okHttpClient: Single<OkHttpClient>,
    private val requestFactory: RequestFactory,
    private val application: Application,
    private val logger: Logger
) {

    /**
     * Provide the [SuggestionsRepository] that maps to the user's current preference.
     */
    fun provideSearchSuggestions(): SuggestionsRepository =
        when (userPreferences.searchSuggestionChoice) {
            0 -> RustSuggestionsModel(SuggestionProvider.GOOGLE, okHttpClient, requestFactory, application, logger)
            // 2 (Baidu), 3 (Naver) were removed — those saved indices
            // fall through to the DuckDuckGo default.
            5 -> NoOpSuggestionsRepository()
            6 -> RustSuggestionsModel(SuggestionProvider.STARTPAGE, okHttpClient, requestFactory, application, logger)
            7 -> RustSuggestionsModel(SuggestionProvider.QWANT, okHttpClient, requestFactory, application, logger)
            else -> RustSuggestionsModel(SuggestionProvider.DUCK, okHttpClient, requestFactory, application, logger)
        }

    /**
     * Provide the [BaseSearchEngine] that maps to the user's current preference.
     */
    fun provideSearchEngine(): BaseSearchEngine =
        when (userPreferences.searchChoice) {
            0 -> CustomSearch(userPreferences.searchUrl)
            1 -> GoogleSearch()
            // 2 (Ask), 9 (Baidu), 10 (Yandex), 11 (Naver), 13 (Ekoru),
            // 14 (Searx) were removed — those saved indices fall through
            // to the default.
            3 -> BingSearch()
            4 -> YahooSearch()
            5 -> StartPageSearch()
            6 -> StartPageMobileSearch()
            7 -> DuckSearch()
            8 -> DuckLiteSearch()
            12 -> EcosiaSearch()
            15 -> MojeekSearch()
            16 -> QwantSearch()
            else -> GoogleSearch()
        }

    /**
     * Return the serializable index of of the provided [BaseSearchEngine].
     */
    fun mapSearchEngineToPreferenceIndex(searchEngine: BaseSearchEngine): Int =
        when (searchEngine) {
            is CustomSearch -> 0
            is GoogleSearch -> 1
            is BingSearch -> 3
            is YahooSearch -> 4
            is StartPageSearch -> 5
            is StartPageMobileSearch -> 6
            is DuckSearch -> 7
            is DuckLiteSearch -> 8
            is EcosiaSearch -> 12
            is MojeekSearch -> 15
            is QwantSearch -> 16
            else -> throw UnsupportedOperationException("Unknown search engine provided: " + searchEngine.javaClass)
        }

    /**
     * Provide a list of all supported search engines.
     */
    fun provideAllSearchEngines(): List<BaseSearchEngine> = listOf(
        CustomSearch(userPreferences.searchUrl),
        GoogleSearch(),
        BingSearch(),
        YahooSearch(),
        StartPageSearch(),
        StartPageMobileSearch(),
        DuckSearch(),
        DuckLiteSearch(),
        EcosiaSearch(),
        MojeekSearch(),
        QwantSearch()
    )

}
