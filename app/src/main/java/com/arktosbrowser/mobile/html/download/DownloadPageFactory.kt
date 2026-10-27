// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.html.download

import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.constant.FILE
import com.arktosbrowser.mobile.database.downloads.DownloadEntry
import com.arktosbrowser.mobile.database.downloads.DownloadsRepository
import com.arktosbrowser.mobile.html.HtmlPageFactory
import com.arktosbrowser.mobile.download.DownloadsPageActions
import com.arktosbrowser.mobile.html.ListPageReader
import com.arktosbrowser.mobile.html.jsoup.*
import com.arktosbrowser.mobile.preference.UserPreferences
import android.app.Application
import dagger.Reusable
import io.reactivex.rxjava3.core.Single
import java.io.File
import java.io.FileWriter
import javax.inject.Inject

/**
 * The factory for the downloads page.
 */
@Reusable
class DownloadPageFactory @Inject constructor(
    private val application: Application,
    private val userPreferences: UserPreferences,
    private val manager: DownloadsRepository,
    private val listPageReader: ListPageReader
) : HtmlPageFactory {

    override fun buildPage(): Single<String> = manager
        .getAllDownloads()
        // The disk scheduler is a single thread — a wedged store call
        // would leave the tab untitled+blank forever; fall back to the
        // empty state on timeout or error.
        .timeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .onErrorReturnItem(emptyList())
        .map { list ->
            parse(listPageReader.provideHtml(application)) andBuild {
                title { application.getString(R.string.action_downloads) }
                body {
                    val repeatableElement = id("repeated").removeElement()
                    id("content") {
                        if (list.isEmpty()) {
                            appendChild(repeatableElement.clone {
                                id("title") { text(application.getString(R.string.downloads_empty)) }
                                id("url") { text("") }
                            })
                        } else {
                            appendChild(repeatableElement.clone {
                                tag("a") { attr("href", DownloadsPageActions.CLEAR_URL) }
                                id("title") { text(application.getString(R.string.downloads_clear_all)) }
                                id("url") { text("") }
                            })
                        }
                        list.forEach {
                            appendChild(repeatableElement.clone {
                                tag("a") { attr("href", createFileUrl(it.title)) }
                                id("title") { text(createFileTitle(it)) }
                                id("url") { text(it.url) }
                            })
                            // Retry row — taps navigate to the source URL
                            // and the download listener re-offers it.
                            appendChild(repeatableElement.clone {
                                tag("a") { attr("href", it.url) }
                                id("title") { text(application.getString(R.string.downloads_retry)) }
                                id("url") { text(it.url) }
                            })
                        }
                    }
                }
            }
        }
        .map { content -> Pair(createDownloadsPageFile(), content) }
        .doOnSuccess { (page, content) ->
            FileWriter(page, false).use { it.write(content) }
        }
        .map { (page, _) -> "$FILE$page" }


    private fun createDownloadsPageFile(): File = File(application.filesDir, FILENAME)

    private fun createFileUrl(fileName: String): String = "$FILE${userPreferences.downloadDirectory}/$fileName"

    private fun createFileTitle(downloadItem: DownloadEntry): String {
        val contentSize = if (downloadItem.contentSize.isNotBlank()) {
            "[${downloadItem.contentSize}]"
        } else {
            ""
        }

        return "${downloadItem.title} $contentSize"
    }

    companion object {

        const val FILENAME = "downloads.html"

    }

}
