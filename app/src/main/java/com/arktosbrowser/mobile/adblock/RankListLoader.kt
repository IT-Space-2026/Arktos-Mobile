package com.arktosbrowser.mobile.adblock

import android.app.Application
import com.arktosbrowser.mobile.di.DatabaseScheduler
import com.arktosbrowser.mobile.di.HostsClient
import com.arktosbrowser.mobile.di.MainScheduler
import com.arktosbrowser.mobile.log.Logger
import com.arktosbrowser.mobile.preference.UserPreferences
import com.arktosbrowser.mobile.preference.userAgent
import com.arktosbrowser.mobile.rust.ArktosCore
import com.arktosbrowser.mobile.uniffi.RankListSource
import com.arktosbrowser.mobile.uniffi.RankListSourceKind
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.plusAssign
import io.reactivex.rxkotlin.subscribeBy
import java.io.File
import java.io.IOException
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okio.Buffer
import okio.buffer
import okio.sink

/**
 * Keeps the Rust site-rank table's remote sources fresh (RANK01) —
 * the mobile mirror of the desktop `TrancoList` updater.
 *
 * The Rust engine owns the merged table (vendored top-100k seed ∪
 * the two drop files, best-rank-wins) and reports staleness; this
 * class owns the network: each source's body is fetched https-only
 * (loopback http is the test-fixture exception), streamed to disk
 * under its drop name inside `<filesDir>/site-rank`, validated
 * through the engine's per-file probe, then `siteRankReload` merges
 * it. A body that yields no usable entries is junk and gets dropped
 * — a failed fetch can only ever shrink coverage back toward the
 * seed, never leave the feature empty.
 *
 * Update model mirrors the desktop:
 *  - [ensureInitialized] is the automatic path — fetches only when
 *    remote-list consent was granted AND a stored copy is missing or
 *    older than the same 7-day cadence the adblock subscriptions use.
 *  - [updateNow] is the explicit consent-free refresh the settings
 *    rows call.
 */
