// SPDX-License-Identifier: MPL-2.0
// Copyright 2020 CookieJarApps and Arktos contributors

package com.arktosbrowser.mobile.popup

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import com.arktosbrowser.mobile.translate.TranslateBridge
import java.util.Locale
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.constraintlayout.widget.ConstraintLayout
import com.arktosbrowser.mobile.AppTheme
import com.arktosbrowser.mobile.IncognitoActivity
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.adblock.allowlist.AllowListModel
import com.arktosbrowser.mobile.browser.MenuDividerClass
import com.arktosbrowser.mobile.browser.MenuItemClass
import com.arktosbrowser.mobile.browser.SiteOverrides
import com.arktosbrowser.mobile.browser.SiteSettings
import com.arktosbrowser.mobile.browser.TabsManager
import com.arktosbrowser.mobile.browser.activity.BrowserActivity
import com.arktosbrowser.mobile.controller.UIController
import com.arktosbrowser.mobile.database.Bookmark
import com.arktosbrowser.mobile.database.HistoryEntry
import com.arktosbrowser.mobile.download.DownloadsActivity
import com.arktosbrowser.mobile.download.DownloadSettings
import com.arktosbrowser.mobile.download.DownloadSettingsActivity
import com.arktosbrowser.mobile.di.injector
import com.arktosbrowser.mobile.dialog.BrowserDialog
import com.arktosbrowser.mobile.dialog.DialogItem
import com.arktosbrowser.mobile.engine.Engines
import com.arktosbrowser.mobile.engine.PageAttributes
import com.arktosbrowser.mobile.extensions.color
import com.arktosbrowser.mobile.extensions.copyToClipboard
import com.arktosbrowser.mobile.rust.ArktosCore
import com.arktosbrowser.mobile.extensions.drawable
import com.arktosbrowser.mobile.extensions.snackbar
import com.arktosbrowser.mobile.history.HistoryActivity
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.reading.activity.ReadingActivity
import com.arktosbrowser.mobile.settings.activity.SettingsActivity
import com.arktosbrowser.mobile.utils.IntentUtils
import com.arktosbrowser.mobile.utils.Utils
import com.arktosbrowser.mobile.utils.isSpecialUrl
import io.github.rosemoe.sora.widget.CodeEditor
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import javax.inject.Inject
import kotlin.math.roundToInt


class PopUpClass {
    private var list: ListView? = null
    private var uiController: UIController? = null

    @Inject
    lateinit var userPreferences: UserPreferences

    @Inject
    lateinit var tabsManager: TabsManager

    @Inject
    lateinit var allowListModel: AllowListModel

    @Inject
    lateinit var siteSettings: SiteSettings

    @Inject
    lateinit var translateBridge: TranslateBridge

    //PopupWindow display method
    fun showPopupWindow(view: View, activity: BrowserActivity) {
        view.context.injector.inject(this)

        val inflater = view.context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val popupView: View?

        if(userPreferences.bottomBar) popupView = inflater.inflate(R.layout.toolbar_menu_btm, null)
        else popupView = inflater.inflate(R.layout.toolbar_menu, null)
        
        val r = view.context.resources

        val px = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 228f, r.displayMetrics).roundToInt()

        val focusable = true
        uiController = view.context as UIController

        val popupWindow = PopupWindow(popupView, px, LinearLayout.LayoutParams.WRAP_CONTENT, focusable)
        popupWindow.isOutsideTouchable = true
        popupWindow.isFocusable = true
        popupWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        var relView = popupView.findViewById<RelativeLayout>(R.id.toolbar_menu)

        if(activity.isIncognito()){
            popupView.findViewById<ConstraintLayout>(R.id.transparent_container).setBackgroundResource(R.drawable.toolbar_dark)
        }
        else{
            when(userPreferences.resolvedTheme(activity)){
                AppTheme.DARK, AppTheme.ARKTOS_DARK -> popupView.findViewById<ConstraintLayout>(R.id.transparent_container).setBackgroundResource(R.drawable.toolbar_dark)
                AppTheme.BLACK -> popupView.findViewById<ConstraintLayout>(R.id.transparent_container).setBackgroundResource(R.drawable.toolbar_black)
                else -> {}
            }
        }

