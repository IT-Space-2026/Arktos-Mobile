package com.arktosbrowser.mobile.adblock

import android.app.Application
import android.content.res.AssetManager
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.adblock.source.HostsDataSource
import com.arktosbrowser.mobile.adblock.source.HostsDataSourceProvider
import com.arktosbrowser.mobile.adblock.source.HostsResult
import com.arktosbrowser.mobile.database.adblock.HostsRepositoryInfo
import com.arktosbrowser.mobile.di.DatabaseScheduler
import com.arktosbrowser.mobile.di.MainScheduler
import com.arktosbrowser.mobile.extensions.toast
import com.arktosbrowser.mobile.log.Logger
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.rust.ArktosCore
import io.reactivex.Maybe
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.plusAssign
import io.reactivex.rxkotlin.subscribeBy
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads blocklist content into the Rust `BlocklistEngine`
 * (via the [ArktosCore] seam) and keeps it in sync with
 * [UserPreferences].
 *
 * Kotlin keeps data *sourcing* — assets, local files, remote URLs —
 * and pushes raw list content into Rust once at init; per-request
 * verdicts happen entirely inside `libarktos.so`. Replaces
 * `BloomFilterAdBlocker` (BLOCK01).
 */
@Singleton
class BlocklistLoader @Inject constructor(
    private val hostsDataSourceProvider: HostsDataSourceProvider,
    private val hostsRepositoryInfo: HostsRepositoryInfo,
    private val userPreferences: UserPreferences,
    private val assetManager: AssetManager,
    private val application: Application,
    private val logger: Logger,
    @DatabaseScheduler private val databaseScheduler: Scheduler,
    @MainScheduler private val mainScheduler: Scheduler
) {

    private val compositeDisposable = CompositeDisposable()

    @Volatile private var hostsLoaded = false
    @Volatile private var malwareLoaded = false

    /**
     * Idempotent entry point — syncs preference-derived state and
     * kicks off the hosts load if it hasn't happened yet. Safe to call
     * once per WebView.
     */
    fun ensureInitialized() {
        syncPrefs()
        refreshHosts(forceRefresh = false)
    }

    /**
     * Push every preference-derived input into the engine: the three
     * feature toggles and the site-block list/mode. Loads the malware
     * list lazily the first time it is enabled.
     */
    fun syncPrefs() {
        ArktosCore.configureBlocklist(
            adsEnabled = userPreferences.adBlockEnabled,
            malwareEnabled = userPreferences.blockMalwareEnabled,
            lanBlockEnabled = userPreferences.blockLanRequestsEnabled
        )
        ArktosCore.setSiteBlockList(userPreferences.siteBlockNames, userPreferences.siteBlockChoice)
        if (userPreferences.blockMalwareEnabled) {
            loadMalwareTerms()
        } else if (malwareLoaded) {
            ArktosCore.loadMalwareTerms("")
            malwareLoaded = false
        }
    }

    /**
     * (Re)populate the ad-block hosts set in the engine from the
     * selected data source. A cached copy of the last fetched content
     * ([HOSTS_CACHE_FILE]) avoids re-downloading remote sources when
     * the source identity is unchanged, unless [forceRefresh].
     */
    fun refreshHosts(forceRefresh: Boolean) {
        // Empty Maybe = hosts already in the engine, nothing to do.
        val hostsMaybe: Maybe<String> = Maybe.defer {
            val dataSource = hostsDataSourceProvider.createHostsDataSource()
            val identity = dataSource.identifier()
            val cacheFile = File(application.filesDir, HOSTS_CACHE_FILE)

            if (!forceRefresh && hostsRepositoryInfo.identity == identity) {
                when {
                    hostsLoaded -> Maybe.empty()
                    cacheFile.exists() -> Maybe.fromCallable { cacheFile.readText() }
                    else -> fetchHosts(dataSource, cacheFile, identity).toMaybe()
                }
            } else {
                fetchHosts(dataSource, cacheFile, identity).toMaybe()
            }
        }

        compositeDisposable += hostsMaybe
            .subscribeOn(databaseScheduler)
            .observeOn(mainScheduler)
            .subscribeBy(
                onSuccess = { content ->
                    val count = ArktosCore.loadAdHosts(content)
                    hostsLoaded = true
                    logger.log(TAG, "Loaded $count ad-block hosts")
                },
                onError = {
                    logger.log(TAG, "Unable to load hosts", it)
                    application.toast(R.string.ad_block_load_failure)
                }
            )
    }

    private fun fetchHosts(
        dataSource: HostsDataSource,
        cacheFile: File,
        identity: String
    ): Single<String> = dataSource.loadHosts()
        .flatMap { result ->
            when (result) {
                is HostsResult.Success -> Single.just(result.hostsFileContent)
                is HostsResult.Failure -> Single.error<String>(result.cause)
            }
        }
        .doOnSuccess { content ->
            runCatching { cacheFile.writeText(content) }
            hostsRepositoryInfo.identity = identity
        }

    private fun loadMalwareTerms() {
        if (malwareLoaded) {
            return
        }
        compositeDisposable += Single
            .fromCallable {
                assetManager.open(MALWARE_LIST_FILE).bufferedReader().use { it.readText() }
            }
            .subscribeOn(databaseScheduler)
            .observeOn(mainScheduler)
            .subscribeBy(
                onSuccess = { content ->
                    ArktosCore.loadMalwareTerms(content)
                    malwareLoaded = true
                    logger.log(TAG, "Loaded malware list")
                },
                onError = { logger.log(TAG, "Unable to load malware list", it) }
            )
    }

    companion object {
        private const val TAG = "BlocklistLoader"
        private const val MALWARE_LIST_FILE = "malware.txt"
        private const val HOSTS_CACHE_FILE = "blocklist_hosts_cache.txt"
    }

}
