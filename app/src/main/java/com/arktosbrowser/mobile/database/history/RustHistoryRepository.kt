package com.arktosbrowser.mobile.database.history

import com.arktosbrowser.mobile.database.HistoryEntry
import com.arktosbrowser.mobile.uniffi.ArktosStore
import com.arktosbrowser.mobile.uniffi.StoreHistoryEntry
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [HistoryRepository] backed by the Rust storage core (STORE01).
 */
@Singleton
class RustHistoryRepository @Inject constructor(
    private val store: ArktosStore
) : HistoryRepository {

    override fun deleteHistory(): Completable = Completable.fromAction {
        store.deleteHistory()
    }

    override fun deleteHistoryEntry(url: String): Completable = Completable.fromAction {
        store.deleteHistoryEntry(url)
    }

    override fun visitHistoryEntry(url: String, title: String?): Completable =
        Completable.fromAction {
            store.visitHistoryEntry(url, title)
        }

    override fun findHistoryEntriesContaining(query: String): Single<List<HistoryEntry>> =
        Single.fromCallable {
            store.findHistoryEntriesContaining(query).map { it.toHistoryEntry() }
        }

    override fun lastHundredVisitedHistoryEntries(): Single<List<HistoryEntry>> =
        Single.fromCallable {
            store.lastHundredHistoryEntries().map { it.toHistoryEntry() }
        }
}

internal fun StoreHistoryEntry.toHistoryEntry() = HistoryEntry(
    url = url,
    title = title,
    lastTimeVisited = lastTimeVisited.toLong()
)
