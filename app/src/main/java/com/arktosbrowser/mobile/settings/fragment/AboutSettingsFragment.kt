// SPDX-License-Identifier: MPL-2.0
// Copyright 2014 A.C.R. and Arktos contributors
package com.arktosbrowser.mobile.settings.fragment

import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import com.arktosbrowser.mobile.BuildConfig
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.di.MainScheduler
import com.arktosbrowser.mobile.di.injector
import com.arktosbrowser.mobile.engine.Engines
import com.arktosbrowser.mobile.html.ChangelogPage
import com.arktosbrowser.mobile.update.UpdateChecker
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.kotlin.subscribeBy
import javax.inject.Inject


class AboutSettingsFragment : AbstractSettingsFragment() {

    @Inject lateinit var updateChecker: UpdateChecker
    @Inject @MainScheduler lateinit var mainScheduler: Scheduler

    private var latestReleaseDisposable: Disposable? = null
    private var latestReleaseUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        injector.inject(this)

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

        // UPDT02 — the latest downloadable release, fetched on open;
        // tapping the row opens the release page.
        clickableDynamicPreference(
                preference = LATEST_VERSION,
                summary = getString(R.string.update_checking),
                onClick = { latestReleaseUrl?.let(::openUrl) }
        )
        latestReleaseDisposable = updateChecker.latestRelease()
            .observeOn(mainScheduler)
            .subscribeBy(
                onSuccess = { info ->
                    latestReleaseUrl = info.url
                    val upToDate = !UpdateChecker.isNewer(info.version, BuildConfig.VERSION_NAME)
                    findPreference<androidx.preference.Preference>(LATEST_VERSION)?.summary =
                        if (upToDate) "${info.version} (${getString(R.string.up_to_date)})" else info.version
                },
                onComplete = {
                    findPreference<androidx.preference.Preference>(LATEST_VERSION)?.summary =
                        getString(R.string.update_unavailable)
                }
            )

        clickablePreference(
                preference = WEBVIEW_VERSION,
                summary = webview,
                onClick = { }
        )

    }

    override fun onDestroy() {
        latestReleaseDisposable?.dispose()
        super.onDestroy()
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        intent.setPackage(requireContext().packageName)
        startActivity(intent)
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
        private const val LATEST_VERSION = "pref_latest_version"
        private const val WEBVIEW_VERSION = "pref_webview"
    }
}