@Singleton
class RankListLoader @Inject constructor(
    private val userPreferences: UserPreferences,
    private val application: Application,
    private val logger: Logger,
    @HostsClient private val okHttpClient: Single<OkHttpClient>,
    @DatabaseScheduler private val databaseScheduler: Scheduler,
    @MainScheduler private val mainScheduler: Scheduler
) {

    private val compositeDisposable = CompositeDisposable()

    /**
     * Invoked after an update run finishes — settings rows re-read
     * stamps. Called on the main scheduler.
     */
    @Volatile var onUpdateFinished: ((Boolean) -> Unit)? = null

    /**
     * Idempotent entry point — points the engine at the rank dir and
     * kicks the consent-gated staleness check. Safe to call once per
     * WebView like `BlocklistLoader.ensureInitialized`.
     */
    fun ensureInitialized() {
        compositeDisposable += Single
            .fromCallable {
                ArktosCore.setRankListDataDir(rankDir().apply { mkdirs() }.absolutePath)
                ArktosCore.rankUpdateIfStale(remoteListsAllowed(), System.currentTimeMillis())
            }
            .subscribeOn(databaseScheduler)
            .observeOn(databaseScheduler)
            .subscribeBy(
                onSuccess = { sources ->
                    val enabled = enabledSources(sources)
                    if (enabled.isNotEmpty()) {
                        fetchAndCommit(enabled)
                    }
                },
                onError = { logger.log(TAG, "Unable to check rank-list staleness", it) }
            )
    }

    /** Explicit refresh — consent-free like the desktop `updateNow()`. */
    fun updateNow() {
        compositeDisposable += Single
            .fromCallable { enabledSources(ArktosCore.rankUpdateNow()) }
            .subscribeOn(databaseScheduler)
            .observeOn(databaseScheduler)
            .subscribeBy(
                onSuccess = { sources ->
                    if (sources.isNotEmpty()) {
                        fetchAndCommit(sources)
                    }
                },
                onError = { logger.log(TAG, "Unable to enumerate rank sources", it) }
            )
    }

    /**
     * Explicit refresh of one source — the settings row's per-list
     * update action (PREFS01). Consent-free like [updateNow]; a
     * no-op when the source is disabled.
     */
    fun updateSource(kind: RankListSourceKind) {
        compositeDisposable += Single
            .fromCallable {
                enabledSources(ArktosCore.rankListSources()).filter { it.kind == kind }
            }
            .subscribeOn(databaseScheduler)
            .observeOn(databaseScheduler)
            .subscribeBy(
                onSuccess = { sources ->
                    if (sources.isNotEmpty()) {
                        fetchAndCommit(sources)
                    }
                },
                onError = { logger.log(TAG, "Unable to enumerate rank sources", it) }
            )
    }

    /** The sources cleared for fetching — disabled rows drop out (PREFS01). */
    internal fun enabledSources(sources: List<RankListSource>): List<RankListSource> =
        sources.filter { isSourceEnabled(it.kind) }

    /** True while [kind] participates in rank-list updates (PREFS01). */
    fun isSourceEnabled(kind: RankListSourceKind): Boolean = when (kind) {
        RankListSourceKind.TRANCO -> userPreferences.trancoListEnabled
        RankListSourceKind.MAJESTIC -> userPreferences.majesticListEnabled
    }

    /**
     * Toggle one rank source (PREFS01). Disabling also removes the
     * committed drop file — the list then contributes no ranks and
     * its last-update stamp reads "never" again, so a disabled row
     * can't keep a stale copy alive. Re-enabling simply lets the next
     * update fetch it again.
     */
    fun setSourceEnabled(kind: RankListSourceKind, enabled: Boolean) {
        when (kind) {
            RankListSourceKind.TRANCO -> userPreferences.trancoListEnabled = enabled
            RankListSourceKind.MAJESTIC -> userPreferences.majesticListEnabled = enabled
        }
        if (!enabled) {
            compositeDisposable += Single
                .fromCallable {
                    val dropped = ArktosCore.rankListSources()
                        .firstOrNull { it.kind == kind }
                        ?.let { File(rankDir(), it.fileName).delete() } ?: false
                    dropped to ArktosCore.reloadRankLists()
                }
                .subscribeOn(databaseScheduler)
                .observeOn(mainScheduler)
                .subscribeBy(
                    onSuccess = { onUpdateFinished?.invoke(true) },
                    onError = {
                        logger.log(TAG, "Unable to drop disabled rank source", it)
                        onUpdateFinished?.invoke(false)
                    }
                )
        }
    }

    /** The host's merged site rank; 0 when unranked. */
    fun rank(host: String): Long = ArktosCore.siteRank(host)

    /** The two sources with current last-update stamps — settings rows. */
    fun sources(): List<RankListSource> = ArktosCore.rankListSources()

    /** Merged table entry count — settings diagnostics. */
    fun entryCount(): Long = ArktosCore.rankEntryCount()

    /**
     * Remote-list consent, mirroring the desktop
     * `AdBlockManager::remoteListsAllowed()`: PREFS01's dedicated
     * toggle is the user's "yes" to remote list downloads — the same
     * gate the filter subscriptions' automatic fetches check. Manual
     * refresh actions ([updateNow]/[updateSource]) stay consent-free.
     */
    private fun remoteListsAllowed(): Boolean = userPreferences.remoteListsConsentEnabled

    private fun rankDir(): File = File(application.filesDir, RANK_DIR)

    private fun fetchAndCommit(sources: List<RankListSource>) {
        compositeDisposable += Single
            .fromCallable { performUpdate(sources) }
            .subscribeOn(databaseScheduler)
            .observeOn(mainScheduler)
            .subscribeBy(
                onSuccess = { ok -> onUpdateFinished?.invoke(ok) },
                onError = {
                    logger.log(TAG, "Rank-list update failed", it)
                    onUpdateFinished?.invoke(false)
                }
            )
    }

    /**
     * The synchronous update pipeline — fetch each source, commit its
     * body under the drop name, sweep junk, reload the merged table.
     * Mirrors the desktop `TrancoList.maybeCommit` flow; exposed for
     * tests. Call on a background scheduler.
     */
    internal fun performUpdate(sources: List<RankListSource>): Boolean {
        var failed = false
        var wrote = false
        for (source in sources) {
            if (source.fileName.isEmpty() || source.fileName.contains('/')) {
                continue
            }
            try {
                download(source.url, File(rankDir(), source.fileName))
                wrote = true
            } catch (e: IOException) {
                logger.log(TAG, "Rank-list fetch failed: ${source.url}", e)
                failed = true
            }
        }
        if (!wrote) {
            return false
        }
        // Sweep every known drop point (not just the ones this run
        // wrote): a file yielding no usable entries is junk — corrupt
        // zip, error page — and gets dropped so it can neither fail
        // the reload nor jam a sibling's future commits.
        for (source in ArktosCore.rankListSources()) {
            val file = File(rankDir(), source.fileName)
            if (file.exists() && ArktosCore.rankFileEntries(source.fileName) == 0L) {
                logger.log(TAG, "${source.fileName} yielded no usable entries — dropping it")
                file.delete()
                failed = true
            }
        }
        val reloaded = runCatching { ArktosCore.reloadRankLists() }.isSuccess
        if (!reloaded) {
            logger.log(TAG, "Merged rank table did not reload")
        }
        return reloaded && !failed
    }

    /**
     * Blocking fetch of one source body into [target] (written via a
     * temp file + rename, the `QSaveFile` pattern). https-only, one
     * redirect hop per response, 128MB cap — the desktop rules.
     */
    private fun download(sourceUrl: String, target: File) {
        val client = (okHttpClient.blockingGet() ?: throw IOException("No HTTP client"))
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        var url = HttpUrl.parse(sourceUrl)
            ?: throw IOException("Invalid rank source $sourceUrl")
        for (hop in 0..MAX_REDIRECT_HOPS) {
            if (!fetchAllowed(url)) {
                throw IOException("Refusing non-https rank source $url")
            }
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userPreferences.userAgent(application))
                .get()
                .build()
            var redirected = false
            client.newCall(request).execute().use { response ->
                val location = response.header("Location")
                if (response.isRedirect && location != null) {
                    // One hop per reply like the desktop — the next
                    // loop iteration re-checks https (a downgrade would
                    // strip the integrity the source list needs).
                    url = url.resolve(location)
                        ?: throw IOException("Bad redirect target $location")
                    redirected = true
                    return@use
                }
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code()} fetching $url")
                }
                target.parentFile?.mkdirs()
                writeBody(response.body() ?: throw IOException("Empty response fetching $url"), target)
                return
            }
            if (!redirected) {
                throw IOException("Fetch of $url produced no body")
            }
        }
        throw IOException("Too many redirects fetching $sourceUrl")
    }

    /**
     * Whether a URL may be fetched: https anywhere, or plaintext http
     * to a loopback literal — the test-fixture exception the desktop
     * grants (TrancoList::updateNow's QHostAddress check).
     */
    internal fun fetchAllowed(url: HttpUrl): Boolean = when {
        url.isHttps -> true
        url.scheme() != "http" -> false
        else -> isLoopbackHost(url.host())
    }

    private fun isLoopbackHost(host: String): Boolean {
        if (host.equals("localhost", ignoreCase = true)) {
            return true
        }
        // Literal-only loopback check — never resolve a real hostname.
        val literal = host.contains(':') || host.matches(IPV4_LITERAL)
        if (!literal) {
            return false
        }
        return runCatching {
            InetAddress.getByName(host.trim('[', ']')).isLoopbackAddress
        }.getOrDefault(false)
    }

    /**
     * Stream the response body to [target] via a sibling temp file,
     * enforcing the size cap. A zip body lands verbatim (a prepended
     * text header would break the engine's PK-magic sniff); plaintext
     * CSV gets the provenance comment the desktop writes.
     */
    private fun writeBody(body: ResponseBody, target: File) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        try {
            val source = body.source()
            val isZip = source.peek().let { peek ->
                if (!peek.request(PK_MAGIC.size.toLong())) {
                    false
                } else {
                    val buf = Buffer()
                    peek.read(buf, PK_MAGIC.size.toLong())
                    buf.readByteArray().contentEquals(PK_MAGIC)
                }
            }
            tmp.sink().buffer().use { sink ->
                if (!isZip) {
                    sink.writeUtf8(
                        "# Arktos site-rank list update\n# source: ${target.name}\n"
                    )
                }
                val chunk = Buffer()
                var total = 0L
                while (source.read(chunk, CHUNK_SIZE.toLong()) != -1L) {
                    total += chunk.size
                    if (total > MAX_LIST_SIZE) {
                        throw IOException("Rank list body exceeds the size cap")
                    }
                    sink.writeAll(chunk)
                }
            }
            target.delete()
            if (!tmp.renameTo(target)) {
                throw IOException("Unable to commit ${target.name}")
            }
        } finally {
            tmp.delete()
        }
    }

    companion object {
        private const val TAG = "RankListLoader"
        private const val RANK_DIR = "site-rank"

        /** The desktop's fetch ceiling — a body larger than this is
         * an error page or an attack, not a rank list. */
        private const val MAX_LIST_SIZE = 128L * 1024 * 1024
        private const val MAX_REDIRECT_HOPS = 5
        private const val CHUNK_SIZE = 64 * 1024
        private val PK_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        private val IPV4_LITERAL = Regex("\\d+\\.\\d+\\.\\d+\\.\\d+")
    }
}
