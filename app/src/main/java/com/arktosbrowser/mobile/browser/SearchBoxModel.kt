package com.arktosbrowser.mobile.browser

import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.rust.ArktosCore
import com.arktosbrowser.mobile.utils.isSpecialUrl
import com.arktosbrowser.mobile.utils.trimmedForDisplay
import android.app.Application
import dagger.Reusable
import javax.inject.Inject

/**
 * A UI model for the search box.
 */
@Reusable
class SearchBoxModel @Inject constructor(
    private val userPreferences: UserPreferences,
    application: Application
) {

    private val untitledTitle: String = application.getString(R.string.untitled)

    /**
     * Returns the contents of the search box based on a variety of factors.
     *
     *  - The user's preference to show either the URL, domain, or page title
     *  - Whether or not the current page is loading
     *  - Whether or not the current page is a Lightning generated page.
     *
     * This method uses the URL, title, and loading information to determine what
     * should be displayed by the search box.
     *
     * @param url       the URL of the current page.
     * @param title     the title of the current page, if known.
     * @param isLoading whether the page is currently loading or not.
     * @return the string that should be displayed by the search box.
     */
    fun getDisplayContent(url: String, title: String?, isLoading: Boolean): String =
        when {
            url.isSpecialUrl() -> ""
            isLoading -> url.forUrlBar()
            else -> when (userPreferences.urlBoxContentChoice) {
                SearchBoxDisplayChoice.URL -> url.forUrlBar()
                SearchBoxDisplayChoice.DOMAIN -> safeDomain(url)
                SearchBoxDisplayChoice.TITLE ->
                    if (title?.isEmpty() == false) {
                        title
                    } else {
                        untitledTitle
                    }
            }
        }

    private fun safeDomain(url: String) = ArktosCore.domainName(url)

    private fun String.forUrlBar(): String =
        if (userPreferences.trimUrlBarEnabled) trimmedForDisplay() else this

}
