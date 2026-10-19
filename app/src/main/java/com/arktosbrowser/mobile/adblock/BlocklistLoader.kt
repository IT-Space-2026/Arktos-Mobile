package com.arktosbrowser.mobile.adblock

import android.app.Application
import android.content.res.AssetManager
import com.arktosbrowser.mobile.adblock.source.AssetsHostsDataSource
import com.arktosbrowser.mobile.adblock.source.FileHostsDataSource
import com.arktosbrowser.mobile.adblock.source.HostsResult
import com.arktosbrowser.mobile.adblock.source.HostsSourceType
import com.arktosbrowser.mobile.adblock.source.UrlHostsDataSource
import com.arktosbrowser.mobile.adblock.source.selectedHostsSource
import com.arktosbrowser.mobile.database.adblock.AdBlockListState
import com.arktosbrowser.mobile.di.DatabaseScheduler
import com.arktosbrowser.mobile.di.HostsClient
import com.arktosbrowser.mobile.di.MainScheduler
import com.arktosbrowser.mobile.log.Logger
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.rust.ArktosCore
import com.arktosbrowser.mobile.uniffi.ArktosStore
import com.arktosbrowser.mobile.uniffi.StoreAdblockSubscription
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.plusAssign
import io.reactivex.rxkotlin.subscribeBy
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/**
 * Loads filter-list content into the Rust `BlocklistEngine`
 * (via the [ArktosCore] seam) and keeps it in sync with the
 * subscription model (LIST01).
 *
 * Subscriptions are rows in the Rust `ArktosStore` — seeded once from
 * the desktop-mirrored preset catalog plus the bundled `hosts.txt`
 * baseline, plus any custom lists the user adds. Kotlin keeps data
 * *sourcing* (assets/files/remote fetches + per-list disk cache) and
 * pushes raw list content into Rust; each enabled list merges into one
 * lookup set so per-request verdicts stay on the BLOCK01 hot path.
 */
