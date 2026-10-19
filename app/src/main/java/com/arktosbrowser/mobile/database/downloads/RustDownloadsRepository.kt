package com.arktosbrowser.mobile.database.downloads

import androidx.annotation.WorkerThread
import com.arktosbrowser.mobile.uniffi.ArktosStore
import com.arktosbrowser.mobile.uniffi.StoreDownloadEntry
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Single
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [DownloadsRepository] backed by the Rust storage core (STORE01).
 */
@Singleton
class RustDownloadsRepository @Inject constructor(
    private val store: ArktosStore
) : DownloadsRepository {

    override fun isDownload(url: String): Single<Boolean> = Single.fromCallable {
        store.isDownload(url)
    }

    override fun findDownloadForUrl(url: String): Maybe<DownloadEntry> = Maybe.fromCallable {
        store.findDownloadForUrl(url)?.toDownloadEntry()
    }

    override fun addDownloadIfNotExists(entry: DownloadEntry): Single<Boolean> =
        Single.fromCallable {
            store.addDownloadIfNotExists(entry.toStoreDownloadEntry())
        }

    override fun addDownloadsList(downloadEntries: List<DownloadEntry>): Completable =
        Completable.fromAction {
            store.addDownloadsList(downloadEntries.map { it.toStoreDownloadEntry() })
        }

    override fun deleteDownload(url: String): Single<Boolean> = Single.fromCallable {
        store.deleteDownload(url)
    }

    override fun deleteAllDownloads(): Completable = Completable.fromAction {
        store.deleteAllDownloads()
    }

    override fun getAllDownloads(): Single<List<DownloadEntry>> = Single.fromCallable {
        store.allDownloads().map { it.toDownloadEntry() }
    }

    @WorkerThread
    override fun count(): Long = store.downloadCount().toLong()
}

internal fun DownloadEntry.toStoreDownloadEntry() = StoreDownloadEntry(
    url = url,
    title = title,
    contentSize = contentSize
)

internal fun StoreDownloadEntry.toDownloadEntry() = DownloadEntry(
    url = url,
    title = title,
    contentSize = contentSize
)
