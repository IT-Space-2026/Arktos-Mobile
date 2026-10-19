package com.arktosbrowser.mobile.settings.fragment

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreference
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.adblock.BlocklistLoader
import com.arktosbrowser.mobile.di.DiskScheduler
import com.arktosbrowser.mobile.di.MainScheduler
import com.arktosbrowser.mobile.di.injector
import com.arktosbrowser.mobile.dialog.BrowserDialog
import com.arktosbrowser.mobile.dialog.DialogItem
import com.arktosbrowser.mobile.extensions.toast
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.uniffi.StoreAdblockSubscription
import io.reactivex.Maybe
import io.reactivex.Scheduler
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.plusAssign
import io.reactivex.rxkotlin.subscribeBy
import okhttp3.HttpUrl
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

/**
 * Settings for the ad block mechanic — master toggles plus the
 * filter-list subscription table (LIST01): per-list switches grouped
 * by category, add-custom and manual refresh actions.
 */
class AdBlockSettingsFragment : AbstractSettingsFragment() {

    @Inject internal lateinit var userPreferences: UserPreferences
    @Inject @field:MainScheduler internal lateinit var mainScheduler: Scheduler
    @Inject @field:DiskScheduler internal lateinit var diskScheduler: Scheduler
    @Inject internal lateinit var blocklistLoader: BlocklistLoader

    private val compositeDisposable = CompositeDisposable()
    private val subscriptionCategories = mutableListOf<PreferenceCategory>()

    override fun onAttach(context: Context) {
        super.onAttach(context)
        // Inject here, not in onCreate: PreferenceFragmentCompat calls
        // onCreatePreferences inside super.onCreate, and the
        // subscription table built there needs the loader already.
        injector.inject(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        switchPreference(
            preference = "block_cookies",
            isChecked = userPreferences.cookieBlockEnabled,
            onCheckChange = { userPreferences.cookieBlockEnabled = it }
        )

        switchPreference(
            preference = "block_malicious_sites",
            isChecked = userPreferences.blockMalwareEnabled,
            onCheckChange = { userPreferences.blockMalwareEnabled = it }
        )

        switchPreference(
            preference = "cb_block_ads",
            isChecked = userPreferences.adBlockEnabled,
            onCheckChange = { userPreferences.adBlockEnabled = it }
        )

        clickablePreference(
            preference = "preference_add_subscription",
            onClick = ::showAddSubscriptionChooser
        )

        clickablePreference(
            preference = "preference_hosts_refresh_force",
            onClick = {
                blocklistLoader.refreshHosts(forceRefresh = true)
            }
        )

        blocklistLoader.onSubscriptionsChanged = {
            activity?.runOnUiThread { rebuildSubscriptionPreferences() }
        }
    }

    override fun onDestroy() {
        blocklistLoader.onSubscriptionsChanged = null
        super.onDestroy()
        compositeDisposable.clear()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preference_ad_block)
        rebuildSubscriptionPreferences()
    }

    /**
     * Rebuild the per-category subscription switch groups under the
     * preference screen. Rows come from the Rust store in seed order;
     * grouping preserves the catalog's category ordering with custom
     * lists last.
     */
    private fun rebuildSubscriptionPreferences() {
        val screen = preferenceScreen
        subscriptionCategories.forEach { screen.removePreference(it) }
        subscriptionCategories.clear()

        blocklistLoader.allSubscriptions()
            .groupBy { it.category }
            .forEach { (category, subscriptions) ->
                val categoryPreference = PreferenceCategory(requireContext()).apply {
                    title = category
                }
                screen.addPreference(categoryPreference)
                subscriptionCategories += categoryPreference
                subscriptions.forEach { sub ->
                    categoryPreference.addPreference(
                        SwitchPreference(requireContext()).apply {
                            title = sub.name
                            summary = subscriptionSummary(sub)
                            isChecked = sub.enabled
                            setOnPreferenceChangeListener { _, newValue ->
                                blocklistLoader.setSubscriptionEnabled(sub.id, newValue as Boolean)
                                true
                            }
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

    private fun showAddSubscriptionChooser() {
        BrowserDialog.showListChoices(
            activity as Activity,
            R.string.block_add_subscription,
            DialogItem(
                title = R.string.block_add_subscription_url,
                onClick = { showUrlChooser() }
            ),
            DialogItem(
                title = R.string.block_add_subscription_file,
                onClick = { showFileChooser() }
            )
        )
    }

    private fun showFileChooser() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = TEXT_MIME_TYPE
        }

        startActivityForResult(intent, FILE_REQUEST_CODE)
    }

    private fun showUrlChooser() {
        BrowserDialog.showEditText(
            activity as Activity,
            title = R.string.block_add_subscription_url,
            hint = R.string.hint_url,
            currentText = null,
            action = R.string.action_ok,
            textInputListener = {
                val url = HttpUrl.parse(it)
                    ?.takeIf { url -> url.isHttps || url.scheme() == "http" }
                    ?: return@showEditText run { activity?.toast(R.string.problem_download) }
                blocklistLoader.addCustomSubscription(url.host(), url.toString())
            }
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                data?.data?.also { uri ->
                    compositeDisposable += copyToCustomListFile(uri)
                        .subscribeOn(diskScheduler)
                        .observeOn(mainScheduler)
                        .subscribeBy(
                            onComplete = { activity?.toast(R.string.action_message_canceled) },
                            onSuccess = { (file, displayName) ->
                                blocklistLoader.addCustomSubscription(
                                    displayName,
                                    "file://${file.absolutePath}"
                                )
                            }
                        )
                }
            } else {
                activity?.toast(R.string.action_message_canceled)
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    /**
     * Copy the picked document into app storage as a new custom list
     * file; emits the output file and the document's display name.
     */
    private fun copyToCustomListFile(uri: Uri): Maybe<Pair<File, String>> = Maybe.create {
        val externalFilesDir = activity?.getExternalFilesDir("")
            ?: return@create it.onComplete()
        val inputStream = activity?.contentResolver?.openInputStream(uri)
            ?: return@create it.onComplete()
        val displayName = activity?.contentResolver
            ?.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: uri.lastPathSegment
            ?: CUSTOM_HOSTS_FILE

        try {
            val outputFile = File(externalFilesDir, "${System.currentTimeMillis()}_$CUSTOM_HOSTS_FILE")

            val input = inputStream.source()
            val output = outputFile.sink().buffer()
            output.writeAll(input)
            return@create it.onSuccess(outputFile to displayName)
        } catch (exception: IOException) {
            return@create it.onComplete()
        }
    }

    companion object {
        private const val FILE_REQUEST_CODE = 100
        private const val CUSTOM_HOSTS_FILE = "custom_hosts.txt"
        private const val TEXT_MIME_TYPE = "text/*"
    }
}
