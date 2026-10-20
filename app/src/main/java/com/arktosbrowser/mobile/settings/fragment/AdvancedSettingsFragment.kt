package com.arktosbrowser.mobile.settings.fragment

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Toast
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.constant.TEXT_ENCODINGS
import com.arktosbrowser.mobile.di.injector
import com.arktosbrowser.mobile.dialog.BrowserDialog
import com.arktosbrowser.mobile.extensions.resizeAndShow
import com.arktosbrowser.mobile.extensions.withSingleChoiceItems
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.view.RenderingMode
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import javax.inject.Inject

/**
 * The advanced settings of the app.
 */
class AdvancedSettingsFragment : AbstractSettingsFragment() {

    @Inject internal lateinit var userPreferences: UserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        injector.inject(this)

        clickableDynamicPreference(
                preference = SETTINGS_TRANSLATION_ENDPOINT,
                summary = userPreferences.translationEndpoint,
                onClick = this::showTranslationEndpointPicker
        )

        clickableDynamicPreference(
            preference = SETTINGS_RENDERING_MODE,
            summary = userPreferences.renderingMode.toDisplayString(),
            onClick = this::showRenderingDialogPicker
        )

        clickableDynamicPreference(
            preference = SETTINGS_TEXT_ENCODING,
            summary = userPreferences.textEncoding,
            onClick = this::showTextEncodingDialogPicker
        )

        switchPreference(
            preference = SETTINGS_NEW_WINDOW,
            isChecked = userPreferences.popupsEnabled,
            onCheckChange = { userPreferences.popupsEnabled = it }
        )

        switchPreference(
                preference = SETTINGS_BLOCK_INTENT,
                isChecked = userPreferences.blockIntent,
                onCheckChange = { userPreferences.blockIntent = it }
        )

        // The label reads "use integrated download manager" — ON means
        // internal, so the third-party pref stores the inverse.
        switchPreference(
                preference = SETTINGS_LEGACY_DOWNLOADER,
                isChecked = !userPreferences.useThirdPartyDownloaderApps,
                onCheckChange = { userPreferences.useThirdPartyDownloaderApps = !it }
        )

        // The XML intent action only exists where the WebView dev UI
        // is installed — bind in code so a missing handler degrades to
        // a toast instead of an ActivityNotFoundException crash.
        clickablePreference(
                preference = SETTINGS_WEBVIEW_DEVTOOLS,
                onClick = {
                    val intent = Intent("com.android.webview.SHOW_DEV_UI")
                        .putExtra("SOURCE", "SELF")
                    try {
                        startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(activity, R.string.webview_devtools_unavailable, Toast.LENGTH_SHORT).show()
                    }
                }
        )
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preference_advanced)
    }

    /**
     * Shows the dialog which allows the user to choose the browser's rendering method.
     *
     * @param summaryUpdater the command which allows the summary to be updated.
     */
    private fun showRenderingDialogPicker(summaryUpdater: SummaryUpdater) {
        activity?.let { MaterialAlertDialogBuilder(it) }?.apply {
            setTitle(resources.getString(R.string.rendering_mode))

            val values = RenderingMode.values().map { Pair(it, it.toDisplayString()) }
            withSingleChoiceItems(values, userPreferences.renderingMode) {
                userPreferences.renderingMode = it
                summaryUpdater.updateSummary(it.toDisplayString())

            }
            setPositiveButton(resources.getString(R.string.action_ok), null)
        }?.resizeAndShow()

    }

    /**
     * Shows the dialog which allows the user to choose the browser's text encoding.
     *
     * @param summaryUpdater the command which allows the summary to be updated.
     */
    private fun showTextEncodingDialogPicker(summaryUpdater: SummaryUpdater) {
        activity?.let {
            MaterialAlertDialogBuilder(it).apply {
                setTitle(resources.getString(R.string.text_encoding))

                val currentChoice = TEXT_ENCODINGS.indexOf(userPreferences.textEncoding)

                setSingleChoiceItems(TEXT_ENCODINGS, currentChoice) { _, which ->
                    userPreferences.textEncoding = TEXT_ENCODINGS[which]
                    summaryUpdater.updateSummary(TEXT_ENCODINGS[which])
                }
                setPositiveButton(resources.getString(R.string.action_ok), null)
            }.resizeAndShow()
        }
    }

    private fun RenderingMode.toDisplayString(): String = getString(when (this) {
        RenderingMode.NORMAL -> R.string.name_normal
        RenderingMode.INVERTED -> R.string.name_inverted
        RenderingMode.GRAYSCALE -> R.string.name_grayscale
        RenderingMode.INVERTED_GRAYSCALE -> R.string.name_inverted_grayscale
        RenderingMode.INCREASE_CONTRAST -> R.string.name_increase_contrast
    })

    private fun showTranslationEndpointPicker(summaryUpdater: SummaryUpdater) {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_edit_text, null)
        val editText = dialogView.findViewById<EditText>(R.id.dialog_edit_text)

        editText.setText(userPreferences.translationEndpoint)

        val editorDialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.translation_endpoint)
                .setView(dialogView)
                .setCancelable(false)
                .setNegativeButton(R.string.action_back) { dialog, which ->
                }
                .setPositiveButton(R.string.action_ok
                ) { _, _ ->
                    userPreferences.translationEndpoint = editText.text.toString()
                    summaryUpdater.updateSummary(editText.text.toString())
                }

        val dialog = editorDialog.show()
        BrowserDialog.setDialogSize(requireContext(), dialog)
    }

    companion object {
        private const val SETTINGS_NEW_WINDOW = "allow_new_window"
        private const val SETTINGS_RENDERING_MODE = "rendering_mode"
        private const val SETTINGS_TEXT_ENCODING = "text_encoding"
        private const val SETTINGS_BLOCK_INTENT = "block_intent"
        private const val SETTINGS_LEGACY_DOWNLOADER = "downloader"
        private const val SETTINGS_WEBVIEW_DEVTOOLS = "webview_devtools"
        private const val SETTINGS_TRANSLATION_ENDPOINT = "translation_endpoint"
    }

}
