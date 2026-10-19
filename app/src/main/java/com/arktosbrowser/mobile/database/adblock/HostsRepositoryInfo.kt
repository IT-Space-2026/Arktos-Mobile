package com.arktosbrowser.mobile.database.adblock

import com.arktosbrowser.mobile.di.AdBlockPrefs
import com.arktosbrowser.mobile.preference.delegates.nullableStringPreference
import android.content.SharedPreferences
import javax.inject.Inject

/**
 * Information about the contents of the hosts repository.
 */
class HostsRepositoryInfo @Inject constructor(@AdBlockPrefs preferences: SharedPreferences) {

    /**
     * The identity of the contents of the hosts repository as a [String] or `null`.
     */
    var identity: String? by preferences.nullableStringPreference(IDENTITY)

    companion object {
        private const val IDENTITY = "identity"
    }

}

