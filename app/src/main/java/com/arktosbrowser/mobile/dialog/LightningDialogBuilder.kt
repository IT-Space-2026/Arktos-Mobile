package com.arktosbrowser.mobile.dialog

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startActivity
import androidx.core.net.toUri
import com.arktosbrowser.mobile.MainActivity
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.browser.SiteOverrides
import com.arktosbrowser.mobile.browser.SiteSettings
import com.arktosbrowser.mobile.constant.HTTP
import com.arktosbrowser.mobile.controller.UIController
import com.arktosbrowser.mobile.database.Bookmark
import com.arktosbrowser.mobile.database.asFolder
import com.arktosbrowser.mobile.database.bookmark.BookmarkRepository
import com.arktosbrowser.mobile.database.downloads.DownloadsRepository
import com.arktosbrowser.mobile.database.history.HistoryRepository
import com.arktosbrowser.mobile.di.DatabaseScheduler
import com.arktosbrowser.mobile.di.MainScheduler
import com.arktosbrowser.mobile.download.DownloadHandler
import com.arktosbrowser.mobile.extensions.copyToClipboard
import com.arktosbrowser.mobile.rust.ArktosCore
import com.arktosbrowser.mobile.extensions.resizeAndShow
import com.arktosbrowser.mobile.extensions.toast
import com.arktosbrowser.mobile.history.HistoryActivity
import com.arktosbrowser.mobile.html.bookmark.BookmarkPageFactory
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.utils.IntentUtils
import com.arktosbrowser.mobile.utils.isBookmarkUrl
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.Reusable
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.kotlin.subscribeBy
import java.util.Locale
import java.util.Locale.getDefault
import javax.inject.Inject


/**
 * A builder of various dialogs.
 */
