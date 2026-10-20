package com.arktosbrowser.mobile.settings.fragment

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.browser.SearchBoxDisplayChoice
import com.arktosbrowser.mobile.browser.TextZoom
import com.arktosbrowser.mobile.di.injector
import com.arktosbrowser.mobile.extensions.resizeAndShow
import com.arktosbrowser.mobile.extensions.withSingleChoiceItems
import com.arktosbrowser.mobile.preference.UserPreferences
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import javax.inject.Inject


class DisplaySettingsFragment : AbstractSettingsFragment() {

    @Inject internal lateinit var userPreferences: UserPreferences

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preference_display)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        injector.inject(this)

        // preferences storage
        clickablePreference(
            preference = SETTINGS_TEXTSIZE,
            onClick = ::showTextSizePicker
        )

        clickableDynamicPreference(
            preference = SETTINGS_URL_CONTENT,
            summary = userPreferences.urlBoxContentChoice.toDisplayString(),
            onClick = this::showUrlBoxDialogPicker
        )

        switchPreference(
            preference = SETTINGS_TRIM_URL_BAR,
            isChecked = userPreferences.trimUrlBarEnabled,
            onCheckChange = { userPreferences.trimUrlBarEnabled = it }
        )

        switchPreference(
            preference = SETTINGS_COLOR_MODE,
            isChecked = userPreferences.colorModeEnabled,
            onCheckChange = { userPreferences.colorModeEnabled = it }
        )

        switchPreference(
            preference = SETTINGS_FORCE_ZOOM,
            isChecked = userPreferences.forceZoom,
            onCheckChange = { userPreferences.forceZoom = it }
        )

        switchPreference(
                preference = SETTINGS_NAVBAR,
                isChecked = userPreferences.navbar,
                isEnabled = !userPreferences.bottomBar || userPreferences.navbar,
                onCheckChange = { userPreferences.navbar = it }
        )

        switchPreference(
            preference = SETTINGS_HIDESTATUSBAR,
            isChecked = userPreferences.hideStatusBarEnabled,
            onCheckChange = { userPreferences.hideStatusBarEnabled = it }
        )

        switchPreference(
            preference = SETTINGS_FULLSCREEN,
            isChecked = userPreferences.fullScreenEnabled,
            onCheckChange = {userPreferences.fullScreenEnabled = it }
        )

        switchPreference(
            preference = SETTINGS_VIEWPORT,
            isChecked = userPreferences.useWideViewPortEnabled,
            onCheckChange = { userPreferences.useWideViewPortEnabled = it }
        )

        switchPreference(
            preference = SETTINGS_OVERVIEWMODE,
            isChecked = userPreferences.overviewModeEnabled,
            onCheckChange = { userPreferences.overviewModeEnabled = it }
        )

        switchPreference(
            preference = SETTINGS_REFLOW,
            isChecked = userPreferences.textReflowEnabled,
            onCheckChange = { userPreferences.textReflowEnabled = it }
        )

        // The drawer option keys live in preference_drawers.xml and are
        // bound by DrawerSettingsFragment; the duplicate bindings that
        // used to sit here found no matching preference and were dead
        // no-ops. "show_extra" had no consumer either.

        switchPreference(
                preference = SETTINGS_BOTTOM_BAR,
                isChecked = userPreferences.bottomBar,
                isEnabled = !userPreferences.navbar || userPreferences.bottomBar,
                onCheckChange = {userPreferences.bottomBar = it; Toast.makeText(activity, R.string.please_restart, Toast.LENGTH_LONG).show()}
        )
    }

    private fun showUrlBoxDialogPicker(summaryUpdater: SummaryUpdater) {
        activity?.let { MaterialAlertDialogBuilder(it) }?.apply {
            setTitle(resources.getString(R.string.url_contents))

            val items = SearchBoxDisplayChoice.values().map { Pair(it, it.toDisplayString()) }

            withSingleChoiceItems(items, userPreferences.urlBoxContentChoice) {
                userPreferences.urlBoxContentChoice = it
                summaryUpdater.updateSummary(it.toDisplayString())
            }
            setPositiveButton(resources.getString(R.string.action_ok), null)
        }?.resizeAndShow()
    }

    private fun SearchBoxDisplayChoice.toDisplayString(): String {
        val stringArray = resources.getStringArray(R.array.url_content_array)
        return when (this) {
            SearchBoxDisplayChoice.URL -> stringArray[0]
            SearchBoxDisplayChoice.DOMAIN -> stringArray[1]
            SearchBoxDisplayChoice.TITLE -> stringArray[2]
        }
    }

    private fun showTextSizePicker() {
        MaterialAlertDialogBuilder(requireContext()).apply {
            val layoutInflater = activity?.layoutInflater
            val customView = (layoutInflater?.inflate(R.layout.dialog_seek_bar, null) as LinearLayout).apply {
                val text = TextView(activity).apply {
                    setText(R.string.untitled)
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                addView(text)
                val size = TextView(activity).apply {
                    setText(R.string.untitled)
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                addView(size)

                findViewById<SeekBar>(R.id.text_size_seekbar).apply {
                    setOnSeekBarChangeListener(TextSeekBarListener(text, size))
                    max = TextZoom.SEEKBAR_MAX
                    progress = TextZoom.toProgress(userPreferences.textSize)
                }

            }
            setView(customView)
            setTitle(R.string.title_text_size)
            setPositiveButton(android.R.string.ok) { _, _ ->
                val seekBar = customView.findViewById<SeekBar>(R.id.text_size_seekbar)
                userPreferences.textSize = TextZoom.fromProgress(seekBar.progress)
            }
        }.resizeAndShow()
    }

    private class TextSeekBarListener(
        private val sampleText: TextView,
        private val sizeText: TextView
    ) : SeekBar.OnSeekBarChangeListener {

        override fun onProgressChanged(view: SeekBar, size: Int, user: Boolean) {
            val index = TextZoom.fromProgress(size)
            this.sampleText.textSize = TextZoom.previewSp(index)
            this.sizeText.text = TextZoom.zoomPercent(index).toString() + "%"
        }

        override fun onStartTrackingTouch(arg0: SeekBar) {}

        override fun onStopTrackingTouch(arg0: SeekBar) {}

    }

    companion object {

        private const val SETTINGS_HIDESTATUSBAR = "fullScreenOption"
        private const val SETTINGS_FULLSCREEN = "fullscreen"
        private const val SETTINGS_VIEWPORT = "wideViewPort"
        private const val SETTINGS_OVERVIEWMODE = "overViewMode"
        private const val SETTINGS_REFLOW = "text_reflow"
        private const val SETTINGS_TEXTSIZE = "text_size"
        private const val SETTINGS_BOTTOM_BAR = "bottom_bar"
        private const val SETTINGS_NAVBAR = "second_bar"
        private const val SETTINGS_URL_CONTENT = "url_contents"
        private const val SETTINGS_TRIM_URL_BAR = "trim_url_bar"
        private const val SETTINGS_COLOR_MODE = "cb_colormode"
        private const val SETTINGS_FORCE_ZOOM = "force_zoom"
    }
}
