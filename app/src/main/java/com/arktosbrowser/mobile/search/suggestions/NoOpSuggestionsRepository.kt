package com.arktosbrowser.mobile.search.suggestions

import com.arktosbrowser.mobile.database.SearchSuggestion
import io.reactivex.rxjava3.core.Single

/**
 * A search suggestions repository that doesn't fetch any results.
 */
class NoOpSuggestionsRepository : SuggestionsRepository {

    private val emptySingle: Single<List<SearchSuggestion>> = Single.just(emptyList())

    override fun resultsForSearch(rawQuery: String) = emptySingle
}