@Reusable
class LightningDialogBuilder @Inject constructor(
    private val bookmarkManager: BookmarkRepository,
    private val downloadsModel: DownloadsRepository,
    private val historyModel: HistoryRepository,
    private val userPreferences: UserPreferences,
    private val siteSettings: SiteSettings,
    private val downloadHandler: DownloadHandler,
    private val clipboardManager: ClipboardManager,
    @DatabaseScheduler private val databaseScheduler: Scheduler,
    @MainScheduler private val mainScheduler: Scheduler
) {

    enum class NewTab {
        FOREGROUND,
        BACKGROUND,
        INCOGNITO
    }

    /**
     * Show the appropriated dialog for the long pressed link. It means that we try to understand
     * if the link is relative to a bookmark or is just a folder.
     *
     * @param activity used to show the dialog
     * @param url      the long pressed url
     */
    fun showLongPressedDialogForBookmarkUrl(
        activity: Activity,
        uiController: UIController,
        url: String
    ) {
        if (url.isBookmarkUrl()) {
            // TODO hacky, make a better bookmark mechanism in the future
            val uri = url.toUri()
            val filename = requireNotNull(uri.lastPathSegment) { "Last segment should always exist for bookmark file" }
            val folderTitle = filename.substring(0, filename.length - BookmarkPageFactory.FILENAME.length - 1)
            showBookmarkFolderLongPressedDialog(activity, uiController, folderTitle.asFolder())
        } else {
            bookmarkManager.findBookmarkForUrl(url)
                .subscribeOn(databaseScheduler)
                .observeOn(mainScheduler)
                .subscribe { historyItem ->
                    // TODO: 6/14/17 figure out solution to case where slashes get appended to root urls causing the item to not exist
                    showLongPressedDialogForBookmarkUrl(activity, uiController, historyItem)
                }
        }
    }

    fun showLongPressedDialogForBookmarkUrl(
        activity: Activity,
        uiController: UIController,
        entry: Bookmark.Entry
    ) = BrowserDialog.showWithIcons(activity, activity.resources.getString(R.string.action_bookmarks),
        DialogItem(title = R.string.dialog_open_new_tab, icon = ContextCompat.getDrawable(activity, R.drawable.ic_action_tabs)) {
            uiController.handleNewTab(NewTab.FOREGROUND, entry.url)
        },
        DialogItem(title = R.string.dialog_open_background_tab, icon = ContextCompat.getDrawable(activity, R.drawable.ic_round_open_in_new)) {
            uiController.handleNewTab(NewTab.BACKGROUND, entry.url)
        },
        DialogItem(
            title = R.string.dialog_open_incognito_tab,
            isConditionMet = activity is MainActivity,
                icon = ContextCompat.getDrawable(activity, R.drawable.incognito_mode)
        ) {
            uiController.handleNewTab(NewTab.INCOGNITO, entry.url)
        },
        DialogItem(title = R.string.action_share, icon = ContextCompat.getDrawable(activity, R.drawable.ic_share_black)) {
            IntentUtils(activity).shareUrl(entry.url, entry.title)
        },
        DialogItem(title = R.string.dialog_copy_link, icon = ContextCompat.getDrawable(activity, R.drawable.ic_content_copy_black)) {
            clipboardManager.copyToClipboard(ArktosCore.stripUrl(entry.url))
        },
        DialogItem(title = R.string.dialog_remove_bookmark, icon = ContextCompat.getDrawable(activity, R.drawable.ic_action_delete)) {
            bookmarkManager.deleteBookmark(entry)
                .subscribeOn(databaseScheduler)
                .observeOn(mainScheduler)
                .subscribe { success ->
                    if (success) {
                        uiController.handleBookmarkDeleted(entry)
                    }
                }
        },
        DialogItem(title = R.string.dialog_edit_bookmark, icon = ContextCompat.getDrawable(activity, R.drawable.ic_action_edit)) {
            showEditBookmarkDialog(activity, uiController, entry)
        })

    /**
     * Show the appropriated dialog for the long pressed link.
     *
     * @param activity used to show the dialog
     * @param url      the long pressed url
     */
    // TODO allow individual downloads to be deleted.
    fun showLongPressedDialogForDownloadUrl(
        activity: Activity,
        uiController: UIController,
        url: String
    ) = BrowserDialog.show(activity, R.string.action_downloads,
        DialogItem(title = R.string.dialog_delete_all_downloads) {
            downloadsModel.deleteAllDownloads()
                .subscribeOn(databaseScheduler)
                .observeOn(mainScheduler)
                .subscribe(uiController::handleDownloadDeleted)
        })

    /**
     * Show the add bookmark dialog. Shows a dialog with the title and URL pre-populated.
     */
    fun showAddBookmarkDialog(
        activity: Activity,
        uiController: UIController,
        entry: Bookmark.Entry
    ) {
        val editBookmarkDialog = MaterialAlertDialogBuilder(activity)
        editBookmarkDialog.setTitle(R.string.action_add_bookmark)
        val dialogLayout = View.inflate(activity, R.layout.dialog_edit_bookmark, null)
        val getTitle = dialogLayout.findViewById<EditText>(R.id.bookmark_title)
        getTitle.setText(entry.title)
        val getUrl = dialogLayout.findViewById<EditText>(R.id.bookmark_url)
        getUrl.setText(entry.url)
        val getFolder = dialogLayout.findViewById<AutoCompleteTextView>(R.id.bookmark_folder)
        getFolder.setHint(R.string.folder)
        getFolder.setText(entry.folder.title)

        val ignored = bookmarkManager.getFolderNames()
            .subscribeOn(databaseScheduler)
            .observeOn(mainScheduler)
            .subscribe { folders ->
                val suggestionsAdapter = ArrayAdapter(activity,
                    android.R.layout.simple_dropdown_item_1line, folders)
                getFolder.threshold = 1
                getFolder.setAdapter(suggestionsAdapter)
                editBookmarkDialog.setView(dialogLayout)
                editBookmarkDialog.setPositiveButton(activity.getString(R.string.action_ok)) { _, _ ->
                    val editedItem = Bookmark.Entry(
                        title = getTitle.text.toString(),
                        url = getUrl.text.toString(),
                        folder = getFolder.text.toString().asFolder(),
                        position = entry.position
                    )
                    bookmarkManager.addBookmarkIfNotExists(editedItem)
                        .subscribeOn(databaseScheduler)
                        .observeOn(mainScheduler)
                        .subscribeBy(
                            onSuccess = {
                                uiController.handleBookmarksChange()
                                activity.toast(R.string.message_bookmark_added)
                            }
                        )
                }
                editBookmarkDialog.setNegativeButton(R.string.action_cancel) { _, _ -> }
                editBookmarkDialog.resizeAndShow()
            }
    }

    private fun showEditBookmarkDialog(
        activity: Activity,
        uiController: UIController,
        entry: Bookmark.Entry
    ) {
        val editBookmarkDialog = MaterialAlertDialogBuilder(activity)
        editBookmarkDialog.setTitle(R.string.title_edit_bookmark)
        val dialogLayout = View.inflate(activity, R.layout.dialog_edit_bookmark, null)
        val getTitle = dialogLayout.findViewById<EditText>(R.id.bookmark_title)
        getTitle.setText(entry.title)
        val getUrl = dialogLayout.findViewById<EditText>(R.id.bookmark_url)
        getUrl.setText(entry.url)
        val getFolder = dialogLayout.findViewById<AutoCompleteTextView>(R.id.bookmark_folder)
        getFolder.setHint(R.string.folder)
        getFolder.setText(entry.folder.title)

        bookmarkManager.getFolderNames()
            .subscribeOn(databaseScheduler)
            .observeOn(mainScheduler)
            .subscribe { folders ->
                val suggestionsAdapter = ArrayAdapter(activity,
                    android.R.layout.simple_dropdown_item_1line, folders)
                getFolder.threshold = 1
                getFolder.setAdapter(suggestionsAdapter)
                editBookmarkDialog.setView(dialogLayout)
                editBookmarkDialog.setPositiveButton(activity.getString(R.string.action_ok)) { _, _ ->
                    val editedItem = Bookmark.Entry(
                        title = getTitle.text.toString(),
                        url = getUrl.text.toString(),
                        folder = getFolder.text.toString().asFolder(),
                        position = entry.position
                    )
                    bookmarkManager.editBookmark(entry, editedItem)
                        .subscribeOn(databaseScheduler)
                        .observeOn(mainScheduler)
                        .subscribe(uiController::handleBookmarksChange)
                }
                editBookmarkDialog.resizeAndShow()
            }
    }

    fun showBookmarkFolderLongPressedDialog(
        activity: Activity,
        uiController: UIController,
        folder: Bookmark.Folder
    ) = BrowserDialog.show(activity, R.string.action_folder,
        DialogItem(title = R.string.dialog_rename_folder) {
            showRenameFolderDialog(activity, uiController, folder)
        },
        DialogItem(title = R.string.dialog_remove_folder) {
            bookmarkManager.deleteFolder(folder.title)
                .subscribeOn(databaseScheduler)
                .observeOn(mainScheduler)
                .subscribe {
                    uiController.handleBookmarkDeleted(folder)
                }
        })

    private fun showRenameFolderDialog(
        activity: Activity,
        uiController: UIController,
        folder: Bookmark.Folder
    ) = BrowserDialog.showEditText(activity,
        R.string.title_rename_folder,
        R.string.hint_title,
        folder.title,
        R.string.action_ok) { text ->
        if (text.isNotBlank()) {
            val oldTitle = folder.title
            bookmarkManager.renameFolder(oldTitle, text)
                .subscribeOn(databaseScheduler)
                .observeOn(mainScheduler)
                .subscribe(uiController::handleBookmarksChange)
        }
    }

    fun showLongPressedHistoryLinkDialog(
        activity: HistoryActivity,
        url: String
    ) = BrowserDialog.show(activity, R.string.action_history,
        DialogItem(title = R.string.dialog_open_new_tab) {
            val i = Intent(Intent.ACTION_VIEW, url.toUri())
            i.setData(Uri.parse(url))
            i.setPackage(activity.packageName)
            startActivity(activity as Context, i, null)
        },
        DialogItem(title = R.string.action_share) {
            IntentUtils(activity).shareUrl(url, null)
        },
        DialogItem(title = R.string.dialog_copy_link) {
            clipboardManager.copyToClipboard(ArktosCore.stripUrl(url))
        },
        DialogItem(title = R.string.dialog_remove_from_history) {
            historyModel.deleteHistoryEntry(url)
                .subscribeOn(databaseScheduler)
                .observeOn(mainScheduler)
                .subscribe()
            activity.dataChanged()
        })

    fun showLongPressImageDialog(
        activity: Activity,
        uiController: UIController,
        url: String,
        imageUrl: String,
        userAgent: String
    ) = BrowserDialog.show(activity, url.replace(HTTP, ""),
        DialogItem(title = R.string.dialog_open_new_tab) {
            uiController.handleNewTab(NewTab.FOREGROUND, url)
        },
        DialogItem(title = R.string.dialog_open_background_tab) {
            uiController.handleNewTab(NewTab.BACKGROUND, url)
        },
        DialogItem(
            title = R.string.dialog_open_incognito_tab,
            isConditionMet = activity is MainActivity
        ) {
            uiController.handleNewTab(NewTab.INCOGNITO, url)
        },
        DialogItem(title = R.string.action_share) {
            IntentUtils(activity).shareUrl(url, null)
        },
        DialogItem(title = R.string.dialog_copy_link) {
            clipboardManager.copyToClipboard(ArktosCore.stripUrl(url))
        },
        DialogItem(title = R.string.dialog_download_image) {
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                MimeTypeMap.getFileExtensionFromUrl(
                    imageUrl
                ).lowercase(getDefault())
            )

            if(mimeType != null){
                downloadHandler.onDownloadStart(activity, userPreferences, imageUrl, userAgent, "attachment", mimeType, "", url)
            }
            else{
                downloadHandler.onDownloadStart(activity, userPreferences, imageUrl, userAgent, "attachment", "image/png", "", url)
            }

        })

    fun showLongPressLinkDialog(
        activity: Activity,
        uiController: UIController,
        url: String
    ) = BrowserDialog.show(activity, url,
        DialogItem(title = R.string.dialog_open_new_tab) {
            uiController.handleNewTab(NewTab.FOREGROUND, url, true)
        },
        DialogItem(title = R.string.dialog_open_background_tab) {
            uiController.handleNewTab(NewTab.BACKGROUND, url, true)
        },
        DialogItem(
            title = R.string.dialog_open_incognito_tab,
            isConditionMet = activity is MainActivity
        ) {
            uiController.handleNewTab(NewTab.INCOGNITO, url)
        },
        DialogItem(title = R.string.action_share) {
            IntentUtils(activity).shareUrl(url, null)
        },
        DialogItem(title = R.string.dialog_copy_link) {
            clipboardManager.copyToClipboard(ArktosCore.stripUrl(url))
        },
        // Per-site JS/cookie quick-toggles (#305) keyed on the long-pressed
        // link's host; applied to the open page at once when it is on the
        // same host, otherwise they take effect on the next load there.
        DialogItem(
            title = if (siteSettings.javaScriptEnabled(url)) {
                R.string.block_javascript
            } else {
                R.string.allow_javascript
            },
            isConditionMet = SiteOverrides.siteHost(url) != null
        ) {
            val enabled = siteSettings.toggleJavaScript(url)
            val tab = uiController.getTabModel().currentTab
            if (tab != null && SiteOverrides.siteHost(tab.url) == SiteOverrides.siteHost(url)) {
                tab.webView?.settings?.javaScriptEnabled = enabled
                tab.reload()
            }
        },
        DialogItem(
            title = if (siteSettings.cookiesEnabled(url)) {
                R.string.block_cookies_site
            } else {
                R.string.allow_cookies_site
            },
            isConditionMet = SiteOverrides.siteHost(url) != null
        ) {
            val enabled = siteSettings.toggleCookies(url)
            CookieManager.getInstance().setAcceptCookie(enabled)
            val tab = uiController.getTabModel().currentTab
            if (tab != null && SiteOverrides.siteHost(tab.url) == SiteOverrides.siteHost(url)) {
                tab.reload()
            }
        })

}
