package com.arktosbrowser.mobile.preference

import android.content.Context
import com.arktosbrowser.mobile.AppTheme
import com.arktosbrowser.mobile.constant.DEFAULT_ENCODING
import com.arktosbrowser.mobile.constant.SCHEME_HOMEPAGE
import com.arktosbrowser.mobile.device.ScreenSize
import com.arktosbrowser.mobile.di.UserPrefs
import com.arktosbrowser.mobile.extensions.isNightMode
import com.arktosbrowser.mobile.isDarkWebAddonEnabled
import com.arktosbrowser.mobile.resolve
import com.arktosbrowser.mobile.preference.delegates.*
import com.arktosbrowser.mobile.network.ReferrerPolicy
import com.arktosbrowser.mobile.search.SearchEngineProvider
import com.arktosbrowser.mobile.search.Suggestions
import com.arktosbrowser.mobile.search.engine.GoogleSearch
import com.arktosbrowser.mobile.utils.FileUtils
import com.arktosbrowser.mobile.view.RenderingMode
import android.content.SharedPreferences
import com.arktosbrowser.mobile.browser.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's preferences.
 */
@Singleton
class UserPreferences @Inject constructor(
    @UserPrefs preferences: SharedPreferences,
    screenSize: ScreenSize
) {
    var siteBlockChoice by preferences.enumPreference(SITE_BLOCK, SiteBlockChoice.NONE)
    var siteBlockNames by preferences.stringPreference(USE_SITE_BLOCK, "")

    /**
     * True if strict safe-search filtering is forced on supported search
     * engines (parental controls, upstream #327). Enforced by
     * [com.arktosbrowser.mobile.search.SafeSearch] at the page-load layer,
     * so the parameter survives edits to the loaded URL.
     */
    var safeSearchForced by preferences.booleanPreference(SAFE_SEARCH, false)

    var javaScriptChoice by preferences.enumPreference(USE_JS_BLOCK, JavaScriptChoice.NONE)
    var javaScriptBlocked by preferences.stringPreference(BLOCK_JS, "")

    var navbarColChoice by preferences.enumPreference(NAVBAR_COL, ChooseNavbarCol.NONE)

    var drawerOffset by preferences.intPreference(DRAWER_OFFSET, 0)

    var passwordChoice by preferences.enumPreference(PASSWORD, PasswordChoice.NONE)
    var passwordText by preferences.stringPreference(USE_PASSWORD, "")

    var passwordChoiceLock by preferences.enumPreference(PASSWORD_LOCK, PasswordChoice.NONE)
    var passwordTextLock by preferences.stringPreference(USE_PASSWORD_LOCK, "")

    /**
     * SEC01 — browser-wide app lock. PIN/password credentials are
     * stored as an argon2id hash in [passwordTextLock]; the legacy
     * `passwordLock=custom` toggle is migrated to
     * [AppLockMethod.PASSWORD] below.
     */
    var appLockMethod by preferences.enumPreference(APP_LOCK_METHOD, AppLockMethod.NONE)

    /** Minutes of background time before the lock re-engages; 0 = immediate. */
    var appLockRelockMinutes by preferences.intPreference(APP_LOCK_DELAY_MIN, 0)

    init {
        if (passwordChoiceLock == PasswordChoice.CUSTOM &&
            appLockMethod == AppLockMethod.NONE
        ) {
            appLockMethod = AppLockMethod.PASSWORD
        }
    }
    /**
     * True if Web RTC is enabled in the browser, false otherwise.
     */
    var webRtcEnabled by preferences.booleanPreference(WEB_RTC, true)

    var blockMalwareEnabled by preferences.booleanPreference(BLOCK_MALWARE, true)

    /**
     * True if requests from public pages to private-IP/localhost hosts
     * should be blocked (upstream #244). Enforced by the Rust
     * blocklist engine at the `shouldInterceptRequest` seam.
     */
    var blockLanRequestsEnabled by preferences.booleanPreference(BLOCK_LAN_REQUESTS, true)

    var startPageThemeEnabled by preferences.booleanPreference(START_THEME, true)

    var tabsToForegroundEnabled by preferences.booleanPreference(FOREGROUND, true)

    var stackFromBottom by preferences.booleanPreference(STACK_FROM_BOTTOM, false)

    /**
     * True if the bookmark drawer lists entries newest-first (upstream
     * #156); false keeps the position/alphabetical order.
     */
    var bookmarkSortByDate by preferences.booleanPreference(BOOKMARK_SORT_DATE, false)

    /**
     * Last folder shown in the bookmark drawer — the #308 prefill
     * for "save new bookmark into the folder I'm in".
     */
    var lastBookmarkFolder by preferences.stringPreference(LAST_BOOKMARK_FOLDER, "")

    /**
     * True if the browser should block ads, false otherwise.
     */
    var adBlockEnabled by preferences.booleanPreference(BLOCK_ADS, true)

    var cookieBlockEnabled by preferences.booleanPreference(COOKIE_BLOCK, false)

    /**
     * Remote-list consent (PREFS01) — the mobile mirror of the
     * desktop `AdBlockManager::remoteListsAllowed`. True while remote
     * filter/rank-list downloads may happen automatically (the 7-day
     * staleness path); manual refresh actions always work regardless.
     * Defaults true to preserve the pre-PREFS01 behavior where an
     * enabled ad blocker was the implicit consent signal.
     */
    var remoteListsConsentEnabled by preferences.booleanPreference(REMOTE_LISTS_CONSENT, true)

    /**
     * Per-source participation for the two site-rank lists (PREFS01).
     * A disabled source is skipped by rank-list updates and its
     * committed drop file is removed so it stops contributing ranks.
     */
    var trancoListEnabled by preferences.booleanPreference(TRANCO_LIST_ENABLED, true)
    var majesticListEnabled by preferences.booleanPreference(MAJESTIC_LIST_ENABLED, true)

    var preferHTTPSenabled by preferences.booleanPreference(PREFER_HTTPS, false)

    /**
     * SEC04 — strict HTTPS-only mode: cleartext `http://` navigations
     * are answered with a warning interstitial (upgrade / go back /
     * proceed) rather than being silently upgraded. See
     * [com.arktosbrowser.mobile.network.HttpsOnlyGate].
     */
    var forceHTTPSenabled by preferences.booleanPreference(FORCE_HTTPS, false)

    /**
     * SEC03 — true to reject mixed content (HTTP subresources on HTTPS
     * pages) in normal browsing. Incognito tabs always reject it.
     */
    var blockMixedContentEnabled by preferences.booleanPreference(BLOCK_MIXED_CONTENT, true)

    /**
     * SEC09 — true to honour the Guardian-Project panic TRIGGER intent
     * (external panic-button apps wipe the session). Any installed app
     * can send it, so users without a panic app can close that surface
     * here.
     */
    var panicTriggerEnabled by preferences.booleanPreference(PANIC_TRIGGER, true)

    /**
     * SEC07 — true to set `FLAG_SECURE` on every app window, blocking
     * screenshots and recents thumbnails app-wide. Incognito windows
     * are always secured regardless of this toggle; see
     * [com.arktosbrowser.mobile.browser.SecureWindowPolicy].
     */
    var secureWindowsEnabled by preferences.booleanPreference(SECURE_WINDOWS, false)

    /**
     * SEC12 — true to render internationalized domain names in the URL
     * bar in their punycode (`xn--`) form instead of Unicode, so
     * homograph/lookalike hosts can't masquerade as ASCII brands.
     * Display-only — the loaded URL is never modified. See
     * [com.arktosbrowser.mobile.rust.ArktosCore.idnDisplay].
     */
    var showPunycodeEnabled by preferences.booleanPreference(SHOW_PUNYCODE, true)

    /**
     * INCOG03 — true to let the persistent "private tabs running"
     * notification appear on the lock screen. Defaults false so the
     * reminder stays hidden ([NotificationCompat.VISIBILITY_SECRET])
     * while the device is locked; see
     * [com.arktosbrowser.mobile.notifications.IncognitoNotification].
     */
    var incognitoNotificationVisible by preferences.booleanPreference(INCOGNITO_NOTIFICATION_VISIBLE, false)

    /**
     * SEC13 — referrer policy for top-level page navigations:
     * [ReferrerPolicy.Mode.SAME_ORIGIN] sends the Referer only when the
     * navigation stays on the source page's origin, [ReferrerPolicy.Mode.STRIP]
     * never sends it. See [com.arktosbrowser.mobile.network.ReferrerPolicy].
     */
    var referrerMode by preferences.enumPreference(REFERRER_MODE, ReferrerPolicy.Mode.DEFAULT)

    /**
     * True if the browser should block images from being loaded, false otherwise.
     */
    var blockImagesEnabled by preferences.booleanPreference(BLOCK_IMAGES, false)

    /**
     * True if the browser should clear the browser cache when the app is exited, false otherwise.
     */
    var clearCacheExit by preferences.booleanPreference(CLEAR_CACHE_EXIT, false)

    /**
     * True if the browser should allow websites to store and access cookies, false otherwise.
     */
    var cookiesEnabled by preferences.booleanPreference(COOKIES, true)

    /**
     * The folder into which files will be downloaded.
     */
    var downloadDirectory by preferences.stringPreference(DOWNLOAD_DIRECTORY, FileUtils.DEFAULT_DOWNLOAD_PATH)

    /**
     * True if the browser should hide the navigation bar when scrolling, false if it should be
     * immobile.
     */
    var fullScreenEnabled by preferences.booleanPreference(FULL_SCREEN, true)

    /**
     * True if the system status bar should be hidden throughout the app, false if it should be
     * visible.
     */
    var hideStatusBarEnabled by preferences.booleanPreference(HIDE_STATUS_BAR, false)

    /**
     * The URL of the selected homepage.
     */
    var homepage by preferences.stringPreference(HOMEPAGE, SCHEME_HOMEPAGE)

    /**
     * True if cookies should be enabled in incognito mode, false otherwise.
     *
     * WARNING: Cookies will be shared between regular and incognito modes if this is enabled.
     */
    var incognitoCookiesEnabled by preferences.booleanPreference(INCOGNITO_COOKIES, false)

    /**
     * True if the browser should allow execution of javascript, false otherwise.
     */
    var javaScriptEnabled by preferences.booleanPreference(JAVASCRIPT, true)

    /**
     * Per-site JavaScript overrides (upstream #305), serialized as
     * `host=0,other=1` pairs keyed on hostname. An entry wins over the
     * global [javaScriptEnabled] switch and the [javaScriptChoice] site
     * list; absent entries inherit both.
     */
    var perSiteJavaScriptOverrides by preferences.stringPreference(PER_SITE_JS_OVERRIDES, "")

    /**
     * Per-site cookie overrides (upstream #305), serialized like
     * [perSiteJavaScriptOverrides]. An entry wins over the global
     * [cookiesEnabled] switch.
     */
    var perSiteCookieOverrides by preferences.stringPreference(PER_SITE_COOKIE_OVERRIDES, "")

    /**
     * True if the device location should be accessible by websites, false otherwise.
     *
     * NOTE: If this is enabled, permission will still need to be granted on a per-site basis.
     */
    var locationEnabled by preferences.booleanPreference(LOCATION, false)

    /**
     * True if the browser should load pages zoomed out instead of zoomed in so that the text is
     * legible, false otherwise.
     */
    var overviewModeEnabled by preferences.booleanPreference(OVERVIEW_MODE, true)

    /**
     * True if the browser should allow websites to open new windows, false otherwise.
     */
    var popupsEnabled by preferences.booleanPreference(POPUPS, true)

    /**
     * True if the app should remember which browser tabs were open and restore them if the browser
     * is automatically closed by the system.
     */
    var restoreLostTabsEnabled by preferences.booleanPreference(RESTORE_LOST_TABS, true)

    /**
     * True if the browser should save form input, false otherwise.
     */
    var savePasswordsEnabled by preferences.booleanPreference(SAVE_PASSWORDS, true)

    /**
     * The index of the chosen search engine.
     *
     * @see SearchEngineProvider
     */
    var searchChoice by preferences.intPreference(SEARCH, 1)

    /**
     * The custom URL which should be used for making searches.
     */
    var searchUrl by preferences.stringPreference(SEARCH_URL, GoogleSearch().queryUrl)

    /**
     * True if the browser should attempt to reflow the text on a web page after zooming in or out
     * of the page.
     */
    var textReflowEnabled by preferences.booleanPreference(TEXT_REFLOW, false)

    /**
     * The index of the text size that should be used in the browser.
     */
    var textSize by preferences.intPreference(TEXT_SIZE, TextZoom.DEFAULT_INDEX)

    /**
     * True if the browser should fit web pages to the view port, false otherwise.
     */
    var useWideViewPortEnabled by preferences.booleanPreference(USE_WIDE_VIEWPORT, true)

    /**
     * The index of the user agent choice that should be used by the browser.
     *
     * @see UserPreferences.userAgent
     */
    var userAgentChoice by preferences.intPreference(USER_AGENT, 1)

    /**
     * The custom user agent that should be used by the browser.
     */
    var userAgentString by preferences.stringPreference(USER_AGENT_STRING, "")

    var imageUrlString by preferences.stringPreference(IMAGE_URL, "")

    var whatsNewEnabled by preferences.booleanPreference(WHATS_NEW, true)

    /**
     * VersionCode of the last build that already showed its changelog
     * (CHLOG01). [com.arktosbrowser.mobile.browser.ChangelogGate]
     * compares it against the running build — a mismatch opens the
     * changelog once and this is updated, so it never re-shows.
     */
    var lastChangelogVersionCode by preferences.intPreference(LAST_CHANGELOG_VERSION, 0)



    /**
     * True if the browser should clear the navigation history on app exit, false otherwise.
     */
    var clearHistoryExitEnabled by preferences.booleanPreference(CLEAR_HISTORY_EXIT, false)

    /**
     * True if the browser should clear the browser cookies on app exit, false otherwise.
     */
    var clearCookiesExitEnabled by preferences.booleanPreference(CLEAR_COOKIES_EXIT, false)

    /**
     * The index of the rendering mode that should be used by the browser.
     */
    var renderingMode by preferences.enumPreference(RENDERING_MODE, RenderingMode.NORMAL)

    /**
     * True if third party cookies should be disallowed by the browser, false if they should be
     * allowed.
     */
    var blockThirdPartyCookiesEnabled by preferences.booleanPreference(BLOCK_THIRD_PARTY, false)

    /**
     * True if the browser should extract the theme color from a website and color the UI with it,
     * false otherwise.
     */
    var colorModeEnabled by preferences.booleanPreference(ENABLE_COLOR_MODE, false)

    /**
     * The index of the URL/search box display choice/
     *
     * @see SearchBoxModel
     */
    var urlBoxContentChoice by preferences.enumPreference(URL_BOX_CONTENTS, SearchBoxDisplayChoice.URL)

    /**
     * True if the URL bar should hide the scheme and a leading "www." when
     * displaying the current URL, false to show the full URL. The loaded URL
     * itself is never modified.
     */
    var trimUrlBarEnabled by preferences.booleanPreference(TRIM_URL_BAR, false)

    /**
     * True if the browser should invert the display colors of the web page content, false
     * otherwise.
     */
    var invertColors by preferences.booleanPreference(INVERT_COLORS, false)

    /**
     * The index of the reading mode text size.
     */
    var readingTextSize by preferences.intPreference(READING_TEXT_SIZE, 2)

    var bottomBar by preferences.booleanPreference(BOTTOM_BAR, false)

    var incognito by preferences.booleanPreference(ALWAYS_INCOGNITO, false)


    var forceZoom by preferences.booleanPreference(FORCE_ZOOM, false)
    /**
     * The index of the theme used by the application.
     */
    var useTheme by preferences.enumPreference(THEME, AppTheme.LIGHT)

    /**
     * The text encoding used by the browser.
     */
    var textEncoding by preferences.stringPreference(TEXT_ENCODING, DEFAULT_ENCODING)

    /**
     * True if the web page storage should be cleared when the app exits, false otherwise.
     */
    var clearWebStorageExitEnabled by preferences.booleanPreference(CLEAR_WEB_STORAGE_EXIT, false)

    /**
     * True if the app should use the navigation drawer UI, false if it should use the traditional
     * desktop browser tabs UI.
     */
    var showTabsInDrawer by preferences.booleanPreference(SHOW_TABS_IN_DRAWER, !screenSize.isTablet())

    /**
     * True if the browser should send a do not track (DNT) header with every GET request, false
     * otherwise.
     */
    var doNotTrackEnabled by preferences.booleanPreference(DO_NOT_TRACK, false)

    /**
     * True if the browser should save form data, false otherwise.
     */
    var saveDataEnabled by preferences.booleanPreference(SAVE_DATA, false)



    /**
     * True if the browser should attempt to remove identifying headers in GET requests, false if
     * the default headers should be left along.
     */
    var removeIdentifyingHeadersEnabled by preferences.booleanPreference(IDENTIFYING_HEADERS, false)

    /**
     * True if the bookmarks tab should be on the opposite side of the screen, false otherwise. If
     * the navigation drawer UI is used, the tab drawer will be displayed on the opposite side as
     * well.
     */
    var bookmarksAndTabsSwapped by preferences.booleanPreference(SWAP_BOOKMARKS_AND_TABS, false)

    /**
     * True if the tab and bookmark drawer icons swap positions — the bookmark
     * icon takes the toolbar's start slot while the tab icon moves to the end,
     * and the two icons trade places in the bottom navigation bar. Mirrors
     * [bookmarksAndTabsSwapped], which swaps the drawers themselves.
     */
    var bookmarksAndTabsIconsSwapped by preferences.booleanPreference(SWAP_BOOKMARKS_AND_TABS_ICONS, false)

    /**
     * True if the status bar of the app should always be high contrast, false if it should follow
     * the theme of the app.
     */
    var useBlackStatusBar by preferences.booleanPreference(BLACK_STATUS_BAR, false)

    var showExtraOptions by preferences.booleanPreference(EXTRA, false)

    var suggestionChoice by preferences.enumPreference(SEARCH_SUGGESTIONS_NUM, SuggestionNumChoice.FIVE)

    /**
     * The index of the proxy choice.
     */
    var proxyChoice by preferences.enumPreference(PROXY_CHOICE, ProxyChoice.NONE)

    /**
     * The proxy host used when [proxyChoice] is [ProxyChoice.MANUAL].
     */
    var proxyHost by preferences.stringPreference(USE_PROXY_HOST, "localhost")

    /**
     * The proxy port used when [proxyChoice] is [ProxyChoice.MANUAL].
     */
    var proxyPort by preferences.intPreference(USE_PROXY_PORT, 8118)

    /**
     * The index of the search suggestion choice.
     *
     * @see SearchEngineProvider
     */
    var searchSuggestionChoice by preferences.intPreference(SEARCH_SUGGESTIONS, Suggestions.DUCK.index)

    /**
     * The index of the ad blocking hosts file source.
     */
    var hostsSource by preferences.intPreference(HOSTS_SOURCE, 0)

    /**
     * The local file from which ad blocking hosts should be read, depending on the [hostsSource].
     */
    var hostsLocalFile by preferences.nullableStringPreference(HOSTS_LOCAL_FILE)

    /**
     * The remote URL from which ad blocking hosts should be read, depending on the [hostsSource].
     */
    var hostsRemoteFile by preferences.nullableStringPreference(HOSTS_REMOTE_FILE)

    var darkModeExtension by preferences.booleanPreference(DARK_MODE, false)
    var translateExtension by preferences.booleanPreference(TRANSLATE, false)

    // The color of the main navbar
    var colorNavbar by preferences.intPreference(NAVBAR_COLOR, 0)

    // If this is the first launch of the browser
    var firstLaunch by preferences.booleanPreference(FIRST_LAUNCH, true)

    // Close browser when the last tab is shut
    var closeOnLastTab by preferences.booleanPreference(LAST_TAB, true)

    // Block opening of links in other apps
    var blockIntent by preferences.booleanPreference(INTENT, true)

    // Only clear history, etc on force close when respective options are on
    var onlyForceClose by preferences.booleanPreference(FORCE, true)

    // Max lines of text in drawer
    var drawerLines by preferences.enumPreference(DRAWER_LINES, DrawerLineChoice.THREE)

    // Size of text in drawers
    var drawerSize by preferences.enumPreference(DRAWER_SIZE, DrawerSizeChoice.AUTO)

    // Show SSL warnings
    var ssl by preferences.booleanPreference(SSL, true)

    // Show homepage shortcuts
    var showShortcuts by preferences.booleanPreference(SHOW_SHORTCUTS, true)

    // Homepage shortcuts
    var link1 by preferences.stringPreference(LINK1, "https://github.com/IT-Space-2026/Arktos-Mobile/releases")
    var link2 by preferences.stringPreference(LINK2, "https://it-space.info")
    var link3 by preferences.stringPreference(LINK3, "https://arktos-browser.com")
    var link4 by preferences.stringPreference(LINK4, "https://github.com/IT-Space-2026/Arktos-Mobile/security")

    // API endpoints
    var translationEndpoint by preferences.stringPreference(TRANSLATION_ENDPOINT, "https://libretranslate.de")


    var useThirdPartyDownloaderApps by preferences.booleanPreference(USE_THIRD_PARTY_DOWNLOADER_APPS, false)

    // Show second navbar at the bottom of the screen
    var navbar by preferences.booleanPreference(SECOND_BAR, false)

    // Load all tabs on browser start
    var allTabs by preferences.booleanPreference(ALL_TABS, false)

    // Redirect from AMP sites
    var noAmp by preferences.booleanPreference(NO_AMP, false)

    // Show download dialog before downloading a file
    var showDownloadConfirmation by preferences.booleanPreference(SHOW_DOWNLOAD_CONFIRMATION, true)

    // First launch since v11.1
    var firstLaunch111 by preferences.booleanPreference(FIRST_LAUNCH111, true)

    // Switches between 3 types for the default homepage
    var homepageType by preferences.enumPreference(HOMEPAGE_TYPE, HomepageTypeChoice.DEFAULT)

    /**
     * The concrete theme to apply — resolves [AppTheme.SYSTEM] against the
     * device's current night mode. Never returns [AppTheme.SYSTEM].
     */
    fun resolvedTheme(context: Context): AppTheme = useTheme.resolve(context.isNightMode)

    /**
     * True when web-content darkening is active — the dark-mode addon toggle
     * is on, or the theme follows the system and the system is in night mode.
     */
    fun darkWebAddonEnabled(context: Context): Boolean =
        isDarkWebAddonEnabled(darkModeExtension, useTheme, context.isNightMode)
}

