package com.arktosbrowser.mobile.database.adblock

import com.arktosbrowser.mobile.di.AdBlockPrefs
import com.arktosbrowser.mobile.preference.delegates.booleanPreference
import android.content.SharedPreferences
import javax.inject.Inject

/**
 * One-shot migration/seed flags for the ad-block subscription model
 * (LIST01). Subscription rows themselves live in the Rust
 * `ArktosStore` (`adblock_subscription` table) — this only tracks
 * whether the catalog seed + legacy hosts-source migration already ran.
 */
class AdBlockListState @Inject constructor(@AdBlockPrefs preferences: SharedPreferences) {

    /**
     * True once the preset catalog has been seeded into the store and
     * the legacy single-source `hostsSource` pref has been migrated.
     */
    var subscriptionsSeeded by preferences.booleanPreference(SUBSCRIPTIONS_SEEDED, false)

    companion object {
        private const val SUBSCRIPTIONS_SEEDED = "subscriptions_seeded"
    }
}
