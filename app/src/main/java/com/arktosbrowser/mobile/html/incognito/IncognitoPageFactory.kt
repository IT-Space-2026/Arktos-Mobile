// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.html.incognito

import android.app.Application
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import com.arktosbrowser.mobile.utils.isValidUrl
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.isDark
import com.arktosbrowser.mobile.constant.FILE
import com.arktosbrowser.mobile.constant.UTF8
import com.arktosbrowser.mobile.database.history.HistoryRepository
import com.arktosbrowser.mobile.html.HtmlPageFactory
import com.arktosbrowser.mobile.html.ListPageReader
import com.arktosbrowser.mobile.html.ShortcutIconResolver
import com.arktosbrowser.mobile.html.jsoup.*
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.search.SearchEngineProvider
import com.arktosbrowser.mobile.utils.DrawableUtils
import dagger.Reusable
import io.reactivex.rxjava3.core.Single
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileWriter
import java.net.URL
import javax.inject.Inject

/**
 * A factory for the home page.
 */
@Reusable
class IncognitoPageFactory @Inject constructor(
        private val application: Application,
        private val searchEngineProvider: SearchEngineProvider,
        private val incognitoPageReader: IncognitoPageReader,
        private var userPreferences: UserPreferences,
        private var resources: Resources,
        private val historyRepository: HistoryRepository,
        private val listPageReader: ListPageReader,
        private val shortcutIcons: ShortcutIconResolver
) : HtmlPageFactory {

    private val title = application.getString(R.string.home)

    override fun buildPage(): Single<String> = Single
            .just(searchEngineProvider.provideSearchEngine())
            .map { (_, queryUrl, _) ->
                parse(incognitoPageReader.provideHtml(application)) andBuild {
                    title { title }
                    charset { UTF8 }
                    body {
                        tag("script") {
                            html(
                                    html()
                                                .replace("\${BASE_URL}", queryUrl)
                                                .replace("&", "\\u0026")


                            )
                        }

                        id("title-pm"){text(resources.getString(R.string.private_title))}
                        id("desc-pm"){text(resources.getString(R.string.private_desc))}
                        if(userPreferences.showShortcuts){
                            var shortcuts = arrayListOf(userPreferences.link1, userPreferences.link2, userPreferences.link3, userPreferences.link4)

                            id("edit_shortcuts"){ text(resources.getString(R.string.edit_shortcuts)) }
                            id("apply"){ text(resources.getString(R.string.apply)) }
                            id("link1click"){ attr("href", shortcuts[0])}
                            id("link2click"){ attr("href", shortcuts[1])}
                            id("link3click"){ attr("href", shortcuts[2])}
                            id("link4click"){ attr("href", shortcuts[3])}

                            shortcuts.forEachIndexed { index, element ->
                                if(!isValidUrl(element)){
                                    val icon = createIconByName('?')
                                    val encoded = bitmapToBase64(icon)

                                    id("link" + (index + 1)){ attr("src", "data:image/png;base64," + encoded)}

                                    return@forEachIndexed
                                }

                                val url = URL(element.replaceFirst("www.", ""))
                                val encoded = bitmapToBase64(createIconByName(url.getHost().first().uppercaseChar()))
                                val src = shortcutIcons.resolve(element) ?: "data:image/png;base64,$encoded"
                                id("link" + (index + 1)){ attr("src", src)}

                            }
                        }
                        else{
                            id("shortcuts"){ attr("style", "display: none;")}
                        }

                    }

                    // Follow the app theme: dark/black themes deepen the
                    // private palette via the arktos-dark class.
                    if(userPreferences.startPageThemeEnabled && userPreferences.resolvedTheme(application).isDark){
                        tag("html"){ addClass("arktos-dark") }
                    }
                }
            }
            .map { content -> Pair(createIncognitoPage(), content) }
            .doOnSuccess { (page, content) ->
                FileWriter(page, false).use { it.write(content) }
            }
            .map { (page, _) -> "$FILE$page" }

    /**
     * Create the incognito page file.
     */
    fun createIncognitoPage() = File(application.filesDir, FILENAME)

    fun createIconByName(name: Char): Bitmap {
        val icon = DrawableUtils.createRoundedLetterImage(
                name,
                64,
                64,
                Color.GRAY
        )
        return icon
    }

    fun bitmapToBase64(bitmap: Bitmap): String{
        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, byteArrayOutputStream)
        val byteArray: ByteArray = byteArrayOutputStream.toByteArray()
        val encoded: String = Base64.encodeToString(byteArray, Base64.NO_WRAP)
        return encoded
    }

    companion object {

        const val FILENAME = "private.html"

    }

}
