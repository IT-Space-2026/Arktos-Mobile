/*
 * Copyright 2014 A.C.R. Development
 */
package com.arktosbrowser.mobile.settings.fragment

import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import com.arktosbrowser.mobile.BuildConfig
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.engine.Engines
import com.arktosbrowser.mobile.html.ChangelogPage


class AboutSettingsFragment : AbstractSettingsFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val webview = Engines.backend.engineNote().ifEmpty {
            resources.getString(R.string.unknown)
        }


        clickablePreference(
                preference = CHANGELOG,
                onClick = ::openChangelog
        )

        clickablePreference(
                preference = SETTINGS_VERSION,
                summary = BuildConfig.VERSION_NAME,
                onClick = ::openChangelog
        )

        clickablePreference(
                preference = WEBVIEW_VERSION,
                summary = webview,
                onClick = { }
        )

    }

    /**
     * CHLOG01 — the changelog lives in the browser as the
     * `arktos://changelog` chrome page: hand a VIEW intent back to this
     * package so MainActivity opens it in a new tab.
     */
    private fun openChangelog() {
        val intent = Intent(Intent.ACTION_VIEW, ChangelogPage.URL.toUri())
        intent.setPackage(requireContext().packageName)
        startActivity(intent)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preference_about)
    }

    companion object {
        private const val CHANGELOG = "pref_changelog"
        private const val SETTINGS_VERSION = "pref_version"
        private const val WEBVIEW_VERSION = "pref_webview"
    }
}