        val currentView = activity.tabsManager.currentTab
        val currentUrl = uiController!!.getTabModel().currentTab?.url

        popupView.findViewById<ImageButton>(R.id.back_option).setOnClickListener {
            currentView?.goBack()
        }
        popupView.findViewById<ImageButton>(R.id.forward_option).setOnClickListener {
            currentView?.goForward()
        }
        popupView.findViewById<ImageButton>(R.id.close_option).setOnClickListener {
            if(Build.VERSION.SDK_INT >= 21){
                activity.finishAndRemoveTask()
            }
            else{
                activity.finish()
            }
        }

        val intent = currentUrl?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)) }
        val packageManager: PackageManager = activity.packageManager
        val resolved = intent?.resolveActivity(packageManager)
        if (resolved == null || resolved.packageName == activity.applicationContext.packageName || currentUrl.isSpecialUrl()) {
            popupView.findViewById<ImageButton>(R.id.open_in_app).visibility = View.GONE
        }

        popupView.findViewById<ImageButton>(R.id.open_in_app).setOnClickListener {
            intent ?: return@setOnClickListener
            val components = arrayOf(ComponentName(activity, BrowserActivity::class.java))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                activity.startActivity(Intent.createChooser(intent, null).putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS,components))
            else activity.startActivity(intent)
            popupWindow.dismiss()
        }

        popupView.findViewById<ImageButton>(R.id.bookmark_option).setOnClickListener {
            if (currentUrl != null && currentView != null) {
                val bookmark = Bookmark.Entry(currentUrl, currentView.title, 0, Bookmark.Folder.Root)
                activity.bookmarksDialogBuilder.showAddBookmarkDialog(activity, uiController!!, bookmark)
            }
            popupWindow.dismiss()
        }

        val container =  popupView.findViewById<ConstraintLayout>(R.id.transparent_container)

        if(userPreferences.navbar){
            popupView.findViewById<LinearLayout>(R.id.topBar).visibility = View.GONE
            val noTopMenu = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 56f, r.displayMetrics).roundToInt()
           container.setPadding(container.paddingLeft, container.paddingTop - noTopMenu, container.paddingRight, container.paddingBottom)
        }

        // Per-site JS/cookie quick-toggles (#305): only offered when the
        // current page has a real host to key the override on.
        val hasSiteHost = SiteOverrides.siteHost(currentUrl) != null
        val jsToggleTitle = if (siteSettings.javaScriptEnabled(currentUrl)) {
            R.string.block_javascript
        } else {
            R.string.allow_javascript
        }
        val cookieToggleTitle = if (siteSettings.cookiesEnabled(currentUrl)) {
            R.string.block_cookies_site
        } else {
            R.string.allow_cookies_site
        }

        val menu = mutableListOf(
                MenuDividerClass(),
                MenuItemClass("new_tab", R.string.action_new_tab, R.drawable.ic_round_add, true),
                MenuItemClass("new_private_tab", R.string.action_incognito, R.drawable.incognito_mode, true),
                MenuDividerClass(),
                MenuItemClass("share", R.string.action_share, R.drawable.ic_share_black, currentUrl != null && !currentUrl.isSpecialUrl()),
                MenuItemClass("open_in_app", R.string.open_in_app, R.drawable.ic_round_open_in_new, userPreferences.navbar && !activity.isIncognito() && resolved != null && resolved.packageName != activity.applicationContext.packageName && !currentUrl.isSpecialUrl()),
                MenuItemClass("translate", R.string.translator, R.drawable.translate, userPreferences.translateExtension),
                MenuItemClass("print", R.string.action_print, R.drawable.ic_round_print, currentView != null),
                MenuDividerClass(),
                MenuItemClass("history", R.string.action_history, R.drawable.ic_history, true),
                MenuItemClass("bookmarks", R.string.action_bookmarks, R.drawable.ic_action_star, true),
                MenuItemClass("downloads", R.string.action_downloads, R.drawable.ic_file_download_black, true),
                MenuDividerClass(),
                MenuItemClass("find_in_page", R.string.action_find, R.drawable.ic_search, currentView != null),
                MenuItemClass("copy_link", R.string.dialog_copy_link, R.drawable.ic_content_copy_black, currentUrl != null && !currentUrl.isSpecialUrl()),
                MenuItemClass("reading_mode", R.string.reading_mode, R.drawable.ic_action_reading, currentUrl != null && !currentUrl.isSpecialUrl()),
                MenuDividerClass(),
                MenuItemClass("toggle_javascript", jsToggleTitle, R.drawable.ic_baseline_code, hasSiteHost),
                MenuItemClass("toggle_cookies", cookieToggleTitle, R.drawable.ic_round_storage, hasSiteHost),
                MenuItemClass("page_tools", R.string.dialog_tools_title, R.drawable.ic_page_tools, currentView != null),
                MenuItemClass("add_to_homepage", R.string.action_add_to_homescreen, R.drawable.ic_round_smartphone, currentView != null && currentView.url.isNotBlank() && !currentView.url.isSpecialUrl()),
                MenuItemClass("settings", R.string.settings, R.drawable.ic_round_settings, true),
                MenuItemClass("close_app", R.string.close_app, R.drawable.ic_action_delete, true)
        )

        val incognitoMenu = mutableListOf(
                MenuItemClass("new_tab", R.string.action_new_tab, R.drawable.ic_round_add, true),
                MenuDividerClass(),
                MenuItemClass("print", R.string.action_print, R.drawable.ic_round_print, currentView != null),
                MenuItemClass("find_in_page", R.string.action_find, R.drawable.ic_search, currentView != null),
                MenuItemClass("copy_link", R.string.dialog_copy_link, R.drawable.ic_content_copy_black, currentUrl != null && !currentUrl.isSpecialUrl()),
                MenuItemClass("add_to_homepage", R.string.action_add_to_homescreen, R.drawable.ic_round_smartphone, currentView != null && currentView.url.isNotBlank() && !currentView.url.isSpecialUrl()),
                MenuDividerClass(),
                MenuItemClass("bookmarks", R.string.action_bookmarks, R.drawable.ic_action_star, true),
                MenuItemClass("reading_mode", R.string.reading_mode, R.drawable.ic_action_reading, currentUrl != null && !currentUrl.isSpecialUrl()),
                MenuDividerClass(),
                MenuItemClass("toggle_javascript", jsToggleTitle, R.drawable.ic_baseline_code, hasSiteHost),
                MenuItemClass("toggle_cookies", cookieToggleTitle, R.drawable.ic_round_storage, hasSiteHost),
                MenuDividerClass(),
                MenuItemClass("settings", R.string.settings, R.drawable.ic_round_settings, true),
                MenuItemClass("close_app", R.string.close_app, R.drawable.ic_action_delete, true),
                MenuItemClass("exit_private", R.string.quit_private, R.drawable.incognito_mode, true)
        )

        val finalMenu = if(activity.isIncognito()) incognitoMenu else menu
        finalMenu.removeAll { it is MenuItemClass && !it.enabled }
        if (userPreferences.bottomBar) finalMenu.reverse()

        //Set the location of the window on the screen
        if (userPreferences.bottomBar) {
            popupWindow.animationStyle = R.style.ToolbarAnimReverse
            popupWindow.showAtLocation(view, Gravity.BOTTOM or Gravity.END, 0, 0)
            relView.gravity = Gravity.BOTTOM
        } else {
            popupWindow.animationStyle = R.style.ToolbarAnim
            popupWindow.showAtLocation(view, Gravity.TOP or Gravity.END, 0, 0)
        }

        val adapter = MenuItemAdapter(view.context, finalMenu)
        list = popupView.findViewById(R.id.menuList)
        list?.adapter = adapter
        list?.setOnItemClickListener { parent, listView, position, id ->
            if(finalMenu[position] !is MenuItemClass){
                return@setOnItemClickListener
            }

            when((finalMenu[position] as MenuItemClass).id){
                "new_tab" -> uiController!!.newTabButtonClicked() // 0 - New tab
                "new_private_tab" -> view.context.startActivity(Intent(view.context, IncognitoActivity::class.java)) // 1 - New incognito tab
                "share" -> IntentUtils(activity).shareUrl(currentUrl, currentView?.title) // 2 - Share
                "print" -> currentView?.createWebPagePrint() // 3 - Print
                "history" -> view.context.startActivity(Intent(view.context, HistoryActivity::class.java)) // 4 - History
                "downloads" -> view.context.startActivity(
                    if (DownloadSettings.externalManager(view.context))
                        DownloadSettingsActivity.externalIntent()
                    else Intent(view.context, DownloadsActivity::class.java)
                ) // 5 - Download
                "find_in_page" -> activity.findInPage() // 6 - Find in Page
                "copy_link" -> {
                    if (currentUrl != null && !currentUrl.isSpecialUrl()) { // 7 - Copy link
                        activity.clipboardManager.copyToClipboard(ArktosCore.stripUrl(currentUrl))
                        activity.snackbar(R.string.message_link_copied)
                    }
                }
                "add_to_homepage" -> {
                    if (currentView != null
                            && currentView.url.isNotBlank()
                            && !currentView.url.isSpecialUrl()) { // 8 - Add to Homepage
                        HistoryEntry(currentView.url, currentView.title).also {
                            Utils.createShortcut(activity, it, currentView.favicon ?: activity.webPageBitmap!!)
                        }
                    }
                }
                "bookmarks" -> activity.openBookmarksDrawer() // 9 - Bookmarks
                "reading_mode" -> {
                    if (currentUrl != null) { // 10 - Reading mode
                        ReadingActivity.launch(view.context, currentUrl, false)
                    }
                }
                "toggle_javascript" -> {
                    currentView?.let { tab ->
                        val enabled = siteSettings.toggleJavaScript(tab.url)
                        tab.setPageAttribute(PageAttributes.JAVASCRIPT_ENABLED, enabled)
                        tab.reload()
                    }
                }
                "toggle_cookies" -> {
                    currentView?.let { tab ->
                        val enabled = siteSettings.toggleCookies(tab.url)
                        Engines.defaultProfile.acceptsCookies = enabled
                        tab.reload()
                    }
                }
                "page_tools" -> {
                    val currentTab = activity.tabsManager.currentTab ?: return@setOnItemClickListener
                    val isAllowedAds = allowListModel.isUrlAllowedAds(currentTab.url)
                    val whitelistString = if (isAllowedAds) {
                        R.string.dialog_adblock_enable_for_site
                    } else {
                        R.string.dialog_adblock_disable_for_site
                    }
                    val jsEnabledString = if (siteSettings.javaScriptEnabled(currentTab.url)) {
                        R.string.block_javascript
                    } else {
                        R.string.allow_javascript
                    }


                    BrowserDialog.showWithIcons(activity, activity.getString(R.string.dialog_tools_title),
                        DialogItem(
                            icon = activity.drawable(R.drawable.ic_action_desktop),
                            title = R.string.dialog_toggle_desktop
                        ) {
                            activity.tabsManager.currentTab?.apply {
                                toggleDesktopUA()
                                reload()
                                // TODO add back drawer closing
                            }
                        },
                        DialogItem(
                            icon = activity.drawable(R.drawable.ic_page_tools),
                            title = R.string.inspect

                        ) {
                            val builder = AlertDialog.Builder(activity)
                            val inflater = activity.layoutInflater
                            builder.setTitle(R.string.inspect)
                            val dialogLayout = inflater.inflate(R.layout.dialog_edit_text, null)
                            val editText = dialogLayout.findViewById<EditText>(R.id.dialog_edit_text)
                            builder.setView(dialogLayout)
                            builder.setPositiveButton("OK") { dialogInterface, i -> currentTab.loadUrl("javascript:(function() {" + editText.text.toString() + "})()") }
                            builder.show()

                        }, DialogItem(
                            icon = activity.drawable(R.drawable.ic_round_storage),
                            title = R.string.edit_cookies
                        ) {

                            val profile = Engines.defaultProfile
                            if (profile.getCookie(currentTab.url) != null) {
                                val builder = MaterialAlertDialogBuilder(activity)
                                val inflater = activity.layoutInflater
                                builder.setTitle(R.string.edit_cookies)
                                val dialogLayout = inflater.inflate(R.layout.dialog_multi_line, null)
                                val editText = dialogLayout.findViewById<EditText>(R.id.dialog_multi_line)
                                editText.setText(profile.getCookie(currentTab.url))
                                builder.setView(dialogLayout)
                                builder.setPositiveButton("OK") { dialogInterface, i ->
                                    val cookiesList = editText.text.toString().split(";")
                                    cookiesList.forEach { item ->
                                        profile.setCookie(currentTab.url, item)
                                    }
                                }
                                builder.show()
                            }

                        },
                        DialogItem(
                            icon = activity.drawable(R.drawable.ic_baseline_code),
                            title = R.string.page_source

                        ) {
                            currentTab.runJavaScript("""(function() {
                        return "<html>" + document.getElementsByTagName('html')[0].innerHTML + "</html>";
                     })()""".trimMargin()) {
                                // Hacky workaround for weird WebView encoding bug
                                var name = it?.replace("\\u003C", "<")
                                name = name?.replace("\\n", System.getProperty("line.separator").toString())
                                name = name?.replace("\\t", "")
                                name = name?.replace("\\\"", "\"")
                                name = name?.substring(1, name.length - 1);

                                val builder = MaterialAlertDialogBuilder(activity)
                                val inflater = activity.layoutInflater
                                builder.setTitle(R.string.page_source)
                                val dialogLayout = inflater.inflate(R.layout.dialog_view_source, null)
                                val editText = dialogLayout.findViewById<CodeEditor>(R.id.dialog_multi_line)
                                editText.setText(name)
                                builder.setView(dialogLayout)
                                builder.setPositiveButton("OK") { _, _ ->
                                    editText.setText(editText.text.toString().replace("\'", "\\\'"));
                                    currentTab.runJavaScript(
    "document.documentElement.innerHTML = " + org.json.JSONObject.quote(editText.text.toString()) + ";"
    ) {}
                                }
                                builder.show()
                            }
                        },
                        DialogItem(
                            icon = activity.drawable(R.drawable.ic_block),
                            colorTint = activity.color(R.color.error_red).takeIf { isAllowedAds },
                            title = whitelistString,
                            isConditionMet = !currentTab.url.isSpecialUrl()
                        ) {
                            if (isAllowedAds) {
                                allowListModel.removeUrlFromAllowList(currentTab.url)
                            } else {
                                allowListModel.addUrlToAllowList(currentTab.url)
                            }
                            activity.tabsManager.currentTab?.reload()
                        }, DialogItem(
                            icon = activity.drawable(R.drawable.ic_action_delete),
                            title = jsEnabledString,
                            isConditionMet = SiteOverrides.siteHost(currentTab.url) != null
                        ) {
                            val enabled = siteSettings.toggleJavaScript(currentTab.url)
                            currentTab.setPageAttribute(PageAttributes.JAVASCRIPT_ENABLED, enabled)
                            activity.tabsManager.currentTab?.reload()
                        }
                    ) // 14 - Page Tools
                }
                "settings" -> {
                    val settings = Intent(view.context, SettingsActivity::class.java) // 11 - Settings
                    view.context.startActivity(settings)
                }
                "close_app" -> activity.closeApp()
                "exit_private" -> {
                    view.context.startActivity(Intent(view.context, BrowserActivity::class.java))
                    activity.finish()
                }
                "translate" -> {
                    val target = activity.resources.configuration.locales[0]?.language
                        ?: Locale.getDefault().language
                    currentView?.let { translateBridge.translatePage(it, activity, target) }
                }
                "open_in_app" -> {
                    intent?.let {
                        val components = arrayOf(ComponentName(activity, BrowserActivity::class.java))
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                            activity.startActivity(Intent.createChooser(it, null).putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS,components))
                        else activity.startActivity(it)
                    }
                    popupWindow.dismiss() // 13 - Open in App
                }
            }
            popupWindow.dismiss()
        }
    }
}