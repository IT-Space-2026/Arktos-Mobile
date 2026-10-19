package com.arktosbrowser.mobile.settings.fragment

import android.content.Context
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.adblock.BlocklistLoader
import com.arktosbrowser.mobile.adblock.RankListLoader
import com.arktosbrowser.mobile.di.injector
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.uniffi.StoreAdblockSubscription
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

/**
 * List management (PREFS01) — the mobile mirror of the desktop
 * `AdBlockDialog`: an "adblock/lists" table with per-list enabled
 * toggle, rule count, last-update stamp and per-row refresh, for
 * both the filter subscriptions (LIST01) and the site-rank sources
 * (RANK01), plus the remote-list consent gate and refresh cadence.
 *
 * Reached from the Privacy settings section.
 *
 * Injection happens in [onAttach]: `PreferenceFragmentCompat` calls
 * [onCreatePreferences] inside `super.onCreate`, so anything the
 * preference builder touches must already be injected by then.
 */
class ListsSettingsFragment : AbstractSettingsFragment() {

    @Inject internal lateinit var userPreferences: UserPreferences
    @Inject internal lateinit var blocklistLoader: BlocklistLoader
    @Inject internal lateinit var rankListLoader: RankListLoader

    private val dynamicPreferences = mutableListOf<Preference>()

    override fun onAttach(context: Context) {
        super.onAttach(context)
        injector.inject(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        switchPreference(
            preference = SETTINGS_ADBLOCK_MASTER,
            isChecked = userPreferences.adBlockEnabled,
            onCheckChange = {
                userPreferences.adBlockEnabled = it
                blocklistLoader.syncPrefs()
            }
        )

        switchPreference(
            preference = SETTINGS_REMOTE_CONSENT,
            isChecked = userPreferences.remoteListsConsentEnabled,
            onCheckChange = {
                userPreferences.remoteListsConsentEnabled = it
                // Granting consent kicks the same staleness-checked
                // fetch the desktop's grantRemoteLists() performs —
                // fresh copies are not re-downloaded.
                if (it) {
                    blocklistLoader.refreshHosts(forceRefresh = false)
                    rankListLoader.ensureInitialized()
                }
            }
        )

        clickablePreference(
            preference = SETTINGS_REFRESH_ALL,
            onClick = {
                blocklistLoader.refreshHosts(forceRefresh = true)
                rankListLoader.updateNow()
            }
        )

        blocklistLoader.onSubscriptionsChanged = {
            activity?.runOnUiThread { rebuildLists() }
        }
        rankListLoader.onUpdateFinished = {
            activity?.runOnUiThread { rebuildLists() }
        }

        // Points the engine at the rank dir (idempotent) so the
        // last-update stamps read real values even when no WebView
        // has run yet — and performs the consent-gated staleness
        // check on the usual cadence.
        rankListLoader.ensureInitialized()
    }

    override fun onDestroy() {
        blocklistLoader.onSubscriptionsChanged = null
        rankListLoader.onUpdateFinished = null
        super.onDestroy()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preference_lists)
        rebuildLists()
    }

    /** Rebuild every data-driven section under the static XML. */
    private fun rebuildLists() {
        val screen = preferenceScreen
        dynamicPreferences.forEach { screen.removePreference(it) }
        dynamicPreferences.clear()
        rebuildRankSources()
        rebuildSubscriptions()
    }

    /**
     * The site-rank section (RANK01): one row per source with an
     * enable toggle, last-update stamp and per-row refresh.
     */
    private fun rebuildRankSources() {
        val screen = preferenceScreen
        val category = PreferenceCategory(requireContext()).apply {
            title = getString(R.string.lists_rank_category)
        }
        screen.addPreference(category)
        dynamicPreferences += category
        rankListLoader.sources().forEach { source ->
            category.addPreference(
                ListRowPreference(requireContext()).apply {
                    title = source.name
                    summary = lastUpdateSummary(source.lastUpdateMs.toLong())
                    isChecked = rankListLoader.isSourceEnabled(source.kind)
                    setOnPreferenceChangeListener { _, newValue ->
                        rankListLoader.setSourceEnabled(source.kind, newValue as Boolean)
                        true
                    }
                    onRefresh = { rankListLoader.updateSource(source.kind) }
                }
            )
        }
    }

    /**
     * The filter-subscription table (LIST01): per-category groups of
     * enable toggles showing rule count + last update, each with a
     * per-row refresh action.
     */
    private fun rebuildSubscriptions() {
        val screen = preferenceScreen
        blocklistLoader.allSubscriptions()
            .groupBy { it.category }
            .forEach { (category, subscriptions) ->
                val categoryPreference = PreferenceCategory(requireContext()).apply {
                    title = category
                }
                screen.addPreference(categoryPreference)
                dynamicPreferences += categoryPreference
                subscriptions.forEach { sub ->
                    categoryPreference.addPreference(
                        ListRowPreference(requireContext()).apply {
                            title = sub.name
                            summary = subscriptionSummary(sub)
                            isChecked = sub.enabled
                            setOnPreferenceChangeListener { _, newValue ->
                                blocklistLoader.setSubscriptionEnabled(sub.id, newValue as Boolean)
                                true
                            }
                            onRefresh = { blocklistLoader.refreshSubscription(sub.id) }
                        }
                    )
                }
            }
    }

    private fun subscriptionSummary(sub: StoreAdblockSubscription): String =
        if (sub.hostCount > 0uL) {
            getString(
                R.string.block_subscription_summary,
                sub.hostCount.toLong(),
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(sub.lastUpdated.toLong()))
            )
        } else {
            getString(R.string.block_subscription_never)
        }

    private fun lastUpdateSummary(stampMs: Long): String =
        if (stampMs > 0) {
            getString(
                R.string.lists_last_updated,
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(stampMs))
            )
        } else {
            getString(R.string.block_subscription_never)
        }

    companion object {
        private const val SETTINGS_ADBLOCK_MASTER = "lists_adblock_master"
        private const val SETTINGS_REMOTE_CONSENT = "lists_remote_consent"
        private const val SETTINGS_REFRESH_ALL = "lists_refresh_all"
    }
}