private const val FIRST_LAUNCH = "firstLaunch"
private const val FIRST_LAUNCH111 = "firstLaunch111"
private const val WEB_RTC = "webRtc"
private const val FORCE = "force"
private const val BLOCK_ADS = "AdBlock"
private const val LAST_TAB = "lastTab"
private const val BLOCK_IMAGES = "blockimages"
private const val CLEAR_CACHE_EXIT = "cache"
private const val COOKIES = "cookies"
private const val DOWNLOAD_DIRECTORY = "downloadLocation"
private const val FULL_SCREEN = "fullscreen"
private const val HIDE_STATUS_BAR = "hidestatus"
private const val HOMEPAGE = "home"
private const val INCOGNITO_COOKIES = "incognitocookies"
private const val JAVASCRIPT = "java"
private const val PER_SITE_JS_OVERRIDES = "perSiteJsOverrides"
private const val PER_SITE_COOKIE_OVERRIDES = "perSiteCookieOverrides"
private const val LOCATION = "location"
private const val OVERVIEW_MODE = "overviewmode"
private const val POPUPS = "newwindows"
private const val RESTORE_LOST_TABS = "restoreclosed"
private const val SAVE_PASSWORDS = "passwords"
private const val SEARCH = "search"
private const val SEARCH_URL = "searchurl"
private const val TEXT_REFLOW = "textreflow"
private const val TEXT_SIZE = "textsize"
private const val USE_WIDE_VIEWPORT = "wideviewport"
private const val USER_AGENT = "agentchoose"
private const val USER_AGENT_STRING = "userAgentString"
private const val CLEAR_HISTORY_EXIT = "clearHistoryExit"
private const val CLEAR_COOKIES_EXIT = "clearCookiesExit"
private const val RENDERING_MODE = "renderMode"
private const val BLOCK_THIRD_PARTY = "thirdParty"
private const val ENABLE_COLOR_MODE = "colorMode"
private const val URL_BOX_CONTENTS = "urlContent"
private const val TRIM_URL_BAR = "trimUrlBar"
private const val INVERT_COLORS = "invertColors"
private const val READING_TEXT_SIZE = "readingTextSize"
private const val THEME = "Theme"
private const val TEXT_ENCODING = "textEncoding"
private const val CLEAR_WEB_STORAGE_EXIT = "clearWebStorageExit"
private const val SHOW_TABS_IN_DRAWER = "showTabsInDrawer"
private const val DO_NOT_TRACK = "doNotTrack"
private const val SAVE_DATA = "saveData"
private const val IDENTIFYING_HEADERS = "removeIdentifyingHeaders"
private const val SWAP_BOOKMARKS_AND_TABS = "swapBookmarksAndTabs"
private const val SWAP_BOOKMARKS_AND_TABS_ICONS = "swapBookmarksAndTabsIcons"
private const val BLACK_STATUS_BAR = "blackStatusBar"
private const val PROXY_CHOICE = "proxyChoice"
private const val SEARCH_SUGGESTIONS_NUM = "suggNum"
private const val USE_PROXY_HOST = "useProxyHost"
private const val USE_PROXY_PORT = "useProxyPort"
private const val SEARCH_SUGGESTIONS = "searchSuggestionsChoice"
private const val HOSTS_SOURCE = "hostsSource"
private const val HOSTS_LOCAL_FILE = "hostsLocalFile"
private const val HOSTS_REMOTE_FILE = "hostsRemoteFile"
private const val PREFER_HTTPS = "preferHTTPS"
private const val FORCE_HTTPS = "forceHTTPS"
private const val SITE_BLOCK = "siteBlock"
private const val SAFE_SEARCH = "safeSearch"
private const val NAVBAR_COLOR = "navcol"
private const val USE_SITE_BLOCK = "useSiteBlock"
private const val USE_JS_BLOCK = "useJSblock"
private const val BLOCK_JS = "blockJS"
private const val PASSWORD = "password"
private const val USE_PASSWORD = "usePassword"
private const val USE_PASSWORD_LOCK = "usePasswordLock"
private const val PASSWORD_LOCK = "passwordLock"
private const val APP_LOCK_METHOD = "appLockMethod"
private const val APP_LOCK_DELAY_MIN = "appLockDelayMin"
private const val BLOCK_MALWARE = "blockMalware"
private const val BLOCK_LAN_REQUESTS = "blockLanRequests"
private const val BLOCK_MIXED_CONTENT = "blockMixedContent"
private const val SECURE_WINDOWS = "secureWindows"
private const val SHOW_PUNYCODE = "showPunycode"
private const val INCOGNITO_NOTIFICATION_VISIBLE = "incognitoNotificationVisible"
private const val REFERRER_MODE = "referrerMode"
private const val PANIC_TRIGGER = "panicTrigger"
private const val COOKIE_BLOCK = "blockCookieDialogs"
private const val REMOTE_LISTS_CONSENT = "remoteListsConsent"
private const val TRANCO_LIST_ENABLED = "trancoListEnabled"
private const val MAJESTIC_LIST_ENABLED = "majesticListEnabled"
private const val START_THEME = "startPageTheme"
private const val FOREGROUND = "tabsToForeground"
private const val EXTRA = "showExtraOptions"
private const val BOTTOM_BAR = "bottomBar"
private const val IMAGE_URL = "imageUrl"
private const val WHATS_NEW = "whatsNew"
private const val LAST_CHANGELOG_VERSION = "lastChangelogVersionCode"
private const val ALWAYS_INCOGNITO = "alwaysincognito"
private const val FORCE_ZOOM = "forcezoom"
private const val NAVBAR_COL = "navbarcol"
private const val DARK_MODE = "darkmode"
private const val TRANSLATE = "translate"
private const val INTENT = "stopIntent"
private const val DRAWER_LINES = "lines"
private const val DRAWER_SIZE = "dize"
private const val SSL = "ssl"
private const val LINK1 = "link1"
private const val LINK2 = "link2"
private const val LINK3 = "link3"
private const val LINK4 = "link4"
private const val USE_THIRD_PARTY_DOWNLOADER_APPS = "useThirdPartyDownloaderApps"
private const val ALL_TABS = "allTabs"
private const val SHOW_SHORTCUTS = "showShortcuts"
private const val SECOND_BAR = "secondBar"
private const val NO_AMP = "noAmp"
private const val SHOW_DOWNLOAD_CONFIRMATION = "showDownloadConfirmation"
private const val TRANSLATION_ENDPOINT = "translationEndpoint"
private const val HOMEPAGE_TYPE = "homepageType"
private const val STACK_FROM_BOTTOM = "stackFromBottom"
private const val BOOKMARK_SORT_DATE = "bookmarkSortByDate"
private const val LAST_BOOKMARK_FOLDER = "lastBookmarkFolder"
private const val DRAWER_OFFSET = "drawerOffset"