@Singleton
class BlocklistLoader @Inject constructor(
    private val userPreferences: UserPreferences,
    private val adBlockListState: AdBlockListState,
    private val arktosStore: ArktosStore,
    private val assetManager: AssetManager,
    private val application: Application,
    private val logger: Logger,
    @HostsClient private val okHttpClient: Single<OkHttpClient>,
    @DatabaseScheduler private val databaseScheduler: Scheduler,
    @MainScheduler private val mainScheduler: Scheduler
) {

    private val compositeDisposable = CompositeDisposable()

    /**
     * Invoked on the main thread after a subscription was pushed into
     * the engine — the settings screen re-reads counts/stamps.
     */
    @Volatile var onSubscriptionsChanged: (() -> Unit)? = null

    @Volatile private var malwareLoaded = false

    /**
     * Idempotent entry point — syncs preference-derived state and
     * kicks off the subscription sync if it hasn't happened yet. Safe
     * to call once per WebView.
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
     * All subscription rows in seed order — cheap enough to read on
     * demand (one indexed SELECT over a small table).
     */
    fun allSubscriptions(): List<StoreAdblockSubscription> =
        arktosStore.allAdblockSubscriptions()

    /**
     * (Re)load every enabled subscription into the engine. Remote
     * lists are fetched only when [forceRefresh] or their stored copy
     * is missing/older than [STALE_AFTER_MS] — the same 7-day cadence
     * the desktop `AdBlockManager` uses.
     */
    fun refreshHosts(forceRefresh: Boolean) {
        compositeDisposable += Single
            .fromCallable {
                seedSubscriptionsIfNeeded()
                arktosStore.allAdblockSubscriptions().filter { it.enabled }
            }
            .subscribeOn(databaseScheduler)
            .observeOn(mainScheduler)
            .subscribeBy(
                onSuccess = { enabled ->
                    enabled.forEach { loadSubscription(it, forceRefresh) }
                },
                onError = {
                    logger.log(TAG, "Unable to enumerate subscriptions", it)
                }
            )
    }

    /**
     * Refresh one subscription — the settings row's per-list update
     * action (PREFS01). Consent-free like the desktop's
     * "Update Subscription" button: the user's explicit click is the
     * consent. No-op for unknown or disabled ids — refreshing a
     * disabled list must not merge rules into the engine.
     */
    fun refreshSubscription(id: String) {
        compositeDisposable += Single
            .fromCallable { arktosStore.allAdblockSubscriptions() }
            .subscribeOn(databaseScheduler)
            .observeOn(mainScheduler)
            .subscribeBy(
                onSuccess = { subs ->
                    subs.firstOrNull { it.id == id }
                        ?.takeIf { it.enabled }
                        ?.let { loadSubscription(it, forceRefresh = true) }
                },
                onError = { logger.log(TAG, "Unable to refresh subscription $id", it) }
            )
    }

    /**
     * Toggle one subscription: persist the flag, then either load its
     * content into the engine or drop its contribution.
     */
    fun setSubscriptionEnabled(id: String, enabled: Boolean) {
        compositeDisposable += Single
            .fromCallable {
                arktosStore.setAdblockSubscriptionEnabled(id, enabled)
                arktosStore.allAdblockSubscriptions()
            }
            .subscribeOn(databaseScheduler)
            .observeOn(mainScheduler)
            .subscribeBy(
                onSuccess = { subs ->
                    val sub = subs.firstOrNull { it.id == id } ?: return@subscribeBy
                    if (enabled) {
                        loadSubscription(sub, forceRefresh = false)
                    } else {
                        ArktosCore.removeSubscriptionHosts(sub.id)
                        notifyChanged()
                    }
                },
                onError = { logger.log(TAG, "Unable to toggle subscription $id", it) }
            )
    }

    /**
     * Add a user-provided list (remote `https?://` URL or a
     * `file://`-resolved local path), enabled immediately in the
     * "Custom" group. No-op if the source is already subscribed.
     */
    fun addCustomSubscription(name: String, url: String) {
        compositeDisposable += Single
            .fromCallable {
                val added = arktosStore.addAdblockSubscription(
                    StoreAdblockSubscription(
                        id = url,
                        name = name,
                        url = url,
                        category = CUSTOM_CATEGORY,
                        enabled = true,
                        lastUpdated = 0uL,
                        hostCount = 0uL,
                        custom = true
                    )
                )
                added to url
            }
            .subscribeOn(databaseScheduler)
            .observeOn(mainScheduler)
            .subscribeBy(
                onSuccess = { (added, id) ->
                    arktosStore.allAdblockSubscriptions()
                        .firstOrNull { it.id == id }
                        ?.let {
                            // Re-adding an existing (e.g. preset) URL
                            // acts as "subscribe" — enable + refetch.
                            if (!added) {
                                arktosStore.setAdblockSubscriptionEnabled(it.id, true)
                            }
                            loadSubscription(it, forceRefresh = !added)
                        }
                },
                onError = { logger.log(TAG, "Unable to add subscription $url", it) }
            )
    }

    private fun notifyChanged() {
        val listener = onSubscriptionsChanged ?: return
        mainScheduler.scheduleDirect { listener() }
    }

    /**
     * Seed the catalog once: the bundled baseline list, every preset
     * (default-enabled mirrors the desktop), then migrate the legacy
     * single-source `hostsSource` pref into a custom subscription —
     * which is also the fix for upstream #312 (multiple hosts files).
     */
    private fun seedSubscriptionsIfNeeded() {
        if (adBlockListState.subscriptionsSeeded) {
            return
        }
        arktosStore.addAdblockSubscription(
            StoreAdblockSubscription(
                id = BUILTIN_SUBSCRIPTION_ID,
                name = BUILTIN_NAME,
                url = BUILTIN_SUBSCRIPTION_ID,
                category = BUILTIN_CATEGORY,
                enabled = true,
                lastUpdated = 0uL,
                hostCount = 0uL,
                custom = false
            )
        )
        for (preset in ArktosCore.adBlockPresets()) {
            arktosStore.addAdblockSubscription(
                StoreAdblockSubscription(
                    id = preset.url,
                    name = preset.name,
                    url = preset.url,
                    category = preset.category,
                    enabled = preset.enabledByDefault,
                    lastUpdated = 0uL,
                    hostCount = 0uL,
                    custom = false
                )
            )
        }
        migrateLegacyHostsSource()
        // The pre-LIST01 single-source cache file is superseded by the
        // per-subscription cache dir.
        File(application.filesDir, LEGACY_HOSTS_CACHE_FILE).delete()
        adBlockListState.subscriptionsSeeded = true
    }

    private fun migrateLegacyHostsSource() {
        when (val source = userPreferences.selectedHostsSource()) {
            is HostsSourceType.Local -> arktosStore.addAdblockSubscription(
                StoreAdblockSubscription(
                    id = "file://${source.file.absolutePath}",
                    name = source.file.name,
                    url = "file://${source.file.absolutePath}",
                    category = CUSTOM_CATEGORY,
                    enabled = true,
                    lastUpdated = 0uL,
                    hostCount = 0uL,
                    custom = true
                )
            )
            is HostsSourceType.Remote -> arktosStore.addAdblockSubscription(
                StoreAdblockSubscription(
                    id = source.httpUrl.toString(),
                    name = source.httpUrl.host(),
                    url = source.httpUrl.toString(),
                    category = CUSTOM_CATEGORY,
                    enabled = true,
                    lastUpdated = 0uL,
                    hostCount = 0uL,
                    custom = true
                )
            )
            HostsSourceType.Default -> {}
        }
    }

    /**
     * Fetch (or read the cache of) one subscription's content, push it
     * into the engine, and stamp the row. Failures are logged and skip
     * the list — a stale/missing subscription never blocks the others.
     */
    private fun loadSubscription(sub: StoreAdblockSubscription, forceRefresh: Boolean) {
        compositeDisposable += contentFor(sub, forceRefresh)
            .subscribeOn(databaseScheduler)
            .observeOn(databaseScheduler)
            .subscribeBy(
                onSuccess = { content ->
                    val count = ArktosCore.loadSubscriptionHosts(sub.id, content)
                    arktosStore.setAdblockSubscriptionState(
                        sub.id,
                        System.currentTimeMillis().toULong(),
                        count.toULong()
                    )
                    logger.log(TAG, "Loaded $count domains for ${sub.name}")
                    notifyChanged()
                },
                onError = {
                    logger.log(TAG, "Unable to load subscription ${sub.name}", it)
                }
            )
    }

    /**
     * Resolve a subscription's list text: the bundled asset for the
     * baseline row, a file read for `file:` rows, a cached/remote
     * fetch for remote rows.
     */
    private fun contentFor(
        sub: StoreAdblockSubscription,
        forceRefresh: Boolean
    ): Single<String> {
        val localResult = when {
            sub.id == BUILTIN_SUBSCRIPTION_ID ->
                AssetsHostsDataSource(assetManager, logger).loadHosts()
            sub.url.startsWith(FILE_PREFIX) ->
                FileHostsDataSource(logger, File(sub.url.removePrefix(FILE_PREFIX))).loadHosts()
            else -> return remoteContent(sub, forceRefresh)
        }
        return localResult.flatMap { result ->
            when (result) {
                is HostsResult.Success -> Single.just(result.hostsFileContent)
                is HostsResult.Failure -> Single.error<String>(result.cause)
            }
        }
    }

    /**
     * Remote-list consent, mirroring the desktop
     * `AdBlockManager::remoteListsAllowed()` — PREFS01's dedicated
     * toggle gates automatic remote fetches for the filter
     * subscriptions AND the rank lists; manual refresh actions are
     * the user's explicit consent and bypass it.
     */
    private fun remoteListsAllowed(): Boolean = userPreferences.remoteListsConsentEnabled

    private fun remoteContent(
        sub: StoreAdblockSubscription,
        forceRefresh: Boolean
    ): Single<String> {
        val cacheFile = File(subscriptionCacheDir(), md5(sub.id))
        val fresh = !forceRefresh &&
            sub.lastUpdated > 0uL &&
            System.currentTimeMillis() - sub.lastUpdated.toLong() < STALE_AFTER_MS &&
            cacheFile.exists()

        // Automatic path is consent-gated like the desktop: without
        // remote-list consent a stale/uncached copy is simply skipped
        // (a cached copy still loads — it's local data). Explicit
        // refreshes always fetch.
        if (!forceRefresh && !fresh && !remoteListsAllowed()) {
            return if (cacheFile.exists()) {
                Single.fromCallable { cacheFile.readText() }
            } else {
                Single.error(IllegalStateException("Remote-list consent not granted"))
            }
        }

        if (fresh) {
            return Single.fromCallable { cacheFile.readText() }
        }

        val httpUrl = HttpUrl.parse(sub.url)
            ?: return Single.error(IllegalArgumentException("Invalid subscription URL ${sub.url}"))

        return UrlHostsDataSource(httpUrl, okHttpClient, logger, userPreferences, application)
            .loadHosts()
            .flatMap { result ->
                when (result) {
                    is HostsResult.Success -> Single.just(result.hostsFileContent)
                    is HostsResult.Failure -> Single.error<String>(result.cause)
                }
            }
            .doOnSuccess { content ->
                runCatching {
                    cacheFile.parentFile?.mkdirs()
                    cacheFile.writeText(content)
                }
            }
    }

    private fun subscriptionCacheDir(): File =
        File(application.filesDir, SUBSCRIPTION_CACHE_DIR)

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
        private const val SUBSCRIPTION_CACHE_DIR = "adblock_subscriptions"
        private const val LEGACY_HOSTS_CACHE_FILE = "blocklist_hosts_cache.txt"
        private const val FILE_PREFIX = "file://"

        /** Remote lists re-fetch when their copy is older than this —
         * the same 7-day cadence as the desktop `AdBlockManager`. */
        private const val STALE_AFTER_MS = 7L * 24 * 60 * 60 * 1000

        /** Pseudo-id of the bundled `hosts.txt` baseline list. */
        const val BUILTIN_SUBSCRIPTION_ID = "builtin:hosts.txt"
        const val BUILTIN_NAME = "Built-in hosts list"
        const val BUILTIN_CATEGORY = "Built-in"
        const val CUSTOM_CATEGORY = "Custom"
    }
}

private fun md5(value: String): String =
    MessageDigest.getInstance("MD5")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
