package com.arktosbrowser.mobile.settings.fragment

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.browser.AppLockGate
import com.arktosbrowser.mobile.browser.PasswordChoice
import com.arktosbrowser.mobile.browser.SiteBlockChoice
import com.arktosbrowser.mobile.di.injector
import com.arktosbrowser.mobile.dialog.BrowserDialog
import com.arktosbrowser.mobile.dialog.BrowserDialog.setDialogSize
import com.arktosbrowser.mobile.dialog.TapjackingGuard
import com.arktosbrowser.mobile.extensions.withSingleChoiceItems
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.rust.ArktosCore
import com.arktosbrowser.mobile.settings.activity.SettingsActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import javax.inject.Inject

/**
 * The general settings of the app.
 */
class ParentalControlSettingsFragment : AbstractSettingsFragment() {

    @Inject lateinit var userPreferences: UserPreferences

    private val appLockGate = AppLockGate()

    private lateinit var proxyChoices: Array<String>

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preference_parents)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        injector.inject(this)

        proxyChoices = resources.getStringArray(R.array.blocked_sites)
        var fullName: String
        if(userPreferences.siteBlockChoice == SiteBlockChoice.BLACKLIST){
            fullName = getText(R.string.only_allow_sites).toString()
        }
        else if(userPreferences.siteBlockChoice == SiteBlockChoice.NONE){
            fullName = getText(R.string.none).toString()
        }
        else{
            fullName = getText(R.string.block_all_sites).toString()
        }

        clickableDynamicPreference(
                preference = SETTINGS_SITE_BLOCK,
                summary = fullName,
                onClick = ::showSiteBlockPicker
        )
        switchPreference(
                preference = SETTINGS_SAFE_SEARCH,
                isChecked = userPreferences.safeSearchForced,
                onCheckChange = { userPreferences.safeSearchForced = it }
        )
        clickableDynamicPreference(
                preference = SETTINGS_PASSWORD,
                summary = userPreferences.passwordChoice.toSummary(),
                onClick = ::showPasswordPicker
        )
        // The gate is the password choice itself — the old
        // `noPassword` flag in the app-wide prefs file was shared with
        // the privacy screen's app-lock toggle, so enabling one lock
        // could silently demand the other's (unset) password.
        if (userPreferences.passwordChoice == PasswordChoice.CUSTOM &&
            userPreferences.passwordText.isNotBlank()) {
            passwordDialog()
        }
    }

    private fun passwordDialog() {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_edit_text, null)
        val editText = dialogView.findViewById<EditText>(R.id.dialog_edit_text)

        editText.setHint(R.string.enter_password)
        editText.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_PASSWORD

        val editorDialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.enter_password)
                .setView(dialogView)
                .setCancelable(false)
                .setNegativeButton(R.string.action_back) { dialog, which ->
                    val settings = Intent(activity, SettingsActivity::class.java)
                    settings.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                    startActivity(settings)
                }
                .setPositiveButton(R.string.action_ok, null)

        val dialog = editorDialog.show()
        setDialogSize(requireContext(), dialog)
        TapjackingGuard.protect(dialog, TapjackingGuard.Surface.PARENTAL_GATE)

        // Button override keeps one dialog alive across attempts —
        // the old flow re-invoked passwordDialog() per wrong guess,
        // stacking a new dialog (and a recursion frame) each time.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            when (val result = appLockGate.check(
                userPreferences.passwordText,
                editText.text.toString()
            )) {
                is AppLockGate.Result.Unlocked -> {
                    result.upgradedHash?.let { userPreferences.passwordText = it }
                    dialog.dismiss()
                }
                AppLockGate.Result.Retry -> {
                    editText.error = resources.getString(R.string.wrong_password)
                    editText.text?.clear()
                }
            }
        }
    }

    private fun SiteBlockChoice.toSummary(): String {
        val stringArray = resources.getStringArray(R.array.blocked_sites)
        return when (this) {
            SiteBlockChoice.NONE -> stringArray[0]
            SiteBlockChoice.WHITELIST -> userPreferences.siteBlockNames
            SiteBlockChoice.BLACKLIST -> userPreferences.siteBlockNames
        }
    }

    private fun showSiteBlockPicker(summaryUpdater: SummaryUpdater) {
        BrowserDialog.showCustomDialog(activity) {
            setTitle(R.string.block_sites_title)
            val stringArray = resources.getStringArray(R.array.blocked_sites)
            val values = SiteBlockChoice.values().map {
                Pair(it, when (it) {
                    SiteBlockChoice.NONE -> stringArray[0]
                    SiteBlockChoice.WHITELIST -> stringArray[1]
                    SiteBlockChoice.BLACKLIST -> stringArray[2]
                })
            }
            withSingleChoiceItems(values, userPreferences.siteBlockChoice) {
                updateSiteBlockChoice(it, activity as Activity, summaryUpdater)
            }
            setPositiveButton(R.string.action_ok, null)
        }
    }

    private fun updateSiteBlockChoice(choice: SiteBlockChoice, activity: Activity, summaryUpdater: SummaryUpdater) {
        if (choice == SiteBlockChoice.WHITELIST || choice == SiteBlockChoice.BLACKLIST) {
            showManualSiteBlockPicker(activity, summaryUpdater, choice)
        }

        userPreferences.siteBlockChoice = choice
        summaryUpdater.updateSummary(choice.toSummary())
    }

    private fun showManualSiteBlockPicker(activity: Activity, summaryUpdater: SummaryUpdater, choice: SiteBlockChoice) {
        val v = activity.layoutInflater.inflate(R.layout.site_block, null)
        val blockedSites = v.findViewById<TextView>(R.id.siteBlock)
        // Limit the number of characters since the port needs to be of type int
        // Use input filters to limit the EditText length and determine the max
        // length by using length of integer MAX_VALUE
        val maxCharacters = Integer.MAX_VALUE.toString().length

        blockedSites.text = userPreferences.siteBlockNames

        BrowserDialog.showCustomDialog(activity) {
            setTitle(R.string.block_sites)
            setView(v)
            setPositiveButton(R.string.action_ok) { _, _ ->
                val proxyHost = blockedSites.text.toString()
                userPreferences.siteBlockNames = proxyHost
                if(choice.toString() == "BLACKLIST"){
                    summaryUpdater.updateSummary(getText(R.string.only_allow_sites).toString())
                }
                else{
                    summaryUpdater.updateSummary(getText(R.string.block_all_sites).toString())
                }

            }
        }
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
            withSingleChoiceItems(values, userPreferences.passwordChoice) {
                updatePasswordChoice(it, activity as Activity, summaryUpdater)
            }
            setPositiveButton(R.string.action_ok, null)
        }
    }

    private fun updatePasswordChoice(choice: PasswordChoice, activity: Activity, summaryUpdater: SummaryUpdater) {
        if (choice == PasswordChoice.CUSTOM) {
            showPasswordTextPicker(activity, summaryUpdater)
        }
        else{
            summaryUpdater.updateSummary(resources.getString(R.string.none))
        }

        userPreferences.passwordChoice = choice
        summaryUpdater.updateSummary(choice.toSummary())
    }

    private fun showPasswordTextPicker(activity: Activity, summaryUpdater: SummaryUpdater) {
        val v = activity.layoutInflater.inflate(R.layout.password, null)
        val passwordText = v.findViewById<TextView>(R.id.password)

        // PORT02 — the stored value is an argon2id hash now, so the
        // field can't be prefilled; an empty submission keeps the
        // existing password.
        BrowserDialog.showCustomDialog(activity, TapjackingGuard.Surface.CREDENTIAL_SET) {
            setTitle(R.string.enter_password)
            setView(v)
            setPositiveButton(R.string.action_ok) { _, _ ->
            val passwordCode = passwordText.text.toString()
            if (passwordCode.isNotEmpty()) {
                userPreferences.passwordText = ArktosCore.hashPassword(passwordCode)
            }
        }
    }
    }


    companion object {
        private const val SETTINGS_SITE_BLOCK = "siteblock"
        private const val SETTINGS_SAFE_SEARCH = "safe_search"
        private const val SETTINGS_PASSWORD = "password"
    }
}
