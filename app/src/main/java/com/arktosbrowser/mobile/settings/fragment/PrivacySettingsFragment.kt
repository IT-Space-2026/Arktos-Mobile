// Copyright 2020 CookieJarApps MPL
package com.arktosbrowser.mobile.settings.fragment

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.webkit.WebView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.arktosbrowser.mobile.DeviceCapabilities
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.browser.JavaScriptChoice
import com.arktosbrowser.mobile.browser.PasswordChoice
import com.arktosbrowser.mobile.browser.ProxyChoice
import com.arktosbrowser.mobile.database.history.HistoryRepository
import com.arktosbrowser.mobile.di.DatabaseScheduler
import com.arktosbrowser.mobile.di.MainScheduler
import com.arktosbrowser.mobile.di.injector
import com.arktosbrowser.mobile.dialog.BrowserDialog
import com.arktosbrowser.mobile.dialog.DialogItem
import com.arktosbrowser.mobile.extensions.withSingleChoiceItems
import com.arktosbrowser.mobile.isSupported
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.rust.ArktosCore
import com.arktosbrowser.mobile.utils.ProxyUtils
import com.arktosbrowser.mobile.utils.WebUtils
import com.arktosbrowser.mobile.view.ArktosCookieView
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Scheduler
import java.io.File
import javax.inject.Inject

class PrivacySettingsFragment : AbstractSettingsFragment() {
    @Inject internal lateinit var historyRepository: HistoryRepository
    @Inject internal lateinit var userPreferences: UserPreferences
    @Inject @field:DatabaseScheduler internal lateinit var databaseScheduler: Scheduler
    @Inject @field:MainScheduler internal lateinit var mainScheduler: Scheduler

    var toastMessage: Toast? = null

