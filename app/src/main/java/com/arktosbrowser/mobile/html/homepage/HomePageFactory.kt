package com.arktosbrowser.mobile.html.homepage

import android.app.Application
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import android.webkit.URLUtil
import com.arktosbrowser.mobile.AppTheme
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.browser.HomepageTypeChoice
import com.arktosbrowser.mobile.constant.FILE
import com.arktosbrowser.mobile.constant.UTF8
import com.arktosbrowser.mobile.database.history.HistoryRepository
import com.arktosbrowser.mobile.html.HtmlPageFactory
import com.arktosbrowser.mobile.html.ListPageReader
import com.arktosbrowser.mobile.html.jsoup.*
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.search.SearchEngineProvider
import com.arktosbrowser.mobile.utils.DrawableUtils
import dagger.Reusable
import io.reactivex.Single
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileWriter
import java.net.URI
import javax.inject.Inject


/**
 * A factory for the home page.
 */
@Reusable
class HomePageFactory @Inject constructor(
        private val application: Application,
        private val searchEngineProvider: SearchEngineProvider,
        private val homePageReader: HomePageReader,
        private var userPreferences: UserPreferences,
        private var resources: Resources,
        private val historyRepository: HistoryRepository,
        private val listPageReader: ListPageReader
) : HtmlPageFactory {

    private val title = application.getString(R.string.home)

    override fun buildPage(): Single<String> = Single
        .just(searchEngineProvider.provideSearchEngine())
        .map { (_, queryUrl, _) ->
            parse(homePageReader.provideHtml(application)) andBuild {
                title { title }
                charset { UTF8 }
                body {
                    // Add background image
                    if(userPreferences.imageUrlString != ""){ tag("body") { attr("style", "background: url('" + userPreferences.imageUrlString + "') no-repeat scroll;") } }

                    // Fill params in scripts
                    tag("script") {
                        html(
                                if(userPreferences.homepageType == HomepageTypeChoice.INFORMATIVE){
                                    html()
                                            .replace("\${ENDPOINT}", userPreferences.newsEndpoint)
                                            .replace("\${BASE_URL}", queryUrl)
                                            .replace("&", "\\u0026")
                                }
                                else{
                                    html()
                                            .replace("\${BASE_URL}", queryUrl)
                                            .replace("&", "\\u0026")
                                }
                        )
                    }

                    if(userPreferences.homepageType == HomepageTypeChoice.FOCUSED){
                        id("image_url").remove()
                    }

                    // Shortcuts
                    if(userPreferences.showShortcuts){
                        val shortcuts = arrayListOf(userPreferences.link1, userPreferences.link2, userPreferences.link3, userPreferences.link4)

                        id("edit_shortcuts"){ text(resources.getString(R.string.edit_shortcuts)) }
                        id("apply"){ text(resources.getString(R.string.apply)) }
                        id("link1click"){ attr("href", shortcuts[0])}
                        id("link2click"){ attr("href", shortcuts[1])}
                        id("link3click"){ attr("href", shortcuts[2])}
                        id("link4click"){ attr("href", shortcuts[3])}

                        shortcuts.forEachIndexed { index, element ->
                            if(!URLUtil.isValidUrl(element)){
                                val icon = createIconByName('?')
                                val encoded = bitmapToBase64(icon)

                                id("link" + (index + 1)){ attr("src",
                                        "data:image/png;base64,$encoded"
                                )}

                                return@forEachIndexed
                            }

                            val url = URI(element.replaceFirst("www.", ""))
                            val icon = createIconByName(url.host.first().uppercaseChar())
                            val encoded = bitmapToBase64(icon)
                            id("link" + (index + 1)){ attr("src", "https://${URI(element).host}/favicon.ico")}
                            id("link" + (index + 1)){ attr("onerror", "this.src = 'data:image/png;base64,$encoded';")}

                        }
                    }
                    else{
                        id("shortcuts"){ attr("style", "display: none;")}
                    }

                }

                // Follow the app theme: dark/black themes flip the page to
                // the aurora dark palette via the arktos-dark class.
                if(userPreferences.startPageThemeEnabled && userPreferences.resolvedTheme(application) != AppTheme.LIGHT){
                    tag("html"){ addClass("arktos-dark") }
                }
            }
        }
        .map { content -> Pair(createHomePage(), content) }
        .doOnSuccess { (page, content) ->
            FileWriter(page, false).use { it.write(content) }
        }
        .map { (page, _) -> "$FILE$page" }

    /**
     * Create the home page file.
     */
    fun createHomePage() = File(application.filesDir, FILENAME)

    fun createIconByName(name: Char): Bitmap{
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

        const val FILENAME = "homepage.html"

    }

}
