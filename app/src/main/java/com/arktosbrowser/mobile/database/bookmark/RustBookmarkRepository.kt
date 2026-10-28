// SPDX-License-Identifier: MPL-2.0
// Copyright Arktos contributors
package com.arktosbrowser.mobile.database.bookmark

import android.app.Application
import androidx.annotation.WorkerThread
import com.arktosbrowser.mobile.R
import com.arktosbrowser.mobile.database.Bookmark
import com.arktosbrowser.mobile.database.asFolder
import com.arktosbrowser.mobile.uniffi.ArktosStore
import com.arktosbrowser.mobile.uniffi.StoreBookmark
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [BookmarkRepository] backed by the Rust storage core (STORE01).
 * Replaces the `SQLiteOpenHelper`-based `BookmarkDatabase`; all query
 * semantics (ordering, slash-twin URL matching, folder paths) live in
 * `arktos-core`'s `storage` module.
 */
@Singleton
class RustBookmarkRepository @Inject constructor(
    private val application: Application,
    private val store: ArktosStore,
    private val sync: com.arktosbrowser.mobile.sync.SyncRepository
) : BookmarkRepository {

    private val dataRoot = application.filesDir.absolutePath

    /** Append a bookmark op to the sync log — best-effort. */
    private fun syncAppend(bodyJson: String) {
        runCatching { sync.append(dataRoot, "bookmarks", bodyJson) }
    }

    private val defaultBookmarkTitle: String = application.getString(R.string.untitled)

    override fun findBookmarkForUrl(url: String): Maybe<Bookmark.Entry> = Maybe.fromCallable {
        store.findBookmarkForUrl(url)?.toBookmarkEntry()
    }

    override fun moveBookmark(entry: Bookmark.Entry, newPosition: Int): Completable =
        Completable.fromAction {
            store.moveBookmark(entry.url, newPosition.toUInt())
        }

    override fun isBookmark(url: String): Single<Boolean> = Single.fromCallable {
        store.isBookmark(url)
    }

    override fun addBookmarkIfNotExists(entry: Bookmark.Entry): Single<Boolean> =
        Single.fromCallable {
            store.addBookmarkIfNotExists(entry.toStoreBookmark(defaultBookmarkTitle))
        }
        .doOnSuccess { added ->
            if (added) {
                val e = entry.toStoreBookmark(defaultBookmarkTitle)
                syncAppend("""{"add":{"url":"${e.url}","title":"${e.title}","folder":"${e.folder}","position":${e.position}}}""")
            }
        }

    override fun addBookmarkList(bookmarkItems: List<Bookmark.Entry>): Completable =
        Completable.fromAction {
            store.addBookmarkList(bookmarkItems.map { it.toStoreBookmark(defaultBookmarkTitle) })
        }

    override fun deleteBookmark(entry: Bookmark.Entry): Single<Boolean> = Single.fromCallable {
        store.deleteBookmark(entry.url)
    }
        .doOnSuccess { removed -> if (removed) syncAppend("""{"remove":{"url":"${entry.url}"}}""") }

    override fun renameFolder(oldName: String, newName: String): Completable =
        Completable.fromAction {
            store.renameFolder(oldName, newName)
        }
        .doOnComplete { syncAppend("""{"folder":{"rename":["$oldName","$newName"]}}""") }

    override fun addFolder(name: String): Completable = Completable.fromAction {
        store.addFolder(name)
    }
        .doOnComplete { syncAppend("""{"folder":{"add":"$name"}}""") }

    override fun deleteFolder(folderToDelete: String): Completable = Completable.fromAction {
        store.deleteFolder(folderToDelete)
    }
        .doOnComplete { syncAppend("""{"folder":{"remove":"$folderToDelete"}}""") }

    override fun deleteAllBookmarks(): Completable = Completable.fromAction {
        store.deleteAllBookmarks()
    }

    override fun editBookmark(oldBookmark: Bookmark.Entry, newBookmark: Bookmark.Entry): Completable =
        Completable.fromAction {
            store.editBookmark(oldBookmark.url, newBookmark.toStoreBookmark(defaultBookmarkTitle))
        }
        .doOnComplete {
            val e = newBookmark.toStoreBookmark(defaultBookmarkTitle)
            syncAppend("""{"edit":{"old_url":"${oldBookmark.url}","url":"${e.url}","title":"${e.title}","folder":"${e.folder}","position":${e.position}}}""")
        }

    override fun getAllBookmarksSorted(): Single<List<Bookmark.Entry>> = Single.fromCallable {
        store.allBookmarksSorted().map { it.toBookmarkEntry() }
    }

    override fun getBookmarksFromFolderSorted(folder: String?): Single<List<Bookmark>> =
        Single.fromCallable {
            store.bookmarksInFolder(folder).map { it.toBookmarkEntry() }
        }

    override fun getBookmarksFromFolderByDate(folder: String?): Single<List<Bookmark>> =
        Single.fromCallable {
            store.bookmarksInFolderByDate(folder).map { it.toBookmarkEntry() }
        }

    override fun getFoldersSorted(): Single<List<Bookmark.Folder>> = Single.fromCallable {
        store.folderNames().map(String::asFolder)
    }

    override fun getFolderNames(): Single<List<String>> = Single.fromCallable {
        store.folderNames()
    }

    @WorkerThread
    override fun count(): Long = store.bookmarkCount().toLong()
}

/** `createdAt` 0 lets the store stamp the insert time. */
internal fun Bookmark.Entry.toStoreBookmark(defaultTitle: String) = StoreBookmark(
    url = url,
    title = title.takeIf(String::isNotBlank) ?: defaultTitle,
    folder = folder.title,
    position = position.toUInt(),
    createdAt = 0u
)

internal fun StoreBookmark.toBookmarkEntry() = Bookmark.Entry(
    url = url,
    title = title,
    position = position.toInt(),
    folder = folder.asFolder()
)