    private lateinit var proxyChoices: Array<String>

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preference_privacy)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        injector.inject(this)

        proxyChoices = resources.getStringArray(R.array.proxy_choices_array)

        clickableDynamicPreference(
                preference = SETTINGS_PROXY,
                summary = userPreferences.proxyChoice.toSummary(),
                onClick = ::showProxyPicker
        )

        clickableDynamicPreference(
                preference = SETTINGS_USER_AGENT,
                summary = choiceToUserAgent(userPreferences.userAgentChoice),
                onClick = ::showUserAgentChooserDialog
        )

        clickableDynamicPreference(
                preference = SETTINGS_BLOCK_JAVASCRIPT,
                summary = userPreferences.javaScriptChoice.toSummary(),
                onClick = ::showJavaScriptPicker
        )

        switchPreference(
                preference = SETTINGS_JAVASCRIPT,
                isChecked = userPreferences.javaScriptEnabled,
                onCheckChange = { userPreferences.javaScriptEnabled = it }
        )

        switchPreference(
                preference = SETTINGS_BLOCK_MALWARE,
                isChecked = userPreferences.blockMalwareEnabled,
                onCheckChange = { userPreferences.blockMalwareEnabled = it }
        )

        switchPreference(
                preference = SETTINGS_SHOW_SSL,
                isChecked = userPreferences.ssl,
                onCheckChange = { userPreferences.ssl = it }
        )

        val incognitoCheckboxPreference = switchPreference(
                preference = SETTINGS_COOKIES_INCOGNITO,
                isEnabled = !DeviceCapabilities.FULL_INCOGNITO.isSupported,
                isChecked = if (DeviceCapabilities.FULL_INCOGNITO.isSupported) {
                    userPreferences.cookiesEnabled
                } else {
                    userPreferences.incognitoCookiesEnabled
                },
                summary = if (DeviceCapabilities.FULL_INCOGNITO.isSupported) {
                    getString(R.string.incognito_cookies_new)
                } else {
                    null
                },
                onCheckChange = { userPreferences.incognitoCookiesEnabled = it }
        )

        switchPreference(
                preference = SETTINGS_ENABLE_COOKIES,
                isChecked = userPreferences.cookiesEnabled,
                onCheckChange = {
                    userPreferences.cookiesEnabled = it
                    if (DeviceCapabilities.FULL_INCOGNITO.isSupported) {
                        incognitoCheckboxPreference?.isChecked = it
                    }
                }
        )

        val stringArrayPassword = resources.getStringArray(R.array.password_set_array)
        clickableDynamicPreference(
                preference = SETTINGS_APP_LOCK,
                summary = stringArrayPassword[userPreferences.passwordChoiceLock.value],
                onClick = ::showPasswordPicker
        )

        clickablePreference(preference = SETTINGS_CLEARCACHE, onClick = this::clearCache)
        clickablePreference(preference = SETTINGS_CLEARHISTORY, onClick = this::clearHistoryDialog)
        clickablePreference(preference = SETTINGS_CLEARCOOKIES, onClick = this::clearCookiesDialog)
        clickablePreference(preference = SETTINGS_CLEARWEBSTORAGE, onClick = this::clearWebStorage)

        switchPreference(
            preference = SETTINGS_LOCATION,
            isChecked = userPreferences.locationEnabled,
            onCheckChange = { userPreferences.locationEnabled = it }
        )

        switchPreference(
                preference = SETTINGS_ONLY_CLOSE,
                isChecked = userPreferences.onlyForceClose,
                onCheckChange = { userPreferences.onlyForceClose = it }
        )

        switchPreference(
            preference = SETTINGS_THIRDPCOOKIES,
            isChecked = userPreferences.blockThirdPartyCookiesEnabled,
            isEnabled = DeviceCapabilities.THIRD_PARTY_COOKIE_BLOCKING.isSupported,
            onCheckChange = { userPreferences.blockThirdPartyCookiesEnabled = it }
        )

        switchPreference(
            preference = SETTINGS_SAVEPASSWORD,
            isChecked = userPreferences.savePasswordsEnabled,
            onCheckChange = { userPreferences.savePasswordsEnabled = it }
        )

        switchPreference(
            preference = SETTINGS_CACHEEXIT,
            isChecked = userPreferences.clearCacheExit,
            onCheckChange = { userPreferences.clearCacheExit = it }
        )

        switchPreference(
            preference = SETTINGS_HISTORYEXIT,
            isChecked = userPreferences.clearHistoryExitEnabled,
            onCheckChange = { userPreferences.clearHistoryExitEnabled = it }
        )

        switchPreference(
            preference = SETTINGS_COOKIEEXIT,
            isChecked = userPreferences.clearCookiesExitEnabled,
            onCheckChange = { userPreferences.clearCookiesExitEnabled = it }
        )

        switchPreference(
            preference = SETTINGS_WEBSTORAGEEXIT,
            isChecked = userPreferences.clearWebStorageExitEnabled,
            onCheckChange = { userPreferences.clearWebStorageExitEnabled = it }
        )

        switchPreference(
            preference = SETTINGS_DONOTTRACK,
            isChecked = userPreferences.doNotTrackEnabled,
            summary = (if(userPreferences.popupsEnabled) {
                resources.getString(R.string.crash_warning)
            }
             else{
                ""
            }).toString(),
            onCheckChange = { userPreferences.doNotTrackEnabled = it }
        )

        switchPreference(
            preference = SETTINGS_WEBRTC,
            isChecked = userPreferences.webRtcEnabled && DeviceCapabilities.WEB_RTC.isSupported,
            isEnabled = DeviceCapabilities.WEB_RTC.isSupported,
            onCheckChange = { userPreferences.webRtcEnabled = it }
        )

        switchPreference(
                preference = SETTINGS_FORCEHTTPS,
                isChecked = userPreferences.forceHTTPSenabled,
                onCheckChange = { userPreferences.forceHTTPSenabled = it }
        )

        switchPreference(
                preference = SETTINGS_PREFERHTTPS,
                isChecked = userPreferences.preferHTTPSenabled,
                onCheckChange = { userPreferences.preferHTTPSenabled = it }
        )

        switchPreference(
                preference = SETTINGS_BLOCK_LAN,
                isChecked = userPreferences.blockLanRequestsEnabled,
                onCheckChange = { userPreferences.blockLanRequestsEnabled = it }
        )

        switchPreference(
            preference = SETTINGS_IDENTIFYINGHEADERS,
            isChecked = userPreferences.removeIdentifyingHeadersEnabled,
            summary = (if(userPreferences.popupsEnabled) {
                resources.getString(R.string.crash_warning)
            }
            else{
                "${ArktosCookieView.HEADER_REQUESTED_WITH}, ${ArktosCookieView.HEADER_WAP_PROFILE}"
            }).toString(),
            onCheckChange = { userPreferences.removeIdentifyingHeadersEnabled = it }
        )

        switchPreference(
                preference = SETTINGS_INCOGNITO,
                isChecked = userPreferences.incognito,
                onCheckChange = { userPreferences.incognito = it }
        )

    }

    private fun PasswordChoice.toSummary(): String {
        val stringArray = resources.getStringArray(R.array.password_set_array)
        return when (this) {
            PasswordChoice.NONE -> stringArray[0]
            PasswordChoice.CUSTOM -> stringArray[1]
        }
    }

    private fun showPasswordPicker(summaryUpdater: SummaryUpdater) {
        BrowserDialog.showCustomDialog(activity) {
            setTitle(R.string.enter_password)
            val stringArray = resources.getStringArray(R.array.password_set_array)
            val values = PasswordChoice.values().map {
                Pair(it, when (it) {
                    PasswordChoice.NONE -> stringArray[0]
                    PasswordChoice.CUSTOM -> resources.getString(R.string.enter_password)
                })
            }
            withSingleChoiceItems(values, userPreferences.passwordChoiceLock) {
                updatePasswordChoice(it, activity as Activity, summaryUpdater)
            }
            setPositiveButton(R.string.action_ok, null)
        }
    }

    private fun updatePasswordChoice(choice: PasswordChoice, activity: Activity, summaryUpdater: SummaryUpdater) {
        if (choice == PasswordChoice.CUSTOM) {
            showPasswordTextPicker(activity, summaryUpdater)

            val prefs: SharedPreferences = activity.getSharedPreferences("com.arktosbrowser.mobile", Context.MODE_PRIVATE)

            val editor: SharedPreferences.Editor = prefs.edit()
            editor.putBoolean("noPassword", false)
            editor.apply()
        }
        else{
            val prefs: SharedPreferences = activity.getSharedPreferences("com.arktosbrowser.mobile", Context.MODE_PRIVATE)

            val editor: SharedPreferences.Editor = prefs.edit()
            editor.putBoolean("noPassword", true)
            editor.apply()

            summaryUpdater.updateSummary(resources.getString(R.string.none))
        }

        userPreferences.passwordChoiceLock = choice
        summaryUpdater.updateSummary(choice.toSummary())
    }

    private fun showPasswordTextPicker(activity: Activity, summaryUpdater: SummaryUpdater) {
        val v = activity.layoutInflater.inflate(R.layout.password, null)
        val passwordText = v.findViewById<TextView>(R.id.password)

        // PORT02 — the stored value is an argon2id hash now, so the
        // field can't be prefilled; an empty submission keeps the
        // existing password.
        BrowserDialog.showCustomDialog(activity) {
            setTitle(R.string.enter_password)
            setView(v)
            setPositiveButton(R.string.action_ok) { _, _ ->
                val passwordCode = passwordText.text.toString()
                if (passwordCode.isNotEmpty()) {
                    userPreferences.passwordTextLock = ArktosCore.hashPassword(passwordCode)
                }
            }
        }
    }

    private fun ProxyChoice.toSummary(): String {
        val stringArray = resources.getStringArray(R.array.proxy_choices_array)
        return when (this) {
            ProxyChoice.NONE -> stringArray[0]
            ProxyChoice.ORBOT -> stringArray[1]
            ProxyChoice.I2P -> stringArray[2]
            ProxyChoice.MANUAL -> "${userPreferences.proxyHost}:${userPreferences.proxyPort}"
        }
    }

    private fun showProxyPicker(summaryUpdater: SummaryUpdater) {
        BrowserDialog.showCustomDialog(activity) {
            setTitle(R.string.http_proxy)
            val stringArray = resources.getStringArray(R.array.proxy_choices_array)
            val values = ProxyChoice.values().map {
                Pair(it, when (it) {
                    ProxyChoice.NONE -> stringArray[0]
                    ProxyChoice.ORBOT -> stringArray[1]
                    ProxyChoice.I2P -> stringArray[2]
                    ProxyChoice.MANUAL -> stringArray[3]
                })
            }
            withSingleChoiceItems(values, userPreferences.proxyChoice) {
                updateProxyChoice(it, activity as Activity, summaryUpdater)
            }
            setPositiveButton(R.string.action_ok, null)
        }
    }

    private fun updateProxyChoice(choice: ProxyChoice, activity: Activity, summaryUpdater: SummaryUpdater) {
        val sanitizedChoice = ProxyUtils.sanitizeProxyChoice(choice, activity)
        if (sanitizedChoice == ProxyChoice.MANUAL) {
            showManualProxyPicker(activity, summaryUpdater)
        }

        userPreferences.proxyChoice = sanitizedChoice
        summaryUpdater.updateSummary(sanitizedChoice.toSummary())
    }

    private fun showManualProxyPicker(activity: Activity, summaryUpdater: SummaryUpdater) {
        val v = activity.layoutInflater.inflate(R.layout.dialog_manual_proxy, null)
        val eProxyHost = v.findViewById<TextView>(R.id.proxyHost)
        val eProxyPort = v.findViewById<TextView>(R.id.proxyPort)

        // Limit the number of characters since the port needs to be of type int
        // Use input filters to limit the EditText length and determine the max
        // length by using length of integer MAX_VALUE
        val maxCharacters = Integer.MAX_VALUE.toString().length
        eProxyPort.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(maxCharacters - 1))

        eProxyHost.text = userPreferences.proxyHost
        eProxyPort.text = userPreferences.proxyPort.toString()

        BrowserDialog.showCustomDialog(activity) {
            setTitle(R.string.manual_proxy)
            setView(v)
            setPositiveButton(R.string.action_ok) { _, _ ->
                val proxyHost = eProxyHost.text.toString()
                val proxyPort = try {
                    // Try/Catch in case the user types an empty string or a number
                    // larger than max integer
                    Integer.parseInt(eProxyPort.text.toString())
                } catch (ignored: NumberFormatException) {
                    userPreferences.proxyPort
                }

                userPreferences.proxyHost = proxyHost
                userPreferences.proxyPort = proxyPort
                summaryUpdater.updateSummary("$proxyHost:$proxyPort")
            }
        }
    }

    private fun choiceToUserAgent(index: Int) = when (index) {
        1 -> resources.getString(R.string.agent_default)
        2 -> resources.getString(R.string.agent_desktop)
        3 -> resources.getString(R.string.agent_mobile)
        4 -> resources.getString(R.string.agent_custom)
        else -> resources.getString(R.string.agent_default)
    }

    private fun showUserAgentChooserDialog(summaryUpdater: SummaryUpdater) {
        BrowserDialog.showCustomDialog(activity) {
            setTitle(resources.getString(R.string.title_user_agent))
            setSingleChoiceItems(R.array.user_agent, userPreferences.userAgentChoice - 1) { _, which ->
                userPreferences.userAgentChoice = which + 1
                summaryUpdater.updateSummary(choiceToUserAgent(userPreferences.userAgentChoice))
                when (which) {
                    in 0..2 -> Unit
                    3 -> {
                        summaryUpdater.updateSummary(resources.getString(R.string.agent_custom))
                        showCustomUserAgentPicker(summaryUpdater)
                    }
                }
            }
            setPositiveButton(resources.getString(R.string.action_ok), null)
        }
    }

    private fun showCustomUserAgentPicker(summaryUpdater: SummaryUpdater) {
        activity?.let {
            BrowserDialog.showEditText(it,
                    R.string.title_user_agent,
                    R.string.title_user_agent,
                    userPreferences.userAgentString,
                    R.string.action_ok) { s ->
                userPreferences.userAgentString = s
                summaryUpdater.updateSummary(it.getString(R.string.agent_custom))
            }
        }
    }

    private fun JavaScriptChoice.toSummary(): String {
        val stringArray = resources.getStringArray(R.array.block_javascript)
        return when (this) {
            JavaScriptChoice.NONE -> stringArray[0]
            JavaScriptChoice.WHITELIST -> userPreferences.siteBlockNames
            JavaScriptChoice.BLACKLIST -> userPreferences.siteBlockNames
        }
    }

    private fun showJavaScriptPicker(summaryUpdater: SummaryUpdater) {
        BrowserDialog.showCustomDialog(activity) {
            setTitle(R.string.block_javascript)
            val stringArray = resources.getStringArray(R.array.block_javascript)
            val values = JavaScriptChoice.values().map {
                Pair(it, when (it) {
                    JavaScriptChoice.NONE -> stringArray[0]
                    JavaScriptChoice.WHITELIST -> stringArray[1]
                    JavaScriptChoice.BLACKLIST -> stringArray[2]
                })
            }
            withSingleChoiceItems(values, userPreferences.javaScriptChoice) {
                updateJavaScriptChoice(it, activity as Activity, summaryUpdater)
            }
            setPositiveButton(R.string.action_ok, null)
        }
    }

    private fun updateJavaScriptChoice(choice: JavaScriptChoice, activity: Activity, summaryUpdater: SummaryUpdater) {
        if (choice == JavaScriptChoice.WHITELIST || choice == JavaScriptChoice.BLACKLIST) {
            showManualJavaScriptPicker(activity, summaryUpdater, choice)
        }

        userPreferences.javaScriptChoice = choice
        summaryUpdater.updateSummary(choice.toSummary())
    }

    private fun showManualJavaScriptPicker(activity: Activity, summaryUpdater: SummaryUpdater, choice: JavaScriptChoice) {
        val v = activity.layoutInflater.inflate(R.layout.site_block, null)
        val blockedSites = v.findViewById<TextView>(R.id.siteBlock)

        blockedSites.text = userPreferences.javaScriptBlocked

        BrowserDialog.showCustomDialog(activity) {
            setTitle(R.string.block_javascript)
            setView(v)
            setPositiveButton(R.string.action_ok) { _, _ ->
                val proxyHost = blockedSites.text.toString()
                userPreferences.javaScriptBlocked = proxyHost
                if(choice.toString() == "BLACKLIST"){
                    summaryUpdater.updateSummary(getText(R.string.listed_javascript).toString())
                }
                else{
                    summaryUpdater.updateSummary(getText(R.string.unlisted_javascript).toString())
                }

            }
        }
    }

    private fun clearHistoryDialog() {
        BrowserDialog.showPositiveNegativeDialog(
            activity = activity as Activity,
            title = R.string.title_clear_history,
            message = R.string.dialog_history,
            positiveButton = DialogItem(title = R.string.action_yes) {
                clearHistory()
                    .subscribeOn(databaseScheduler)
                    .observeOn(mainScheduler)
                    .subscribe {toastMessage?.cancel()
                        toastMessage = Toast.makeText(activity, R.string.message_clear_history, Toast.LENGTH_LONG)
                        toastMessage!!.show()
                    }
            },
            negativeButton = DialogItem(title = R.string.action_no) {},
            onCancel = {}
        )
    }

    private fun clearCookiesDialog() {
        BrowserDialog.showPositiveNegativeDialog(
            activity = activity as Activity,
            title = R.string.title_clear_cookies,
            message = R.string.dialog_cookies,
            positiveButton = DialogItem(title = R.string.action_yes) {
                clearCookies()
                    .subscribeOn(databaseScheduler)
                    .observeOn(mainScheduler)
                    .subscribe {
                        toastMessage?.cancel()
                        toastMessage = Toast.makeText(activity, R.string.message_cookies_cleared, Toast.LENGTH_LONG)
                        toastMessage!!.show()
                    }
            },
            negativeButton = DialogItem(title = R.string.action_no) {},
            onCancel = {}
        )
    }

    private fun clearCache() {
        WebView(requireNotNull(activity)).apply {
            clearCache(true)
            destroy()
        }
        deleteCache(requireContext())
        toastMessage?.cancel()
        toastMessage = Toast.makeText(activity, R.string.message_cache_cleared, Toast.LENGTH_LONG)
        toastMessage!!.show()
    }

    fun deleteCache(context: Context) {
        try {
            val dir = context.cacheDir
            deleteDir(dir)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun deleteData(context: Context) {
        try {
            val dir = context.dataDir
            deleteDir(dir)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun deleteDir(dir: File?): Boolean {
        return if (dir != null && dir.isDirectory) {
            val children = dir.list()
            for (i in children.indices) {
                val success = deleteDir(File(dir, children[i]))
                if (!success) {
                    return false
                }
            }
            dir.delete()
        } else if (dir != null && dir.isFile) {
            dir.delete()
        } else {
            false
        }
    }

    private fun clearHistory(): Completable = Completable.fromAction {
        val activity = activity
        if (activity != null) {
            // TODO: 6/9/17 clearHistory is not synchronous
            WebUtils.clearHistory(activity, historyRepository, databaseScheduler)
        } else {
            throw RuntimeException("Activity was null in clearHistory")
        }
    }

    private fun clearCookies(): Completable = Completable.fromAction {
        val activity = activity
        if (activity != null) {
            WebUtils.clearCookies(activity)
        } else {
            throw RuntimeException("Activity was null in clearCookies")
        }
    }

    private fun clearWebStorage() {
        WebView(requireNotNull(activity)).apply {
            clearFormData()
            clearSslPreferences()
            destroy()
        }
        context?.let { WebUtils.eraseWebStorage(it) }

        toastMessage?.cancel()
        toastMessage = Toast.makeText(activity, R.string.message_web_storage_cleared, Toast.LENGTH_LONG)
        toastMessage!!.show()
    }

    companion object {
        private const val SETTINGS_LOCATION = "location"
        private const val SETTINGS_THIRDPCOOKIES = "third_party"
        private const val SETTINGS_SAVEPASSWORD = "password"
        private const val SETTINGS_CACHEEXIT = "clear_cache_exit"
        private const val SETTINGS_HISTORYEXIT = "clear_history_exit"
        private const val SETTINGS_COOKIEEXIT = "clear_cookies_exit"
        private const val SETTINGS_CLEARCACHE = "clear_cache"
        private const val SETTINGS_CLEARHISTORY = "clear_history"
        private const val SETTINGS_CLEARCOOKIES = "clear_cookies"
        private const val SETTINGS_CLEARWEBSTORAGE = "clear_webstorage"
        private const val SETTINGS_WEBSTORAGEEXIT = "clear_webstorage_exit"
        private const val SETTINGS_DONOTTRACK = "do_not_track"
        private const val SETTINGS_WEBRTC = "webrtc_support"
        private const val SETTINGS_IDENTIFYINGHEADERS = "remove_identifying_headers"
        private const val SETTINGS_FORCEHTTPS = "force_https"
        private const val SETTINGS_PREFERHTTPS = "prefer_https"
        private const val SETTINGS_BLOCK_LAN = "block_lan_requests"
        private const val SETTINGS_INCOGNITO = "start_incognito"
        private const val SETTINGS_ONLY_CLOSE = "only_clear"
        private const val SETTINGS_APP_LOCK = "app_lock"
        private const val SETTINGS_CLEAR_ALL = "clear_all"
        private const val SETTINGS_PROXY = "proxy"
        private const val SETTINGS_USER_AGENT = "agent"
        private const val SETTINGS_JAVASCRIPT = "cb_javascript"
        private const val SETTINGS_BLOCK_JAVASCRIPT = "block_javascript"
        private const val SETTINGS_ENABLE_COOKIES = "allow_cookies"
        private const val SETTINGS_COOKIES_INCOGNITO = "incognito_cookies"
        private const val SETTINGS_SHOW_SSL = "show_ssl"
        private const val SETTINGS_BLOCK_MALWARE = "block_malicious_sites"
    }

}
