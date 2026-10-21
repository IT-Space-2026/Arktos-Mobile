// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Menu
import com.arktosbrowser.mobile.browser.activity.BrowserActivity
import com.arktosbrowser.mobile.engine.Engines
import io.reactivex.rxjava3.core.Completable


class IncognitoActivity : BrowserActivity() {

    override fun provideThemeOverride(): Int? = R.style.Theme_DarkTheme

    public override fun updateCookiePreference(): Completable = Completable.fromAction {
        Engines.defaultProfile.acceptsCookies = if (DeviceCapabilities.FULL_INCOGNITO.isSupported) {
            userPreferences.cookiesEnabled
        } else {
            userPreferences.incognitoCookiesEnabled
        }
    }



    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        return false
    }

    @Suppress("RedundantOverride")
    override fun onNewIntent(intent: Intent) {
        handleNewIntent(intent)
        super.onNewIntent(intent)
    }

    @Suppress("RedundantOverride")
    override fun onPause() = super.onPause() // saveOpenTabs();

    override fun updateHistory(title: String?, url: String) = Unit // addItemToHistory(title, url)

    override fun isIncognito() = true

    override fun closeActivity() = closeDrawers(this::closeBrowser)

    companion object {
        /**
         * Creates the intent with which to launch the activity. Adds the reorder to front flag.
         */
        fun createIntent(context: Context, uri: Uri? = null) = Intent(context, IncognitoActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            data = uri
        }
    }
}
