// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.settings.fragment

import android.app.Activity
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.browser.SuggestionNumChoice
import com.arktosbrowser.mobile.di.injector
import com.arktosbrowser.mobile.dialog.BrowserDialog
import com.arktosbrowser.mobile.extensions.withSingleChoiceItems
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.search.SearchEngineProvider
import com.arktosbrowser.mobile.search.Suggestions
import com.arktosbrowser.mobile.search.engine.BaseSearchEngine
import com.arktosbrowser.mobile.search.engine.CustomSearch
import com.arktosbrowser.mobile.utils.FileUtils
import com.arktosbrowser.mobile.utils.ThemeUtils
import javax.inject.Inject


/**
 * The general settings of the app.
 */
class GeneralSettingsFragment : AbstractSettingsFragment() {

    @Inject lateinit var searchEngineProvider: SearchEngineProvider
    @Inject lateinit var userPreferences: UserPreferences

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preference_general)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        injector.inject(this)

        clickableDynamicPreference(
                preference = SETTINGS_DOWNLOAD,
                summary = userPreferences.downloadDirectory,
                onClick = ::showDownloadLocationDialog
        )
        val stringArraySuggestions = resources.getStringArray(R.array.suggestion_name_array)

       clickableDynamicPreference(
               preference = SETTINGS_SUGGESTIONS_NUM,
               summary = stringArraySuggestions[userPreferences.suggestionChoice.value],
               onClick = ::showSuggestionNumPicker
       )

        clickableDynamicPreference(
                preference = SETTINGS_SEARCH_ENGINE,
                summary = getSearchEngineSummary(searchEngineProvider.provideSearchEngine()),
                onClick = ::showSearchProviderDialog
        )

        clickableDynamicPreference(
                preference = SETTINGS_SUGGESTIONS,
                summary = searchSuggestionChoiceToTitle(Suggestions.from(userPreferences.searchSuggestionChoice)),
                onClick = ::showSearchSuggestionsDialog
        )

        switchPreference(
                preference = SETTINGS_ALL_TABS,
                isChecked = userPreferences.allTabs,
                onCheckChange = { userPreferences.allTabs = it }
        )

        switchPreference(
                preference = SETTINGS_IMAGES,
                isChecked = userPreferences.blockImagesEnabled,
                onCheckChange = { userPreferences.blockImagesEnabled = it }
        )

        switchPreference(
                preference = SETTINGS_SAVEDATA,
                isChecked = userPreferences.saveDataEnabled,
                onCheckChange = { userPreferences.saveDataEnabled = it }
        )

        switchPreference(
                preference = SETTINGS_TRANSLATE,
                isChecked = userPreferences.translateExtension,
                onCheckChange = { userPreferences.translateExtension = it }
        )

        switchPreference(
                preference = SETTINGS_AMP,
                isChecked = userPreferences.noAmp,
                onCheckChange = { userPreferences.noAmp = it }
        )

        switchPreference(
                preference = SETTINGS_LAST_TAB,
                isChecked = userPreferences.closeOnLastTab,
                onCheckChange = { userPreferences.closeOnLastTab = it }
        )

        switchPreference(
                preference = SETTINGS_RESTORE_TABS,
                isChecked = userPreferences.restoreLostTabsEnabled,
                onCheckChange = { userPreferences.restoreLostTabsEnabled = it }
        )

        switchPreference(
                preference = SETTINGS_FOREGROUND,
                isChecked = userPreferences.tabsToForegroundEnabled,
                onCheckChange = { userPreferences.tabsToForegroundEnabled = it }
        )

    }

    private fun showSuggestionNumPicker(summaryUpdater: SummaryUpdater) {
        BrowserDialog.showCustomDialog(activity) {
            setTitle(R.string.suggestion)
            val stringArray = resources.getStringArray(R.array.suggestion_name_array)
            val values = SuggestionNumChoice.values().map {
                Pair(it, when (it) {
                    SuggestionNumChoice.THREE -> stringArray[0]
                    SuggestionNumChoice.FOUR -> stringArray[1]
                    SuggestionNumChoice.FIVE -> stringArray[2]
                    SuggestionNumChoice.SIX -> stringArray[3]
                    SuggestionNumChoice.SEVEN -> stringArray[4]
                    SuggestionNumChoice.EIGHT -> stringArray[5]
                    else -> stringArray[2]
                })
            }
            withSingleChoiceItems(values, userPreferences.suggestionChoice) {
                updateSearchNum(it, activity as Activity, summaryUpdater)
            }
            setPositiveButton(R.string.action_ok, null)
        }
    }

    private fun updateSearchNum(choice: SuggestionNumChoice, activity: Activity, summaryUpdater: SummaryUpdater) {
        val stringArray = resources.getStringArray(R.array.suggestion_name_array)

        userPreferences.suggestionChoice = choice
        summaryUpdater.updateSummary(stringArray[choice.value])
    }

    private fun showDownloadLocationDialog(summaryUpdater: SummaryUpdater) {
        BrowserDialog.showCustomDialog(activity) {
            setTitle(resources.getString(R.string.title_download_location))
            val n: Int = if (userPreferences.downloadDirectory.contains(Environment.DIRECTORY_DOWNLOADS)) {
                0
            } else {
                1
            }

            setSingleChoiceItems(R.array.download_folder, n) { _, which ->
                when (which) {
                    0 -> {
                        userPreferences.downloadDirectory = FileUtils.DEFAULT_DOWNLOAD_PATH
                        summaryUpdater.updateSummary(FileUtils.DEFAULT_DOWNLOAD_PATH)
                    }
                    1 -> {
                        showCustomDownloadLocationPicker(summaryUpdater)
                    }
                }
            }
            setPositiveButton(resources.getString(R.string.action_ok), null)
        }
    }


    private fun showCustomDownloadLocationPicker(summaryUpdater: SummaryUpdater) {
        activity?.let { activity ->
            val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_edit_text, null)
            val getDownload = dialogView.findViewById<EditText>(R.id.dialog_edit_text)

            val errorColor = ContextCompat.getColor(activity, R.color.error_red)
            val regularColor = ThemeUtils.getTextColor(activity)
            getDownload.setTextColor(regularColor)
            getDownload.addTextChangedListener(DownloadLocationTextWatcher(getDownload, errorColor, regularColor))
            getDownload.setText(userPreferences.downloadDirectory)

            BrowserDialog.showCustomDialog(activity) {
                setTitle(R.string.title_download_location)
                setView(dialogView)
                setPositiveButton(R.string.action_ok) { _, _ ->
                    var text = getDownload.text.toString()
                    text = FileUtils.addNecessarySlashes(text)
                    // The TextWatcher only recolored invalid paths — the
                    // value was saved anyway and downloads silently
                    // failed. Reject unwritable targets outright.
                    if (!FileUtils.isWriteAccessAvailable(text)) {
                        Toast.makeText(
                            activity,
                            R.string.download_location_invalid,
                            Toast.LENGTH_LONG
                        ).show()
                        return@setPositiveButton
                    }
                    userPreferences.downloadDirectory = text
                    summaryUpdater.updateSummary(text)
                }
            }
        }
    }

    private class DownloadLocationTextWatcher(
            private val getDownload: EditText,
            private val errorColor: Int,
            private val regularColor: Int
    ) : TextWatcher {

        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}

        override fun afterTextChanged(s: Editable) {
            if (!FileUtils.isWriteAccessAvailable(s.toString())) {
                this.getDownload.setTextColor(this.errorColor)
            } else {
                this.getDownload.setTextColor(this.regularColor)
            }
        }
    }

    private fun getSearchEngineSummary(baseSearchEngine: BaseSearchEngine): String {
        return if (baseSearchEngine is CustomSearch) {
            baseSearchEngine.queryUrl
        } else {
            getString(baseSearchEngine.titleRes)
        }
    }

    private fun convertSearchEngineToString(searchEngines: List<BaseSearchEngine>): Array<CharSequence> =
        searchEngines.map { getString(it.titleRes) }.toTypedArray()

    private fun showSearchProviderDialog(summaryUpdater: SummaryUpdater) {
        BrowserDialog.showCustomDialog(activity) {
            setTitle(resources.getString(R.string.title_search_engine))

            val searchEngineList = searchEngineProvider.provideAllSearchEngines()

            val chars = convertSearchEngineToString(searchEngineList)

            val n = userPreferences.searchChoice

            setSingleChoiceItems(chars, n) { _, which ->
                val searchEngine = searchEngineList[which]
                val preferencesIndex = searchEngineProvider.mapSearchEngineToPreferenceIndex(searchEngine)

                if (searchEngine is CustomSearch) {
                    // Commit the Custom choice only once a valid URL is
                    // confirmed — otherwise canceling the dialog leaves
                    // search permanently broken on a stale template.
                    showCustomSearchDialog(searchEngine, preferencesIndex, summaryUpdater)
                } else {
                    userPreferences.searchChoice = preferencesIndex
                    summaryUpdater.updateSummary(getSearchEngineSummary(searchEngine))
                }
            }
            setPositiveButton(R.string.action_ok, null)
        }
    }

    private fun showCustomSearchDialog(
        customSearch: CustomSearch,
        preferencesIndex: Int,
        summaryUpdater: SummaryUpdater
    ) {
        activity?.let {
            BrowserDialog.showEditText(
                    it,
                    R.string.search_engine_custom,
                    R.string.search_engine_custom,
                    userPreferences.searchUrl,
                    R.string.action_ok
            ) { searchUrl ->
                // The query is appended by the use-site
                // ("$queryUrl$QUERY_PLACE_HOLDER") — the stored template
                // is just the search prefix (e.g. "...&q="). Require
                // https so searches never go out unencrypted.
                val trimmed = searchUrl.trim()
                if (!trimmed.startsWith("https://")) {
                    Toast.makeText(it, R.string.custom_search_invalid, Toast.LENGTH_LONG).show()
                    return@showEditText
                }
                userPreferences.searchUrl = trimmed
                userPreferences.searchChoice = preferencesIndex
                summaryUpdater.updateSummary(getSearchEngineSummary(customSearch))
            }

        }
    }

    private fun searchSuggestionChoiceToTitle(choice: Suggestions): String =
        when (choice) {
            Suggestions.GOOGLE -> getString(R.string.powered_by_google)
            Suggestions.DUCK -> getString(R.string.powered_by_duck)
            Suggestions.STARTPAGE -> getString(R.string.powered_by_startpage)
            Suggestions.QWANT -> getString(R.string.powered_by_qwant)
            Suggestions.NONE -> getString(R.string.search_suggestions_off)
        }

    private fun showSearchSuggestionsDialog(summaryUpdater: SummaryUpdater) {
        BrowserDialog.showCustomDialog(activity) {
            setTitle(resources.getString(R.string.search_suggestions))

            val currentChoice = when (Suggestions.from(userPreferences.searchSuggestionChoice)) {
                Suggestions.GOOGLE -> 0
                Suggestions.DUCK -> 1
                Suggestions.STARTPAGE -> 2
                Suggestions.QWANT -> 3
                Suggestions.NONE -> 4
            }

            setSingleChoiceItems(R.array.suggestions, currentChoice) { _, which ->
                val suggestionsProvider = when (which) {
                    0 -> Suggestions.GOOGLE
                    1 -> Suggestions.DUCK
                    2 -> Suggestions.STARTPAGE
                    3 -> Suggestions.QWANT
                    4 -> Suggestions.NONE
                    else -> Suggestions.DUCK
                }
                userPreferences.searchSuggestionChoice = suggestionsProvider.index
                summaryUpdater.updateSummary(searchSuggestionChoiceToTitle(suggestionsProvider))
                Toast.makeText(context, getText(R.string.please_restart), Toast.LENGTH_LONG).show()
            }
            setPositiveButton(resources.getString(R.string.action_ok), null)
        }
    }

    companion object {
        private const val SETTINGS_IMAGES = "cb_images"
        private const val SETTINGS_SAVEDATA = "savedata"
        private const val SETTINGS_DOWNLOAD = "download"
        private const val SETTINGS_SEARCH_ENGINE = "search"
        private const val SETTINGS_SUGGESTIONS = "suggestions_choice"
        private const val SETTINGS_SUGGESTIONS_NUM = "suggestions_number"
        private const val SETTINGS_LAST_TAB = "last_tab"
        private const val SETTINGS_ALL_TABS = "load_tabs"
        private const val SETTINGS_TRANSLATE = "translate"
        private const val SETTINGS_AMP = "amp"
        private const val SETTINGS_RESTORE_TABS = "restore_tabs"
        private const val SETTINGS_FOREGROUND = "new_tabs_foreground"
    }
}
